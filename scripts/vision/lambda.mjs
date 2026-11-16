// Brass Pawn Chessboard Vision Lambda (`brasspawn-board-vision`, eu-central-1)
//
// Exposes a public HTTPS Lambda Function URL accepting POST JSON (<= 2 MB):
//   { imageBase64, mimeType, entitlementJWS?, appTransactionJWS?, clientInstallID? }
//
// Enforces S3-backed daily quotas (1,500 global/day, 100/day verified StoreKit 2 Pro,
// 1/day Free across both IP hash and client/appTransaction hash) before calling
// OpenAI `gpt-6.1-terra` (`reasoning: { effort: "high" }`) and normalizing the
// resulting FEN with `chess.js`.
import { openS3 } from '../feed/s3.mjs';
import { getSecureParameter } from '../feed/ssm.mjs';
import { freeClientHash, hashIP, verifyProEntitlement } from './jws.mjs';
import { consumeVisionQuota } from './quota.mjs';
import { recognizeChessboard } from './vision.mjs';

const REGION = process.env.VISION_REGION ?? process.env.AWS_REGION ?? 'eu-central-1';
const BUCKET = process.env.VISION_BUCKET ?? 'brasspawn-media';
const OPENAI_SSM_PARAM = process.env.OPENAI_SSM_PARAM ?? '/brasspawn/prod/openai_api_key';
const MAX_BODY_BYTES = 2 * 1024 * 1024;

const ALLOWED_MIMES = new Set(['image/jpeg', 'image/png', 'image/webp', 'image/gif']);

const s3 = openS3({ bucket: BUCKET, region: REGION });

const reply = (statusCode, body) => ({
  statusCode,
  headers: { 'content-type': 'application/json' },
  body: JSON.stringify(body),
});

function normalizeMime(rawMime) {
  if (typeof rawMime !== 'string' || !rawMime.trim()) return 'image/jpeg';
  const lower = rawMime.trim().toLowerCase();
  if (lower === 'image/jpg') return 'image/jpeg';
  return ALLOWED_MIMES.has(lower) ? lower : null;
}

function cleanBase64Image(input) {
  if (typeof input !== 'string') return null;
  let trimmed = input.trim();
  const dataPrefix = /^data:([a-zA-Z0-9.+/-]+);base64,(.*)$/s.exec(trimmed);
  let inferredMime = null;
  if (dataPrefix) {
    inferredMime = dataPrefix[1].toLowerCase();
    trimmed = dataPrefix[2].trim();
  }
  const compact = trimmed.replace(/\s+/g, '');
  if (!compact || compact.length > MAX_BODY_BYTES * 1.38) return null;
  if (!/^[A-Za-z0-9+/]+={0,2}$/.test(compact)) return null;
  const decoded = Buffer.from(compact, 'base64');
  if (decoded.byteLength < 64 || decoded.byteLength > MAX_BODY_BYTES) return null;
  return { base64: compact, inferredMime };
}

export const handler = async (event) => {
  const method = (event?.requestContext?.http?.method ?? event?.httpMethod ?? 'POST').toUpperCase();
  if (method === 'OPTIONS') {
    return { statusCode: 204, headers: {}, body: '' };
  }
  if (method !== 'POST') {
    return reply(405, { error: 'method_not_allowed' });
  }

  let body;
  try {
    const rawStr = event?.isBase64Encoded
      ? Buffer.from(event.body ?? '', 'base64').toString('utf8')
      : String(event?.body ?? '');
    if (Buffer.byteLength(rawStr, 'utf8') > MAX_BODY_BYTES) {
      return reply(413, { error: 'payload_too_large' });
    }
    body = JSON.parse(rawStr);
  } catch {
    return reply(400, { error: 'invalid_json' });
  }

  const image = cleanBase64Image(body?.imageBase64);
  if (!image) {
    return reply(400, { error: 'invalid_image' });
  }

  const mimeType = normalizeMime(body?.mimeType ?? image.inferredMime);
  if (!mimeType) {
    return reply(400, { error: 'unsupported_mime_type' });
  }

  const sourceIp = event?.requestContext?.http?.sourceIp ?? 'unknown';
  const pro = verifyProEntitlement(body?.entitlementJWS);
  const ipHash = hashIP(sourceIp);
  const clientHash = freeClientHash({
    appTransactionJWS: body?.appTransactionJWS,
    clientInstallID: body?.clientInstallID,
    ip: sourceIp,
  });

  let quotaTicket;
  try {
    quotaTicket = await consumeVisionQuota(s3, { pro, ipHash, clientHash });
  } catch (err) {
    console.error('Vision quota check error:', err.message);
    return reply(503, { error: 'quota_unavailable' });
  }

  if (!quotaTicket.allowed) {
    return reply(quotaTicket.statusCode, quotaTicket.body);
  }

  try {
    const apiKey = await getSecureParameter(OPENAI_SSM_PARAM, { region: REGION });
    const detected = await recognizeChessboard({
      apiKey,
      imageBase64: image.base64,
      mimeType,
    });

    return reply(200, {
      fen: detected.fen,
      piecePlacement: detected.piecePlacement,
      turn: detected.turn,
      castling: detected.castling,
      orientation: detected.orientation,
      isLegal: detected.isLegal,
      confidence: detected.confidence,
      notes: detected.notes,
      quota: quotaTicket.quota,
    });
  } catch (err) {
    console.error('Board vision inference failed:', err.message);
    try {
      await quotaTicket.refund?.();
    } catch {
      // Best-effort quota refund on upstream failure.
    }
    return reply(502, { error: 'vision_failed', detail: 'Could not recognize chessboard position.' });
  }
};
