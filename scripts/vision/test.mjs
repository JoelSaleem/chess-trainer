import assert from 'node:assert/strict';
import { createHash, createSign, generateKeyPairSync } from 'node:crypto';
import test from 'node:test';

import { BUNDLE_ID, verifyProEntitlement } from './jws.mjs';
import { consumeVisionQuota } from './quota.mjs';
import { normalizeDetectedPosition } from './vision.mjs';

test('normalizeDetectedPosition strips impossible castling and fixes check turn', () => {
  const res = normalizeDetectedPosition({
    orientation: 'white',
    ranks: [
      'r...k..r',
      'ppp..ppp',
      '..n.....',
      '...qp...',
      '........',
      '.....N..',
      'PPPP.PPP',
      'RNBQ.RK.',
    ],
    piecePlacement: 'r3k2r/ppp2ppp/2n5/3qp3/8/5N2/PPPP1PPP/RNBQ1RK1',
    turn: 'b',
    castling: 'KQkq',
    confidence: 'high',
    notes: 'White castled kingside; Black still has both rooks and king on home squares.',
  });
  assert.equal(res.piecePlacement, 'r3k2r/ppp2ppp/2n5/3qp3/8/5N2/PPPP1PPP/RNBQ1RK1');
  // White king is on g1 (not e1), so K and Q must be stripped, leaving kq!
  assert.equal(res.castling, 'kq');
  assert.equal(res.isLegal, true);

  // Non-side-to-move in check -> flips turn to side in check
  const checkFixed = normalizeDetectedPosition({
    orientation: 'black',
    ranks: [
      '....k...',
      '........',
      '........',
      '........',
      '........',
      '........',
      '....Q...',
      '....K...',
    ],
    piecePlacement: '4k3/8/8/8/8/8/4Q3/4K3',
    turn: 'w',
    castling: '-',
    confidence: 'high',
    notes: 'Black king in check from e2 queen.',
  });
  assert.equal(checkFixed.turn, 'b');
  assert.equal(checkFixed.isLegal, true);
  assert.equal(checkFixed.orientation, 'black');
});

test('verifyProEntitlement rejects unverified or forged JWS against Apple Root CA - G3', () => {
  const { privateKey } = generateKeyPairSync('ec', { namedCurve: 'P-256' });
  const header = Buffer.from(JSON.stringify({ alg: 'ES256', x5c: ['AAAA', 'BBBB'] })).toString('base64url');
  const payload = Buffer.from(
    JSON.stringify({
      bundleId: BUNDLE_ID,
      productId: 'com.artesoft.brasspawn.pro.yearly',
      originalTransactionId: '100000012345',
      expiresDate: Date.now() + 86_400_000 * 30,
    }),
  ).toString('base64url');
  const sign = createSign('SHA256');
  sign.update(`${header}.${payload}`);
  const sig = sign.sign({ key: privateKey, dsaEncoding: 'ieee-p1363' }).toString('base64url');

  const check = verifyProEntitlement(`${header}.${payload}.${sig}`);
  assert.equal(check.isPro, false);
});

test('consumeVisionQuota enforces Free 1/day, Pro 100/day, and Global 1500/day', async () => {
  const mem = new Map();
  const mockS3 = {
    async get(k) {
      return mem.get(k) ?? null;
    },
    async put(k, v) {
      mem.set(k, v);
    },
  };
  const now = Date.UTC(2026, 10, 16, 12, 0, 0);
  const ipHash = createHash('sha256').update('1.2.3.4').digest('hex');
  const clientHash = createHash('sha256').update('install-abc').digest('hex');

  const firstFree = await consumeVisionQuota(mockS3, { pro: { isPro: false }, ipHash, clientHash, now });
  assert.equal(firstFree.allowed, true);
  assert.deepEqual(firstFree.quota, { used: 1, limit: 1, remaining: 0, isPro: false });

  // Second scan from same IP with different clientInstallID is still blocked!
  const secondDiffClient = await consumeVisionQuota(mockS3, {
    pro: { isPro: false },
    ipHash,
    clientHash: createHash('sha256').update('install-xyz').digest('hex'),
    now,
  });
  assert.equal(secondDiffClient.allowed, false);
  assert.deepEqual(secondDiffClient.body, { error: 'free_limit_reached', limit: 1, used: 1, isPro: false });

  // Pro user gets 100/day even from same IP!
  const pro = { isPro: true, originalTransactionId: '2000000999', productId: 'com.artesoft.brasspawn.pro.lifetime' };
  const firstPro = await consumeVisionQuota(mockS3, { pro, ipHash, clientHash, now });
  assert.equal(firstPro.allowed, true);
  assert.deepEqual(firstPro.quota, { used: 1, limit: 100, remaining: 99, isPro: true });
});
