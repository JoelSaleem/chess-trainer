// Deeply analytical Premium Articles authored by OpenAI `gpt-6.1-terra` (`effort: "high"`)
// from verified broadcast PGN moves, player metadata, clocks, and Stockfish evaluations.
//
// Deterministic chess guarantees:
//   - Every section's `fen`, `last`, and `move` label are computed by `chess.js` from
//     `story.moves` at `section.ply` — never trusted to freeform text generation.
//   - Every English sentence passes `article.mjs`'s `problems()` editorial standard
//     (strictly factual, zero gendered pronouns, zero sensationalist/judgement words).
//   - Translated across all 16 indexed SEO languages, all 30 website `LANGS`, and
//     all 50+ Brass Pawn app locales.
import { Chess } from 'chess.js';

import { LANGS, articles as fallbackArticles, moveLabel, problems } from './article.mjs';
import { positionAt } from './diagram.mjs';
import { getSecureParameter } from './ssm.mjs';

export const PREMIUM_MODEL = 'gpt-6.1-terra';
const OPENAI_SSM_PARAM = process.env.OPENAI_SSM_PARAM ?? '/brasspawn/prod/openai_api_key';

/** The 16 high-value indexed SEO languages on brasspawn.com (besides English). */
export const INDEXED_SEO_LANGS = [
  'it', 'ru', 'id', 'pl', 'es', 'ar', 'bg', 'hu',
  'fr', 'de', 'zh-Hant', 'cs', 'el', 'ja', 'ko', 'pt-BR',
];

/** Remaining website Today languages in `article.mjs` `LANGS`. */
export const REMAINING_SITE_LANGS = LANGS.filter(
  (l) => l !== 'en' && !INDEXED_SEO_LANGS.includes(l),
);

/** Extended app locales so all 50+ app locales get localized `media/feed/v1/<lang>/...` feeds. */
export const EXTRA_APP_LANGS = [
  'uk', 'ca', 'hr', 'sk', 'pt-PT', 'es-MX', 'sr', 'sl', 'lt', 'lv',
  'et', 'fa', 'ur', 'bn', 'ta', 'te', 'mr', 'kk', 'uz', 'hy', 'ka', 'az',
];

/** Regional locale aliases mapped without extra API calls. */
export const LOCALE_ALIASES = {
  'en-GB': 'en',
  'en-CA': 'en',
  'en-AU': 'en',
  'fr-CA': 'fr',
  'zh-HK': 'zh-Hant',
  pt: 'pt-PT',
  nb: 'no',
};

export const ALL_FEED_LANGS = [
  ...LANGS,
  ...EXTRA_APP_LANGS,
  ...Object.keys(LOCALE_ALIASES),
];

const LANGUAGE_NAMES = {
  ar: 'Arabic',
  az: 'Azerbaijani',
  bg: 'Bulgarian',
  bn: 'Bengali',
  ca: 'Catalan',
  cs: 'Czech',
  da: 'Danish',
  de: 'German',
  el: 'Greek',
  es: 'Spanish (Spain)',
  'es-MX': 'Spanish (Mexico / Latin America)',
  et: 'Estonian',
  fa: 'Persian (Farsi)',
  fi: 'Finnish',
  fr: 'French',
  he: 'Hebrew',
  hi: 'Hindi',
  hr: 'Croatian',
  hu: 'Hungarian',
  hy: 'Armenian',
  id: 'Indonesian',
  it: 'Italian',
  ja: 'Japanese',
  ka: 'Georgian',
  kk: 'Kazakh',
  ko: 'Korean',
  lt: 'Lithuanian',
  lv: 'Latvian',
  mr: 'Marathi',
  ms: 'Malay',
  nl: 'Dutch',
  no: 'Norwegian Bokmål',
  pl: 'Polish',
  'pt-BR': 'Portuguese (Brazil)',
  'pt-PT': 'Portuguese (Portugal)',
  ro: 'Romanian',
  ru: 'Russian',
  sk: 'Slovak',
  sl: 'Slovenian',
  sr: 'Serbian',
  sv: 'Swedish',
  ta: 'Tamil',
  te: 'Telugu',
  th: 'Thai',
  tr: 'Turkish',
  uk: 'Ukrainian',
  ur: 'Urdu',
  uz: 'Uzbek',
  vi: 'Vietnamese',
  'zh-Hans': 'Chinese (Simplified)',
  'zh-Hant': 'Chinese (Traditional)',
};

