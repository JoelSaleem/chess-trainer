#!/usr/bin/env node
// Collects any newly finished Lichess broadcast games and upgrades the newest
// tournament stories in `s3://brasspawn-media/media/feed/v1/` to rich
// `gpt-6.1-terra` (`effort: "high"`) Premium Articles across all 50+ locales.
//
// Usage:
//   node scripts/feed/upgrade-latest.mjs --count 4           # collect + upgrade top N stories in S3 feed
//   node scripts/feed/upgrade-latest.mjs --pages --count 4   # render HTML pages + translations + sitemap + announce
import { readdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { collect } from './collect.mjs';
import { createEngines } from './engine-pool.mjs';
import { pageURL } from './page-story.mjs';
import { openPages, publishPages } from './pages.mjs';
import { generatePremiumStoryWords } from './premium.mjs';
import { openStore, stored } from './store.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));

function keyFromSite() {
  const files = readdirSync(resolve(HERE, '../../web/public')).filter((name) => /^[a-f0-9-]{8,128}\.txt$/i.test(name));
  return files.length === 1 ? files[0].slice(0, -4) : null;
}

const argv = process.argv.slice(2);
const pagesMode = argv.includes('--pages');
const force = argv.includes('--force');
const countIdx = argv.indexOf('--count');
const count = countIdx >= 0 ? Number(argv[countIdx + 1]) || 4 : 4;

const store = openStore();

if (!pagesMode) {
  console.error('▸ Step 1: Checking Lichess broadcasts for newly finished rounds...');
  const newlyCollected = await collect({ store, hours: 72, workers: 2, site: null });
  console.error(`  Newly collected stories: ${newlyCollected.length}`);

  const state = await store.state();
  const publicStories = await store.publicStories(state.days);
  const targets = publicStories.slice(0, count);
  const toUpgrade = targets.filter((s) => force || !s.premium);

  console.error(`▸ Step 2: Ensuring newest ${targets.length} stories have Premium Articles (${toUpgrade.length} need upgrade)...`);
  if (toUpgrade.length > 0) {
    const [engine] = await createEngines(1);
    const upgraded = [];
    try {
      for (const summary of toUpgrade) {
        const full = (await store.story(summary.id)) ?? summary;
        console.error(`  ▸ Authoring & translating Premium Article for ${full.id}...`);
        const t0 = Date.now();
        const prem = await generatePremiumStoryWords(full, {
          engine,
          includeExtendedAppLocales: true,
        });
        const next = stored({
          ...full,
          headline: prem.headline,
          lede: prem.lede,
          body: prem.body,
          premium: prem.premium,
          words: prem.words,
          url: pageURL(full.id),
        });
        upgraded.push(next);
        console.error(`    ✓ ${full.id} upgraded in ${Math.round((Date.now() - t0) / 1000)}s (${Object.keys(prem.words).length + 1} locales)`);
      }
    } finally {
      await engine.close?.();
    }

    await store.write(upgraded, state.days, { latest: true });
    console.error(`✓ Wrote ${upgraded.length} upgraded Premium Articles + localized feeds + latest.json to S3`);
  } else {
    // Still refresh latest.json so all 50+ locale latest.json files exist!
    await store.writeLatest(state.days);
    console.error('✓ Top stories already have Premium Articles; refreshed latest.json across all locales');
  }
} else {
  const site = openPages({ indexNowKey: process.env.INDEXNOW_KEY ?? keyFromSite() });
  const state = await store.state();
  const publicStories = await store.publicStories(state.days);
  const targets = publicStories.filter((s) => s.premium).slice(0, Math.max(count, 5));
  const pagesState = await store.pages();
  const build = await site.build();

  const announcedUrls = [];
  console.error(`▸ Rendering prerendered English + 29 localized HTML pages for ${targets.length} Premium stories (build ${build})...`);
  for (const summary of targets) {
    const full = await store.story(summary.id);
    if (!full) continue;
    const enUrl = await site.page(full);
    const langUrls = await site.translations(full);
    pagesState.done[full.id] = build;
    announcedUrls.push(enUrl, ...langUrls);
    console.error(`  ✓ Rendered ${enUrl} + ${langUrls.length} translations`);
  }
  await store.savePages(pagesState);
  const moreWritten = await publishPages({ store, site, days: state.days, translated: pagesState.done });
  announcedUrls.push(...moreWritten);

  console.error('▸ Submitting updated URLs & sitemap-today.xml to IndexNow, Bing Webmaster API, and Google Search Console...');
  const statusReport = await site.announce(announcedUrls);
  console.log(JSON.stringify({ renderedStories: targets.map((s) => s.id), indexing: statusReport }, null, 2));
}

process.exit(0);
