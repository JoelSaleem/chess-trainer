#!/usr/bin/env node
// Collect the day's stories into the feed: the finished rounds of the top
// broadcasts, the games worth a story from each, and the move where each
// turned. Runs in Lambda every two hours (lambda.mjs), and by hand for a
// backfill:
//
//   node scripts/feed/collect.mjs                      # what the Lambda does: the last three days
//   node scripts/feed/collect.mjs --hours 720 --pages 5 --workers 4   # a month back
//   node scripts/feed/collect.mjs --tour n1pPI5Q0      # one broadcast, whatever its tier
//   node scripts/feed/collect.mjs --dry-run            # read and analyse, write nothing
//   node scripts/feed/collect.mjs --site               # and the new stories' pages, with the local build
//
// A day of an event is read once all its rounds are over, and read once: its
// stories are chosen from the whole day rather than from whichever games had
// finished when the collector happened to look, and a day it has read is
// never read again.
// Every story is written as "auto" — article.mjs's words, in every language
// the app speaks, which say only what the broadcast and the engine say. A person rewriting and approving one
// is publish.mjs's business; the collector never touches a story that exists.
import { readFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { Chess } from 'chess.js';

import { candidateTours, loadPlayers, score, slugify, turningPoint } from './analyse.mjs';
import { createEngines } from './engine-pool.mjs';
import { parsePgn, roundPgn } from './lichess.mjs';
import { openPages, publishPages } from './pages.mjs';
import { generatePremiumStoryWords } from './premium.mjs';
import { openStore, stored } from './store.mjs';
import { articles } from './article.mjs';
import { eventNames, person } from './words.mjs';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../..');

/**
 * Stories per event per day, however many broadcasts relay it. A top-tier event gets five from its open section
 * and two from its women's; a tier below gets three, and only games with a followed
 * player or somebody strong in them — that tier is every strong national
 * league and open, and without the bar it would bury the events people follow.
 * The bar is 2600 in an open event and 2450 in a women's one, where the best
 * players are rated lower: at 2680 a women's league gave nothing at all.
 */
const QUOTA = { Women: 2, default: 5 };
const LOWER_TIER = { quota: 3, elo: 2600, womenElo: 2450 };
/** Past this, a round whose broadcast has not said it is over is taken to be. */
const ROUND_OVER_MS = 14 * 3600_000;

export async function collect({
  store,
  hours = 72,
  budgetMs = 10 * 60_000,
  workers = 1,
  only = null,
  pages = 1,
  minElo = 2400,
  depth = 12,
  // The website's side — openPages(): each new public story's page, and the
  // lists. Without it the collector writes the feed and nothing else.
  site = null,
  log = console.error,
} = {}) {
  const started = Date.now();
  const lookup = loadPlayers(JSON.parse(await readFile(resolve(ROOT, 'feed/players.json'), 'utf8')));
  const state = await store.state();
  const since = started - hours * 3600_000;
  const taken = new Set(Object.values(state.days).flat());

  // What is left to do: every day of every broadcast whose rounds are all
  // over, and from each day the games it has room for. By day rather than by
  // round because a round is not the same thing everywhere — an Olympiad
  // plays one a day, a league plays a dozen six-game matches, and a quota per
  // round would give the league sixty stories to the Olympiad's seven.
  const jobs = [];
  const groups = [];
  let tours = [];
  try {
    tours = await candidateTours({ tour: only, pages });
  } catch (error) {
    log(`Could not read the broadcasts (${error.message}); nothing to do until the next run`);
  }
  // One event, one quota. A broadcast group can relay the same section more
  // than once — a group stage as "Combined" and again as "Group A" to "Group D"
  // — and read tour by tour that is the same game five times over and five
  // quotas where the event has one. So the tours of an event's section are
  // read together, a day at a time, and a game two of them carry is one game.
  const events = new Map();
  for (const t of tours) {
    const names = eventNames(t);
    const eventKey = `${names.name}|${names.section ?? ''}`;
    const event = events.get(eventKey) ?? { names, top: false, title: [names.name, names.section].filter(Boolean).join(' | '), byDay: new Map() };
    events.set(eventKey, event);
    event.top ||= Boolean(only) || (t.tour.tier ?? 0) >= 5;
    for (const round of t.rounds ?? []) {
      if (!round.startsAt || round.startsAt < since || round.startsAt > started) continue;
      const date = new Date(round.startsAt).toISOString().slice(0, 10);
      event.byDay.set(date, [...(event.byDay.get(date) ?? []), { t, round }]);
    }
  }
  const sameGame = (g) =>
    [g.tags.WhiteFideId || g.tags.White, g.tags.BlackFideId || g.tags.Black, g.tags.Result, g.sans.slice(0, 20).join(' ')].join('|');
  for (const { names, top, title, byDay } of events.values()) {
    for (const [date, dayRounds] of byDay) {
      const dayTours = [...new Map(dayRounds.map(({ t }) => [t.tour.id, t])).values()];
      const keyOf = (t) => `${t.tour.id}:${date}`;
      const read = dayTours.filter((t) => state.rounds[keyOf(t)]?.done);
      if (read.length === dayTours.length) continue;
      if (read.length) {
        // Read tour by tour before the tours were read together: the day has
        // had its stories, and the tours left would only repeat them.
        for (const t of dayTours) (state.rounds[keyOf(t)] ??= { tour: t.tour.id, name: `${t.tour.name} · ${date}`, games: [] }).done = true;
        continue;
      }
      const over = dayRounds.every(({ round: r }) => r.finished === true || started - r.startsAt > ROUND_OVER_MS);
      if (!over) {
        log(`  ${title} · ${date}: still being played`);
        continue;
      }
      const candidates = [];
      const seen = new Set();
      try {
        for (const { t, round } of dayRounds) {
          const games = parsePgn(await roundPgn(round.id)).filter(
            (g) => ['1-0', '0-1', '1/2-1/2'].includes(g.tags.Result) && (g.tags.Variant ?? 'Standard') === 'Standard',
          );
          for (const game of games) {
            const same = sameGame(game);
            if (seen.has(same)) continue;
            seen.add(same);
            candidates.push({ game, round, t });
          }
        }
      } catch (error) {
        // Not marked read: the next run asks again.
        log(`  ${title} · ${date}: could not be read (${error.message}) — next time`);
        continue;
      }
      const quota = top ? (QUOTA[names.section] ?? QUOTA.default) : LOWER_TIER.quota;
      const followed = (g) => lookup(g.tags.White, g.tags.WhiteFideId)?.star || lookup(g.tags.Black, g.tags.BlackFideId)?.star;
      const bar = /women/i.test(`${names.section ?? ''} ${dayTours.map((t) => t.tour.name).join(' ')}`) ? LOWER_TIER.womenElo : LOWER_TIER.elo;
      const chosen = candidates
        .filter(({ game: g }) => {
          const elo = Math.max(Number(g.tags.WhiteElo) || 0, Number(g.tags.BlackElo) || 0);
          return top ? elo >= minElo : followed(g) || elo >= bar;
        })
        .map((c) => ({ ...c, score: score(c.game, lookup) }))
        .filter((c) => c.score > 0)
        .sort((a, b) => b.score - a.score)
        .slice(0, quota);
      log(`  ${title} · ${date}: ${dayRounds.length} round(s) in ${dayTours.length} broadcast(s), ${candidates.length} finished, ${chosen.length} chosen`);
      const entries = new Map(
        dayTours.map((t) => [t.tour.id, (state.rounds[keyOf(t)] ??= { tour: t.tour.id, name: `${t.tour.name} · ${date}`, games: [] })]),
      );
      const record = { entries: [...entries.values()], left: 0 };
      groups.push(record);
      const done = new Set(record.entries.flatMap((entry) => entry.games));
      chosen.forEach(({ game, round, t, score: weight }, rank) => {
        const gameId = game.tags.GameURL?.split('/').pop() ?? `${game.tags.White}-${game.tags.Black}`;
        if (done.has(gameId)) return;
        record.left++;
        jobs.push({ game, gameId, rank, weight, t, round, names, record, entry: entries.get(t.tour.id) });
      });
    }
  }
  log(`${jobs.length} games to analyse, ${workers} at a time, ${Math.round(budgetMs / 60_000)} minutes allowed`);

  const engines = jobs.length ? await createEngines(Math.min(workers, jobs.length)) : [];
  const written = [];
  const announced = [];
  let next = 0;

  // One save at a time, so two workers finishing together cannot each write
  // a state that has lost the other's story.
  let saving = Promise.resolve();
  const save = (drafted, job) => {
    saving = saving.then(async () => {
      let story = drafted;
      // The page first, so the feed never lists a story whose address is not
      // yet there. A page that fails is tried again at the end of the run, and
      // on every run after until it is made.
      if (story && site && store.visible(story)) {
        try {
          const url = await site.page(story);
          story = { ...story, url };
          announced.push(url);
        } catch (error) {
          log(`  ✗ page for ${story.id}: ${error.message}`);
        }
      }
      if (story) {
        (state.days[story.date] ??= []).push(story.id);
        taken.add(story.id);
        await store.write([story], state.days, { latest: false });
        written.push(story);
        // The latest file every so often rather than after every story: a run
        // cut short still leaves most of its stories listed.
        if (written.length % 10 === 0) await store.writeLatest(state.days);
      }
      job.entry.games.push(job.gameId);
      if (--job.record.left === 0) for (const entry of job.record.entries) entry.done = true;
      await store.saveState(state);
    });
    return saving;
  };

  await Promise.all(engines.map(async (engine) => {
    while (next < jobs.length) {
      // Never start a game the time left cannot finish: the next run picks
      // it up, and a Lambda killed mid-write is the one way to lose work.
      if (Date.now() - started > budgetMs) return;
      const job = jobs[next++];
      let story = null;
      try {
        story = await storyFor(job, { engine, lookup, depth, taken });
        log(`  ✓ ${story.id}${story.key ? ` · ${story.key.kind} at ply ${story.key.ply}` : ''}`);
      } catch (error) {
        log(`  ✗ ${job.game.tags.White} – ${job.game.tags.Black}: ${error.message}`);
      }
      await save(story, job);
    }
  }));
  await saving;
  for (const engine of engines) await engine.close?.();

  // A day with nothing chosen from it is over all the same.
  for (const { entries, left } of groups) if (left === 0) for (const entry of entries) entry.done = true;
  if (written.length) await store.writeLatest(state.days);
  await store.saveState(state);
  if (site) {
    // Every run, not only one that wrote something: it retries a page that
    // failed, and rewrites the lists a deploy may have raced.
    try {
      announced.push(...(await publishPages({ store, site, days: state.days, log })));
      await site.announce(announced);
    } catch (error) {
      log(`  ✗ the site's lists: ${error.message}`);
    }
  }
  log(`${written.length} stories written in ${Math.round((Date.now() - started) / 1000)} s`);
  return written;
}

async function storyFor({ game, gameId, rank, weight, t, round, names }, { engine, lookup, depth, taken }) {
  const tags = game.tags;
  // A relay occasionally carries an illegal move, and a story built on one
  // would be a replay that stops halfway. Such a game is dropped, not fixed.
  const probe = new Chess();
  for (const san of game.sans) probe.move(san);

  const white = person(tags, 'White', lookup);
  const black = person(tags, 'Black', lookup);
  const turn = await turningPoint(engine, game.sans, tags.Result, depth);
  const roundNumber = Number(/\d+/.exec(round.name)?.[0]) || null;
  // The round's date, not the game's tags: a relay's Date tag is whatever the
  // board operator typed in, and one Olympiad round said the fourth of the month.
  const date = new Date(round.startsAt).toISOString().slice(0, 10);
  // Four words of the event at most: "international-chess-tournament-by-green-hills-resort-masters"
  // is an address nobody types and a search engine truncates.
  const event = slugify(names.short).split('-').slice(0, 4).join('-');
  let id = `${date}-${slugify(white.short)}-${slugify(black.short)}-${event}${roundNumber ? `-r${roundNumber}` : ''}`;
  if (taken.has(id)) id = `${id}-${slugify(gameId)}`;

  const story = {
    id,
    status: 'auto',
    date,
    rank,
    weight,
    event: {
      name: names.name,
      short: names.short,
      section: names.section,
      round: roundNumber,
      location: t.tour.info?.location ?? tags.Site ?? null,
      url: tags.BroadcastURL ?? t.tour.url ?? null,
    },
    white,
    black,
    result: tags.Result,
    opening: { eco: tags.ECO ?? null, name: tags.Opening ?? null },
    moves: game.sans.join(' '),
    plies: game.sans.length,
    ending: turn.ending,
    key: turn.key ? { ...turn.key, clock: game.clocks[turn.key.ply - 1] ?? null } : null,
    source: { name: 'Lichess broadcast', url: tags.GameURL ?? tags.BroadcastURL ?? null },
  };
  // Author a deeply analytical Premium Article via gpt-6.1-terra (high) across
  // all languages, with graceful fallback to deterministic article.mjs templates
  // if OpenAI is unreachable.
  try {
    const premiumOut = await generatePremiumStoryWords(story, {
      engine,
      clocks: game.clocks,
    });
    return stored({
      ...story,
      headline: premiumOut.headline,
      lede: premiumOut.lede,
      body: premiumOut.body,
      premium: premiumOut.premium,
      words: premiumOut.words,
    });
  } catch (error) {
    console.error(`  ! premium article fallback for ${story.id}: ${error.message}`);
    const { en, ...words } = articles(story);
    return stored({ ...story, headline: en.headline, lede: en.lede, body: en.body, words });
  }
}

function parseArgs(argv) {
  const args = { hours: 72, workers: 1, tour: null, pages: 1, budget: 3600, 'dry-run': false, site: false };
  for (let i = 0; i < argv.length; i++) {
    const key = argv[i].replace(/^--/, '');
    if (!(key in args)) throw new Error(`unknown option --${key}`);
    if (key === 'dry-run' || key === 'site') args[key] = true;
    else args[key] = key === 'tour' ? argv[++i] : Number(argv[++i]);
  }
  return args;
}

// Run as a script, not when the Lambda imports it.
if (process.argv[1] && fileURLToPath(import.meta.url) === resolve(process.argv[1])) {
  const args = parseArgs(process.argv.slice(2));
  collect({
    store: openStore({ dryRun: args['dry-run'] }),
    // Pages only when asked: they are rendered with the local build, which is
    // right only if it is the one deployed.
    site: args.site ? openPages({ dryRun: args['dry-run'] }) : null,
    hours: args.hours,
    workers: args.workers,
    only: args.tour,
    pages: args.pages,
    budgetMs: args.budget * 1000,
  })
    .then(() => process.exit(0))
    .catch((error) => {
      console.error(error);
      process.exit(1);
    });
}