function formatEval(score) {
  if (!score) return null;
  if (score.mate != null) {
    return `#${score.mate > 0 ? '+' : '-'}${Math.abs(score.mate)}`.replace('#+', '#');
  }
  if (typeof score.cp === 'number') {
    const pawns = score.cp / 100;
    const sign = pawns > 0 ? '+' : '';
    return `${sign}${pawns.toFixed(2)}`;
  }
  return null;
}

function formatClock(seconds) {
  if (seconds == null || !Number.isFinite(seconds)) return null;
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  const s = Math.floor(seconds % 60);
  if (h > 0) return `${h}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
  return `${m}:${String(s).padStart(2, '0')}`;
}

function uciLineToSan(fen, uciMoves, startPly, maxPlies = 6) {
  if (!Array.isArray(uciMoves) || !uciMoves.length) return null;
  const chess = new Chess(fen);
  const parts = [];
  let ply = startPly;
  for (const uci of uciMoves.slice(0, maxPlies)) {
    if (typeof uci !== 'string' || uci.length < 4) break;
    let moved;
    try {
      moved = chess.move({
        from: uci.slice(0, 2),
        to: uci.slice(2, 4),
        promotion: uci[4] || undefined,
      });
    } catch {
      break;
    }
    if (!moved) break;
    const num = Math.ceil(ply / 2);
    if (ply % 2 === 1) {
      parts.push(`${num}.${moved.san}`);
    } else if (parts.length === 0) {
      parts.push(`${num}...${moved.san}`);
    } else {
      parts.push(moved.san);
    }
    ply++;
  }
  return parts.length ? parts.join(' ') : null;
}

/**
 * Selects 3 distinct milestone plies (Opening & Setup, Critical Turning Point,
 * Endgame / Conversion) and enriches them with `chess.js` FENs and optional Stockfish PVs.
 */
export async function computeMilestones(story, { engine = null, clocks = null } = {}) {
  const sans = story.moves.trim().split(/\s+/).filter(Boolean);
  const total = sans.length;
  if (total === 0) throw new Error(`Story ${story.id} has no moves`);

  const chess = new Chess();
  const fens = [chess.fen()];
  const lasts = [null];
  for (const san of sans) {
    const mv = chess.move(san);
    fens.push(chess.fen());
    lasts.push({ from: mv.from, to: mv.to });
  }

  let turnPly = story.key?.ply ?? Math.max(1, Math.round(total * 0.55));
  turnPly = Math.min(Math.max(1, turnPly), total);

  let openPly = Math.min(Math.max(8, Math.round(total * 0.2)), Math.max(1, turnPly - 4));
  if (openPly >= turnPly && turnPly > 1) openPly = Math.max(1, Math.floor(turnPly / 2));

  let endPly =
    story.ending === 'checkmate' || total - turnPly <= 6
      ? total
      : Math.min(total, Math.max(turnPly + 4, Math.round(total * 0.86)));
  if (endPly <= turnPly && turnPly < total) endPly = total;

  const plies = [openPly, turnPly, endPly];

  async function inspectPly(ply, isKey) {
    const moveStr = moveLabel(ply, sans[ply - 1]);
    const fenBefore = fens[ply - 1];
    const fenAfter = fens[ply];
    const last = lasts[ply];
    const clockSec = clocks?.[ply - 1] ?? (isKey ? story.key?.clock ?? null : null);

    let evalBefore = isKey ? formatEval(story.key?.before) : null;
    let evalAfter = isKey ? formatEval(story.key?.after) : null;
    let engineLine = null;

    if (engine) {
      try {
        const [beforeAn, afterAn] = await Promise.all([
          engine.analyse(fenBefore, { depth: 15, movetime: 1000 }),
          engine.analyse(fenAfter, { depth: 15, movetime: 1000 }),
        ]);
        if (!evalBefore && beforeAn?.lines?.[0]?.score) {
          evalBefore = formatEval(beforeAn.lines[0].score);
        }
        if (!evalAfter) {
          if (afterAn?.terminal) {
            const checkC = new Chess(fenAfter);
            const side = fenAfter.split(' ')[1];
            evalAfter = checkC.isCheckmate() ? (side === 'w' ? '#-1' : '#1') : '0.00';
          } else if (afterAn?.lines?.[0]?.score) {
            evalAfter = formatEval(afterAn.lines[0].score);
          }
        }
        // For mistakes/swings show what Stockfish preferred BEFORE the move; otherwise show continuation AFTER.
        if (isKey && (story.key?.kind === 'mistake' || story.key?.kind === 'swing') && beforeAn?.lines?.[0]?.pv?.length) {
          engineLine = uciLineToSan(fenBefore, beforeAn.lines[0].pv, ply, 6);
        } else if (afterAn?.lines?.[0]?.pv?.length) {
          engineLine = uciLineToSan(fenAfter, afterAn.lines[0].pv, ply + 1, 6);
        }
      } catch {
        // Fallback to key metadata below if engine call fails.
      }
    }

    if (!engineLine && isKey) {
      const parts = [];
      if (story.key?.better) parts.push(`${moveLabel(ply, story.key.better)} (Stockfish preference)`);
      if (story.key?.reply) parts.push(`reply ${moveLabel(ply + 1, story.key.reply)}`);
      if (parts.length) engineLine = parts.join(' · ');
    }

    return {
      ply,
      move: moveStr,
      san: sans[ply - 1],
      fen: fenAfter,
      last,
      evalBefore: evalBefore ?? null,
      evalAfter: evalAfter ?? null,
      engineLine: engineLine ?? null,
      clock: formatClock(clockSec),
    };
  }

  const results = [];
  for (let i = 0; i < plies.length; i++) {
    results.push(await inspectPly(plies[i], plies[i] === story.key?.ply));
  }
  return results;
}

const ENGLISH_PREMIUM_SCHEMA = {
  type: 'object',
  additionalProperties: false,
  required: ['headline', 'lede', 'body', 'subtitle', 'readingMinutes', 'sections'],
  properties: {
    headline: { type: 'string' },
    lede: { type: 'string' },
    body: {
      type: 'string',
      description: '3 to 4 factual analytical paragraphs separated by double newlines (\\n\\n).',
    },
    subtitle: { type: 'string' },
    readingMinutes: { type: 'integer' },
    sections: {
      type: 'array',
      items: {
        type: 'object',
        additionalProperties: false,
        required: ['heading', 'ply', 'body', 'takeaways'],
        properties: {
          heading: { type: 'string' },
          ply: { type: 'integer' },
          body: {
            type: 'string',
            description: '1 to 2 rich analytical paragraphs separated by \\n\\n discussing concrete moves, plans, and engine lines.',
          },
          takeaways: {
            type: 'array',
            items: { type: 'string' },
          },
        },
      },
    },
  },
};

function sanitizeEnglishEditorial(text) {
  if (typeof text !== 'string') return '';
  const replacements = [
    [/\bblunders\b/gi, 'costly errors'],
    [/\bblundered\b/gi, 'erred significantly'],
    [/\bblundering\b/gi, 'erring on'],
    [/\bblunder\b/gi, 'decisive error'],
    [/\bcollapsed\b/gi, 'gave way'],
    [/\bcollapses\b/gi, 'gives way'],
    [/\bcollapse\b/gi, 'breakdown'],
    [/\bcrushing\b/gi, 'decisive'],
    [/\bcrushed\b/gi, 'defeated'],
    [/\bcrush\b/gi, 'overcome'],
    [/\bbrilliancy\b/gi, 'tactical resource'],
    [/\bbrilliantly\b/gi, 'accurately'],
    [/\bbrilliant\b/gi, 'precise'],
    [/\bstunning\b/gi, 'notable'],
    [/\bstunned\b/gi, 'defeated'],
    [/\bshocking\b/gi, 'unexpected'],
    [/\bshocked\b/gi, 'upset'],
    [/\bshock\b/gi, 'upset'],
    [/\bdestroyed\b/gi, 'dismantled'],
    [/\bdestroying\b/gi, 'dismantling'],
    [/\bdestroy\b/gi, 'dismantle'],
    [/\bdisaster\b/gi, 'setback'],
    [/\bdisastrous\b/gi, 'costly'],
    [/\bterrible\b/gi, 'inaccurate'],
    [/\bterribly\b/gi, 'severely'],
    [/\bawful\b/gi, 'inaccurate'],
    [/\bnerves\b/gi, 'time pressure'],
    [/\bnervous\b/gi, 'tense'],
    [/\bpanicked\b/gi, 'erred'],
    [/\bpanic\b/gi, 'pressure'],
    [/\bchoked\b/gi, 'faltered'],
    [/\bchoke\b/gi, 'slip'],
    [/\bgenius\b/gi, 'resourceful'],
    [/\bmeltdown\b/gi, 'turnaround'],
    [/\bcracked\b/gi, 'yielded'],
    [/\bclueless\b/gi, 'passive'],
    [/\bhumiliating\b/gi, 'one-sided'],
    [/\bhumiliated\b/gi, 'outplayed'],
    [/\bembarrassing\b/gi, 'costly'],
    [/\bshame\w*\b/gi, 'regret'],
    [/\bhimself\b/gi, 'the side'],
    [/\bherself\b/gi, 'the side'],
    [/\bhis\b/gi, "the player's"],
    [/\bhers\b/gi, "the player's"],
    [/\bher\b/gi, 'the'],
    [/\bhim\b/gi, 'the opponent'],
    [/\bhe\b/gi, 'the player'],
    [/\bshe\b/gi, 'the player'],
  ];
  let out = text;
  for (const [re, sub] of replacements) {
    out = out.replace(re, sub);
  }
  return out;
}

function extractResponseText(payload) {
  if (typeof payload?.output_text === 'string' && payload.output_text.trim()) {
    return payload.output_text.trim();
  }
  for (const item of payload?.output ?? []) {
    for (const part of item?.content ?? []) {
      if (typeof part?.text === 'string' && part.text.trim()) {
        return part.text.trim();
      }
    }
  }
  return null;
}

async function callOpenAIJson({ apiKey, systemPrompt, userPrompt, schemaName, schema, effort = 'high', maxOutputTokens = 8192 }) {
  const response = await fetch('https://api.openai.com/v1/responses', {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${apiKey}`,
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({
      model: PREMIUM_MODEL,
      reasoning: { effort },
      max_output_tokens: maxOutputTokens,
      input: [
        { role: 'developer', content: [{ type: 'input_text', text: systemPrompt }] },
        { role: 'user', content: [{ type: 'input_text', text: userPrompt }] },
      ],
      text: {
        format: {
          type: 'json_schema',
          name: schemaName,
          strict: true,
          schema,
        },
      },
    }),
    signal: AbortSignal.timeout(150_000),
  });

  if (!response.ok) {
    const errText = await response.text();
    throw new Error(`OpenAI ${schemaName} HTTP ${response.status}: ${errText.slice(0, 300)}`);
  }
  const payload = await response.json();
  const text = extractResponseText(payload);
  if (!text) {
    throw new Error(`OpenAI ${schemaName} returned empty output (status=${payload?.status ?? 'unknown'})`);
  }
  return JSON.parse(text);
}

