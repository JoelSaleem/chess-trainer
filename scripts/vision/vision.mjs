// Chessboard Vision via OpenAI Responses API (`gpt-6.1-terra`, `reasoning: { effort: "high" }`)
// with deterministic `chess.js` FEN normalization and legality verification.
import { Chess, validateFen } from 'chess.js';

export const VISION_MODEL = 'gpt-6.1-terra';

const VISION_SCHEMA = {
  type: 'object',
  additionalProperties: false,
  required: ['orientation', 'ranks', 'piecePlacement', 'turn', 'castling', 'confidence', 'notes'],
  properties: {
    orientation: {
      type: 'string',
      enum: ['white', 'black'],
      description: 'Which side of the chessboard is at the bottom of the image ("white" or "black").',
    },
    ranks: {
      type: 'array',
      items: { type: 'string' },
      description:
        'Exactly 8 strings of 8 characters each, ALWAYS in standard FEN order from rank 8 (a8..h8) down to rank 1 (a1..h1), using P,N,B,R,Q,K for White pieces, p,n,b,r,q,k for Black pieces, and . for empty squares.',
    },
    piecePlacement: {
      type: 'string',
      description: 'Standard FEN field 1 piece placement from rank 8 (a8..h8) down to rank 1 (a1..h1).',
    },
    turn: {
      type: 'string',
      enum: ['w', 'b'],
      description: 'Most likely side to move ("w" or "b") based on check, last-move highlights, clock, caption, or standard convention.',
    },
    castling: {
      type: 'string',
      description: 'Subset of KQkq consistent with king and rook positions, or "-" if none.',
    },
    confidence: {
      type: 'string',
      enum: ['high', 'medium', 'low'],
    },
    notes: {
      type: 'string',
      description: 'Brief 1-sentence factual note on board orientation, position cues, or any occluded squares.',
    },
  },
};

const SYSTEM_PROMPT = `You are a precision chess position recognizer. Inspect every single square (all 64 squares) of the chessboard in the image carefully before answering.

Rules:
1. First determine board orientation ("white" or "black" at the bottom of the image) using rank/file labels (a-h, 1-8), king/queen/pawn structure, or UI cues.
   - If White is at the bottom: top row left-to-right is a8..h8, bottom row left-to-right is a1..h1.
   - If Black is at the bottom: top row left-to-right is h1..a1, bottom row left-to-right is h8..a8. Convert carefully so your output is ALWAYS in standard FEN order (rank 8 a8..h8 first, down to rank 1 a1..h1 last).
2. Distinguish similar pieces carefully: King (cross/finial on crown) vs Queen (coronet points), Bishop (mitre/slit) vs Pawn (round head, smaller), Knight, Rook.
3. Verify each of the 8 ranks has exactly 8 squares, verify piece counts, and make sure \`ranks\` and \`piecePlacement\` match identically.
4. Set \`castling\` only for kings and rooks still on their original home squares (e1/h1/a1 for White K/Q, e8/h8/a8 for Black k/q), or "-".
5. Set \`turn\` ("w" or "b"): if one king is in check, that side MUST be to move; if last-move highlight squares show a White piece just moved, set "b" (and vice versa); otherwise infer from position/cues or default to "w".`;

function compressGridRank(row) {
  let out = '';
  let empty = 0;
  for (const ch of row) {
    if (ch === '.') {
      empty++;
    } else {
      if (empty > 0) {
        out += String(empty);
        empty = 0;
      }
      out += ch;
    }
  }
  if (empty > 0) out += String(empty);
  return out;
}

function expandPlacementToGrid(placement) {
  if (typeof placement !== 'string') return null;
  const parts = placement.trim().split('/');
  if (parts.length !== 8) return null;
  const rows = [];
  for (const part of parts) {
    let row = '';
    for (const ch of part) {
      if (ch >= '1' && ch <= '8') {
        row += '.'.repeat(Number(ch));
      } else if ('PNBRQKpnbrqk'.includes(ch)) {
        row += ch;
      } else {
        return null;
      }
    }
    if (row.length !== 8) return null;
    rows.push(row);
  }
  return rows;
}

function parseGridArray(ranks) {
  if (!Array.isArray(ranks) || ranks.length !== 8) return null;
  const rows = [];
  for (const r of ranks) {
    if (typeof r !== 'string') return null;
    const trimmed = r.trim();
    if (trimmed.length === 8 && /^[PNBRQKpnbrqk.]{8}$/.test(trimmed)) {
      rows.push(trimmed);
    } else {
      const expanded = expandPlacementToGrid(Array(8).fill(trimmed).join('/'));
      if (!expanded) return null;
      rows.push(expanded[0]);
    }
  }
  return rows;
}

/**
 * Normalizes raw vision output into a canonical 6-field FEN and validates chess legality.
 */
