// Strict server-side anti-drain quota stored in S3:
//   s3://brasspawn-media/vision-quota/v1/<yyyy-MM-dd>/...
//
// Enforces:
//   1. Global daily hard cap: 1,500 scans/UTC day across all callers -> 429 { error: "service_busy" }
//   2. Verified Pro/Lifetime (`pro:<originalTransactionId>`): 100 scans/UTC day -> 429 { error: "daily_limit_reached", limit: 100, used, isPro: true }
//   3. Free tier (BOTH sha256(ip) AND sha256(appTransactionJWS || clientInstallID)): 1 scan/UTC day -> 429 { error: "free_limit_reached", limit: 1, used, isPro: false }

export const GLOBAL_DAILY_LIMIT = 1_500;
export const PRO_DAILY_LIMIT = 100;
export const FREE_DAILY_LIMIT = 1;

export function utcDayString(now = Date.now()) {
  return new Date(now).toISOString().slice(0, 10);
}

async function readCount(s3, key) {
  const raw = await s3.get(key);
  if (!raw) return 0;
  try {
    const parsed = JSON.parse(raw);
    const n = Number(parsed?.used ?? 0);
    return Number.isFinite(n) && n >= 0 ? Math.floor(n) : 0;
  } catch {
    return 0;
  }
}

async function writeCount(s3, key, used, extra = {}) {
  await s3.put(
    key,
    JSON.stringify({ used, updatedAt: new Date().toISOString(), ...extra }),
    { contentType: 'application/json' },
  );
}

export async function consumeVisionQuota(s3, { pro, ipHash, clientHash, now = Date.now() }) {
  const day = utcDayString(now);
  const prefix = `vision-quota/v1/${day}`;
  const globalKey = `${prefix}/global.json`;

  const globalUsed = await readCount(s3, globalKey);
  if (globalUsed >= GLOBAL_DAILY_LIMIT) {
    return {
      allowed: false,
      statusCode: 429,
      body: { error: 'service_busy' },
    };
  }

  if (pro?.isPro) {
    const proKey = `${prefix}/pro:${pro.originalTransactionId}.json`;
    const used = await readCount(s3, proKey);
    if (used >= PRO_DAILY_LIMIT) {
      return {
        allowed: false,
        statusCode: 429,
        body: {
          error: 'daily_limit_reached',
          limit: PRO_DAILY_LIMIT,
          used,
          isPro: true,
        },
      };
    }

    const nextUsed = used + 1;
    const nextGlobal = globalUsed + 1;
    await Promise.all([
      writeCount(s3, proKey, nextUsed, { key: `pro:${pro.originalTransactionId}`, productId: pro.productId }),
      writeCount(s3, globalKey, nextGlobal),
    ]);

    return {
      allowed: true,
      quota: {
        used: nextUsed,
        limit: PRO_DAILY_LIMIT,
        remaining: Math.max(0, PRO_DAILY_LIMIT - nextUsed),
        isPro: true,
      },
      async refund() {
        await writeCount(s3, proKey, Math.max(0, nextUsed - 1), { key: `pro:${pro.originalTransactionId}`, productId: pro.productId });
      },
    };
  }

  const ipKey = `${prefix}/ip:${ipHash}.json`;
  const clientKey = `${prefix}/client:${clientHash}.json`;
  const sameKey = ipKey === clientKey;
  const [ipUsed, clientUsed] = sameKey
    ? [await readCount(s3, ipKey), 0]
    : await Promise.all([readCount(s3, ipKey), readCount(s3, clientKey)]);
  const used = Math.max(ipUsed, clientUsed);

  if (used >= FREE_DAILY_LIMIT) {
    return {
      allowed: false,
      statusCode: 429,
      body: {
        error: 'free_limit_reached',
        limit: FREE_DAILY_LIMIT,
        used,
        isPro: false,
      },
    };
  }

  const nextUsed = used + 1;
  const nextGlobal = globalUsed + 1;
  const writes = [
    writeCount(s3, ipKey, ipUsed + 1),
    writeCount(s3, globalKey, nextGlobal),
  ];
  if (!sameKey) {
    writes.push(writeCount(s3, clientKey, clientUsed + 1));
  }
  await Promise.all(writes);

  return {
    allowed: true,
    quota: {
      used: nextUsed,
      limit: FREE_DAILY_LIMIT,
      remaining: Math.max(0, FREE_DAILY_LIMIT - nextUsed),
      isPro: false,
    },
    async refund() {
      const reverts = [writeCount(s3, ipKey, Math.max(0, ipUsed))];
      if (!sameKey) reverts.push(writeCount(s3, clientKey, Math.max(0, clientUsed)));
      await Promise.all(reverts);
    },
  };
}