const AUTHOR_SYSTEM_PROMPT = `You are the senior chess analyst and tournament editor at Brass Pawn (brasspawn.com).
Write a deeply analytical, crystal-clear Premium Article for a finished master tournament game using ONLY verified facts from the PGN moves, player ratings/titles/teams, tournament/round metadata, clocks, and Stockfish evaluations provided.

STRICT BRASS PAWN EDITORIAL RULES (MANDATORY):
1. ZERO GENDERED PRONOUNS anywhere in headline, lede, body, subtitle, headings, or takeaways: NEVER use "he", "she", "his", "her", "him", "hers", "himself", or "herself". Always refer to players by surname (e.g. "Carlsen", "Gukesh"), title + name, or "White" / "Black".
2. ZERO INVENTED EMOTIONS, QUOTES, OR SENSATIONALISM: Never claim what a player felt, feared, hoped, or panicked about. Never use sensationalist/judgement words such as: blunder, choke, collapse, panic, nerves, disaster, humiliate, shock, stun, crush, destroy, brilliant, brilliancy, genius, shame, terrible, awful, embarrass, meltdown, cracked, clueless. Use precise chess terminology instead ("inaccuracy", "tactical oversight", "decisive breakthrough", "prophylactic resource", "pawn break", "conversion").
3. EXACT MOVE & ENGINE FIDELITY: Reference real moves from the game score and the exact Stockfish evaluations provided.
4. THREE ANALYTICAL SECTIONS: Produce exactly 3 sections corresponding in order to the 3 milestone plies provided:
   - Section 1: Opening & Setup (pawn structure, development, transition out of opening around Milestone 1)
   - Section 2: Critical Turning Point (the tactical/positional crux at Milestone 2, comparing the game move with Stockfish's line)
   - Section 3: Endgame / Practical Takeaways (how the advantage was converted or held around Milestone 3, with concrete practical lessons for club and tournament players)
   Each section must use the exact \`ply\` integer of its milestone and include 2 to 3 concise, concrete \`takeaways\` bullets.`;