export function normalizeDetectedPosition(raw) {
  const fromPlacement = expandPlacementToGrid(raw?.piecePlacement);
  const fromGrid = parseGridArray(raw?.ranks);
  const grid = fromPlacement ?? fromGrid;
  if (!grid) {
    throw new Error('Could not parse a valid 8x8 chessboard position from image');
  }

  const piecePlacement = grid.map(compressGridRank).join('/');
  let turn = raw?.turn === 'b' ? 'b' : 'w';
  const orientation = raw?.orientation === 'black' ? 'black' : 'white';
  const confidence = ['high', 'medium', 'low'].includes(raw?.confidence) ? raw.confidence : 'medium';
  const notes = typeof raw?.notes === 'string' ? raw.notes.trim().slice(0, 500) : '';

  // Strip impossible castling flags against actual piece locations on rank 1 (grid[7]) & rank 8 (grid[0]).
  const rawCastling = typeof raw?.castling === 'string' ? raw.castling : '-';
  let castling = '';
  if (rawCastling.includes('K') && grid[7][4] === 'K' && grid[7][7] === 'R') castling += 'K';
  if (rawCastling.includes('Q') && grid[7][4] === 'K' && grid[7][0] === 'R') castling += 'Q';
  if (rawCastling.includes('k') && grid[0][4] === 'k' && grid[0][7] === 'r') castling += 'k';
  if (rawCastling.includes('q') && grid[0][4] === 'k' && grid[0][0] === 'r') castling += 'q';
  if (!castling) castling = '-';

  // Count kings & check illegal pawns on 1st/8th rank.
  let whiteKings = 0;
  let blackKings = 0;
  for (const row of grid) {
    for (const ch of row) {
      if (ch === 'K') whiteKings++;
      if (ch === 'k') blackKings++;
    }
  }
  const pawnsOnBackRanks = /[Pp]/.test(grid[0]) || /[Pp]/.test(grid[7]);

  let isLegal = whiteKings === 1 && blackKings === 1 && !pawnsOnBackRanks;
  let fen = `${piecePlacement} ${turn} ${castling} - 0 1`;

  if (isLegal) {
    const checkVal = validateFen(fen);
    if (!checkVal.ok) {
      isLegal = false;
    } else {
      try {
        const otherTurn = turn === 'w' ? 'b' : 'w';
        const asChosen = new Chess(`${piecePlacement} ${turn} ${castling} - 0 1`);
        const asOther = new Chess(`${piecePlacement} ${otherTurn} ${castling} - 0 1`);
        // If non-side-to-move is in check, either flip turn (if only that king is in check) or mark illegal.
        if (asOther.inCheck()) {
          if (!asChosen.inCheck()) {
            turn = otherTurn;
            fen = `${piecePlacement} ${turn} ${castling} - 0 1`;
          } else {
            isLegal = false;
          }
        }
      } catch {
        isLegal = false;
      }
    }
  }

  return {
    fen,
    piecePlacement,
    turn,
    castling,
    orientation,
    isLegal,
    confidence,
    notes,
  };
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

async function callOpenAIVision({ apiKey, dataUrl, maxOutputTokens }) {
  const response = await fetch('https://api.openai.com/v1/responses', {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${apiKey}`,
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({
      model: VISION_MODEL,
      reasoning: { effort: 'high' },
      max_output_tokens: maxOutputTokens,
      input: [
        {
          role: 'developer',
          content: [{ type: 'input_text', text: SYSTEM_PROMPT }],
        },
        {
          role: 'user',
          content: [
            {
              type: 'input_text',
              text: 'Identify the exact chess position on this board and return the structured JSON.',
            },
            {
              type: 'input_image',
              image_url: dataUrl,
              detail: 'high',
            },
          ],
        },
      ],
      text: {
        format: {
          type: 'json_schema',
          name: 'chess_board_scan',
          strict: true,
          schema: VISION_SCHEMA,
        },
      },
    }),
  });

  if (!response.ok) {
    const errBody = await response.text();
    throw new Error(`OpenAI Responses API HTTP ${response.status}: ${errBody.slice(0, 300)}`);
  }
  return response.json();
}

export async function recognizeChessboard({ apiKey, imageBase64, mimeType }) {
  const dataUrl = `data:${mimeType};base64,${imageBase64}`;
  let payload = await callOpenAIVision({ apiKey, dataUrl, maxOutputTokens: 2500 });
  let text = extractResponseText(payload);

  // If high reasoning consumed all 2,500 output tokens on an unusually dense photo, retry once with headroom.
  if (!text && payload?.status === 'incomplete') {
    payload = await callOpenAIVision({ apiKey, dataUrl, maxOutputTokens: 8192 });
    text = extractResponseText(payload);
  }
  if (!text) {
    throw new Error(`OpenAI returned no structured output (status=${payload?.status ?? 'unknown'})`);
  }

  let parsed;
  try {
    parsed = JSON.parse(text);
  } catch {
    throw new Error('OpenAI returned invalid JSON payload');
  }

  return normalizeDetectedPosition(parsed);
}