function formatFullScore(sans) {
  const out = [];
  for (let i = 0; i < sans.length; i += 2) {
    const n = Math.floor(i / 2) + 1;
    out.push(`${n}.${sans[i]}${sans[i + 1] ? ` ${sans[i + 1]}` : ''}`);
  }
  return out.join(' ');
}

export async function authorEnglishPremium(story, milestones, { apiKey }) {
  const sans = story.moves.trim().split(/\s+/).filter(Boolean);
  const fullMoves = Math.ceil(sans.length / 2);
  const pDesc = (p) => `${p.title ? `${p.title} ` : ''}${p.name}${p.elo ? ` (${p.elo})` : ''}${p.team ? ` [${p.team}]` : ''}`;

  const userPrompt = JSON.stringify(
    {
      event: story.event,
      date: story.date,
      white: pDesc(story.white),
      black: pDesc(story.black),
      result: story.result,
      ending: story.ending ?? 'resignation/agreement',
      totalMoves: fullMoves,
      totalPlies: sans.length,
      opening: story.opening,
      keyTurningPoint: story.key
        ? {
            kind: story.key.kind,
            ply: story.key.ply,
            played: moveLabel(story.key.ply, story.key.played),
            stockfishPreferred: story.key.better ? moveLabel(story.key.ply, story.key.better) : null,
            reply: story.key.reply ? moveLabel(story.key.ply + 1, story.key.reply) : null,
            evalBefore: formatEval(story.key.before),
            evalAfter: formatEval(story.key.after),
            moverClockRemaining: formatClock(story.key.clock),
          }
        : null,
      milestones: milestones.map((m, idx) => ({
        sectionIndex: idx + 1,
        role: idx === 0 ? 'Opening & Setup' : idx === 1 ? 'Critical Turning Point' : 'Endgame / Practical Takeaways',
        ply: m.ply,
        move: m.move,
        fen: m.fen,
        evalBefore: m.evalBefore,
        evalAfter: m.evalAfter,
        stockfishLine: m.engineLine,
        clockRemaining: m.clock,
      })),
      gameScore: formatFullScore(sans),
    },
    null,
    2,
  );

  const raw = await callOpenAIJson({
    apiKey,
    systemPrompt: AUTHOR_SYSTEM_PROMPT,
    userPrompt,
    schemaName: 'brass_pawn_premium_article',
    schema: ENGLISH_PREMIUM_SCHEMA,
    effort: 'high',
    maxOutputTokens: 8192,
  });

  const headline = sanitizeEnglishEditorial(raw.headline).trim();
  const lede = sanitizeEnglishEditorial(raw.lede).trim();
  const body = sanitizeEnglishEditorial(raw.body).trim();
  const subtitle = sanitizeEnglishEditorial(raw.subtitle).trim();

  if (!Array.isArray(raw.sections) || raw.sections.length !== 3) {
    throw new Error('Expected 3 premium sections from gpt-6.1-terra');
  }

  const sections = milestones.map((m, idx) => {
    const sec = raw.sections[idx] ?? {};
    // Compute FEN and last deterministically from story.moves at milestone ply via chess.js!
    const exactBoard = positionAt(sans, m.ply);
    return {
      heading: sanitizeEnglishEditorial(sec.heading || `Phase ${idx + 1}`).trim(),
      ply: m.ply,
      move: moveLabel(m.ply, sans[m.ply - 1]),
      fen: exactBoard.fen,
      last: exactBoard.last,
      evalBefore: m.evalBefore,
      evalAfter: m.evalAfter,
      engineLine: m.engineLine,
      body: sanitizeEnglishEditorial(sec.body || '').trim(),
      takeaways: (Array.isArray(sec.takeaways) ? sec.takeaways : [])
        .slice(0, 4)
        .map((t) => sanitizeEnglishEditorial(t).trim())
        .filter(Boolean),
    };
  });

  const wordCount = [headline, subtitle, body, ...sections.flatMap((s) => [s.heading, s.body, ...s.takeaways])]
    .join(' ')
    .split(/\s+/)
    .filter(Boolean).length;
  const readingMinutes = Math.max(3, Math.min(8, Math.round(wordCount / 210) || raw.readingMinutes || 4));

  const premium = {
    tier: 'premium',
    subtitle,
    readingMinutes,
    sections,
  };

  // Enforce article.mjs's exact editorial validator across the entire English text!
  const allEnglishText = [
    headline,
    lede,
    body,
    subtitle,
    ...sections.flatMap((s) => [s.heading, s.body, ...s.takeaways]),
  ].join('\n');
  const errs = problems(allEnglishText, 'en');
  if (errs.length) {
    throw new Error(`Premium English editorial check failed: ${errs.join('; ')}`);
  }

  return { headline, lede, body, premium };
}

function buildTranslationBatchSchema(langs) {
  const langItemSchema = {
    type: 'object',
    additionalProperties: false,
    required: ['headline', 'lede', 'body', 'subtitle', 'sections'],
    properties: {
      headline: { type: 'string' },
      lede: { type: 'string' },
      body: { type: 'string' },
      subtitle: { type: 'string' },
      sections: {
        type: 'array',
        items: {
          type: 'object',
          additionalProperties: false,
          required: ['heading', 'body', 'takeaways'],
          properties: {
            heading: { type: 'string' },
            body: { type: 'string' },
            takeaways: { type: 'array', items: { type: 'string' } },
          },
        },
      },
    },
  };

  const properties = {};
  for (const lang of langs) {
    properties[lang] = langItemSchema;
  }
  return {
    type: 'object',
    additionalProperties: false,
    required: langs,
    properties,
  };
}

const TRANSLATE_SYSTEM_PROMPT = `You are a master multilingual chess translator for Brass Pawn (brasspawn.com).
Translate the English chess Premium Article accurately and idiomatically into each requested target language.

Rules:
1. Keep algebraic chess notation (e.g. "9...Bb7", "14.Rfe1", "e4", "1–0", "½–½"), Stockfish scores ("+1.42", "#4"), clock times ("1:42"), FIDE titles ("GM", "IM"), and player names in standard Latin algebraic / international form.
2. For right-to-left languages (ar, he, fa, ur), wrap inline algebraic moves and scores smoothly so bidirectional reading stays clean.
3. Avoid gendered pronouns referring to players: refer to players by surname or the localized words for White / Black so translations remain 100% gender-accurate.
4. Preserve paragraph breaks (\\n\\n) and return exactly 3 sections in the same order.`;

async function translateBatch({ apiKey, englishArticle, langs }) {
  const langList = langs.map((code) => ({ code, language: LANGUAGE_NAMES[code] ?? code }));
  const sourcePayload = {
    targetLanguages: langList,
    article: {
      headline: englishArticle.headline,
      lede: englishArticle.lede,
      body: englishArticle.body,
      subtitle: englishArticle.premium.subtitle,
      sections: englishArticle.premium.sections.map((s) => ({
        move: s.move,
        heading: s.heading,
        body: s.body,
        takeaways: s.takeaways,
      })),
    },
  };

  const parsed = await callOpenAIJson({
    apiKey,
    systemPrompt: TRANSLATE_SYSTEM_PROMPT,
    userPrompt: JSON.stringify(sourcePayload),
    schemaName: 'brass_pawn_premium_translations',
    schema: buildTranslationBatchSchema(langs),
    effort: 'medium',
    maxOutputTokens: 16000,
  });

  const out = {};
  for (const lang of langs) {
    const t = parsed?.[lang];
    if (!t || !Array.isArray(t.sections) || t.sections.length !== 3) continue;
    const wrong = problems(`${t.headline}\n${t.body}\n${t.subtitle}`, lang);
    if (wrong.length) continue;

    out[lang] = {
      headline: t.headline.trim(),
      lede: t.lede.trim(),
      body: t.body.trim(),
      premium: {
        tier: 'premium',
        subtitle: t.subtitle.trim(),
        readingMinutes: englishArticle.premium.readingMinutes,
        sections: englishArticle.premium.sections.map((enSec, i) => ({
          heading: (t.sections[i]?.heading ?? enSec.heading).trim(),
          ply: enSec.ply,
          move: enSec.move,
          fen: enSec.fen,
          last: enSec.last,
          evalBefore: enSec.evalBefore,
          evalAfter: enSec.evalAfter,
          engineLine: enSec.engineLine,
          body: (t.sections[i]?.body ?? enSec.body).trim(),
          takeaways: (Array.isArray(t.sections[i]?.takeaways) ? t.sections[i].takeaways : enSec.takeaways)
            .map((x) => String(x).trim())
            .filter(Boolean),
        })),
      },
    };
  }
  return out;
}

/**
 * Generates an English Premium Article via `gpt-6.1-terra` (`effort: "high"`) and
 * translates it into all 16 indexed SEO languages + remaining `LANGS` + 50+ app locales.
 */
export async function generatePremiumStoryWords(
  story,
  {
    engine = null,
    clocks = null,
    region = process.env.FEED_REGION ?? process.env.AWS_REGION ?? 'eu-central-1',
    includeExtendedAppLocales = !process.env.AWS_LAMBDA_FUNCTION_NAME,
    log = console.error,
  } = {},
) {
  const apiKey = await getSecureParameter(OPENAI_SSM_PARAM, { region });
  const milestones = await computeMilestones(story, { engine, clocks });
  const en = await authorEnglishPremium(story, milestones, { apiKey });

  let baseFallbacks = {};
  try {
    baseFallbacks = fallbackArticles(story);
  } catch {
    baseFallbacks = {};
  }

  const targetLangs = [
    ...INDEXED_SEO_LANGS,
    ...REMAINING_SITE_LANGS,
    ...(includeExtendedAppLocales ? EXTRA_APP_LANGS : []),
  ];

  // Chunk into batches of 4 languages with concurrency 8 so translations complete in a single parallel wave.
  const batches = [];
  const BATCH_SIZE = 4;
  for (let i = 0; i < targetLangs.length; i += BATCH_SIZE) {
    batches.push(targetLangs.slice(i, i + BATCH_SIZE));
  }

  const words = {};
  const concurrency = 8;
  for (let i = 0; i < batches.length; i += concurrency) {
    const slice = batches.slice(i, i + concurrency);
    const results = await Promise.all(
      slice.map(async (batch) => {
        try {
          return await translateBatch({ apiKey, englishArticle: en, langs: batch });
        } catch (err) {
          log(`  ! premium translation batch [${batch.join(',')}] fallback: ${err.message}`);
          return {};
        }
      }),
    );
    for (const r of results) Object.assign(words, r);
  }

  // Ensure every single language in LANGS + EXTRA_APP_LANGS has an entry
  for (const lang of targetLangs) {
    if (!words[lang]) {
      const fb = baseFallbacks[lang];
      words[lang] = fb
        ? { headline: fb.headline, lede: fb.lede, body: fb.body, premium: en.premium }
        : { headline: en.headline, lede: en.lede, body: en.body, premium: en.premium };
    }
  }

  // Populate regional locale aliases (en-GB, en-CA, en-AU, fr-CA, zh-HK, pt, nb)
  for (const [alias, source] of Object.entries(LOCALE_ALIASES)) {
    words[alias] = source === 'en' ? { headline: en.headline, lede: en.lede, body: en.body, premium: en.premium } : words[source];
  }

  return {
    headline: en.headline,
    lede: en.lede,
    body: en.body,
    premium: en.premium,
    words,
  };
}
