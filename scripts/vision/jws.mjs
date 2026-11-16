// Apple StoreKit 2 JWS (JSON Web Signature) verification using Node 22 crypto.
//
// Verifies the x5c certificate chain in the JWS header roots to Apple Root CA - G3,
// verifies intermediate CA constraints and validity windows, verifies the ES256
// IEEE-P1363 signature over `<header>.<payload>`, and enforces Brass Pawn bundle
// and Pro product rules.
import { X509Certificate, createHash, verify } from 'node:crypto';

export const BUNDLE_ID = 'com.arte-soft.brasspawn';

export const PRO_PRODUCT_IDS = new Set([
  'com.artesoft.brasspawn.pro.monthly',
  'com.artesoft.brasspawn.pro.yearly',
  'com.artesoft.brasspawn.pro.lifetime',
]);

export const LIFETIME_PRODUCT_ID = 'com.artesoft.brasspawn.pro.lifetime';

// Pinned Apple Root CA - G3 (SHA-256: 63:34:3A:BF:B8:9A:6A:03:EB:B5:7E:9B:3F:5F:A7:BE:7C:4F:5C:75:6F:30:17:B3:A8:C4:88:C3:65:3E:91:79)
const APPLE_ROOT_CA_G3_PEM = `-----BEGIN CERTIFICATE-----
MIICQzCCAcmgAwIBAgIILcX8iNLFS5UwCgYIKoZIzj0EAwMwZzEbMBkGA1UEAwwS
QXBwbGUgUm9vdCBDQSAtIEczMSYwJAYDVQQLDB1BcHBsZSBDZXJ0aWZpY2F0aW9u
IEF1dGhvcml0eTETMBEGA1UECgwKQXBwbGUgSW5jLjELMAkGA1UEBhMCVVMwHhcN
MTQwNDMwMTgxOTA2WhcNMzkwNDMwMTgxOTA2WjBnMRswGQYDVQQDDBJBcHBsZSBS
b290IENBIC0gRzMxJjAkBgNVBAsMHUFwcGxlIENlcnRpZmljYXRpb24gQXV0aG9y
aXR5MRMwEQYDVQQKDApBcHBsZSBJbmMuMQswCQYDVQQGEwJVUzB2MBAGByqGSM49
AgEGBSuBBAAiA2IABJjpLz1AcqTtkyJygRMc3RCV8cWjTnHcFBbZDuWmBSp3ZHtf
TjjTuxxEtX/1H7YyYl3J6YRbTzBPEVoA/VhYDKX1DyxNB0cTddqXl5dvMVztK517
IDvYuVTZXpmkOlEKMaNCMEAwHQYDVR0OBBYEFLuw3qFYM4iapIqZ3r6966/ayySr
MA8GA1UdEwEB/wQFMAMBAf8wDgYDVR0PAQH/BAQDAgEGMAoGCCqGSM49BAMDA2gA
MGUCMQCD6cHEFl4aXTQY2e3v9GwOAEZLuN+yRhHFD/3meoyhpmvOwgPUnPWTxnS4
at+qIxUCMG1mihDK1A3UT82NQz60imOlM27jbdoXt2QfyFMm+YhidDkLF1vLUagM
6BgD56KyKA==
-----END CERTIFICATE-----`;

export const APPLE_ROOT_CA_G3 = new X509Certificate(APPLE_ROOT_CA_G3_PEM);

function decodeBase64Url(str) {
  const normalized = str.replace(/-/g, '+').replace(/_/g, '/');
  const pad = normalized.length % 4 === 0 ? '' : '='.repeat(4 - (normalized.length % 4));
  return Buffer.from(normalized + pad, 'base64');
}

function withinValidity(cert, now) {
  const from = Date.parse(cert.validFrom);
  const to = Date.parse(cert.validTo);
  return Number.isFinite(from) && Number.isFinite(to) && now >= from && now <= to;
}

/**
 * Verifies an Apple StoreKit 2 JWS string (`alg: "ES256"`, `x5c` chain to Apple Root CA - G3)
 * and returns the decoded JSON payload, or throws an Error if invalid.
 */
export function verifyAppleJWS(jws, { now = Date.now(), rootCert = APPLE_ROOT_CA_G3 } = {}) {
  if (typeof jws !== 'string' || jws.length < 64 || jws.length > 32_768) {
    throw new Error('invalid JWS length');
  }
  const parts = jws.split('.');
  if (parts.length !== 3) throw new Error('malformed JWS');

  let header;
  let payload;
  try {
    header = JSON.parse(decodeBase64Url(parts[0]).toString('utf8'));
    payload = JSON.parse(decodeBase64Url(parts[1]).toString('utf8'));
  } catch {
    throw new Error('malformed JWS JSON');
  }

  if (header?.alg !== 'ES256') throw new Error('unsupported JWS alg');
  if (!Array.isArray(header.x5c) || header.x5c.length < 2 || header.x5c.length > 5) {
    throw new Error('missing x5c certificate chain');
  }

  const chain = header.x5c.map((derB64) => {
    if (typeof derB64 !== 'string' || !derB64) throw new Error('empty x5c entry');
    return new X509Certificate(Buffer.from(derB64, 'base64'));
  });

  for (let i = 0; i < chain.length; i++) {
    if (!withinValidity(chain[i], now)) {
      throw new Error(`certificate ${i} outside validity window`);
    }
    if (i < chain.length - 1) {
      const child = chain[i];
      const issuer = chain[i + 1];
      if (!issuer.ca) throw new Error(`certificate ${i + 1} is not a CA`);
      if (!child.checkIssued(issuer) || !child.verify(issuer.publicKey)) {
        throw new Error(`certificate ${i} not signed by issuer ${i + 1}`);
      }
    }
  }

  const top = chain[chain.length - 1];
  const matchesRootDirectly =
    top.fingerprint256 === rootCert.fingerprint256 && top.verify(rootCert.publicKey);
  const signedByRoot =
    rootCert.ca && top.checkIssued(rootCert) && top.verify(rootCert.publicKey);

  if (!matchesRootDirectly && !signedByRoot) {
    throw new Error('x5c chain does not root to Apple Root CA - G3');
  }

  const signingInput = Buffer.from(`${parts[0]}.${parts[1]}`, 'ascii');
  const signature = decodeBase64Url(parts[2]);
  const signatureOk = verify(
    'sha256',
    signingInput,
    { key: chain[0].publicKey, dsaEncoding: 'ieee-p1363' },
    signature,
  );
  if (!signatureOk) throw new Error('invalid JWS ES256 signature');

  return payload;
}

/**
 * Verifies `entitlementJWS` for Brass Pawn Pro/Lifetime access.
 * Never throws: returns `{ isPro: true, originalTransactionId, productId }` or `{ isPro: false, reason }`.
 */
export function verifyProEntitlement(entitlementJWS, options = {}) {
  if (!entitlementJWS || typeof entitlementJWS !== 'string') {
    return { isPro: false, reason: 'none' };
  }
  const now = options.now ?? Date.now();
  try {
    const tx = verifyAppleJWS(entitlementJWS, options);
    if (tx?.bundleId !== BUNDLE_ID) {
      return { isPro: false, reason: 'wrong_bundle' };
    }
    if (!PRO_PRODUCT_IDS.has(tx.productId)) {
      return { isPro: false, reason: 'not_pro_product' };
    }
    if (tx.revocationDate != null) {
      return { isPro: false, reason: 'revoked' };
    }
    if (tx.productId !== LIFETIME_PRODUCT_ID) {
      const expires = Number(tx.expiresDate);
      if (!Number.isFinite(expires) || expires <= now - 86_400_000) {
        return { isPro: false, reason: 'expired' };
      }
    } else if (tx.expiresDate != null && Number(tx.expiresDate) <= now - 86_400_000) {
      return { isPro: false, reason: 'expired' };
    }
    const originalTransactionId = String(tx.originalTransactionId ?? tx.transactionId ?? '').trim();
    if (!originalTransactionId || !/^[A-Za-z0-9_-]{1,128}$/.test(originalTransactionId)) {
      return { isPro: false, reason: 'missing_original_tx_id' };
    }
    return {
      isPro: true,
      originalTransactionId,
      productId: tx.productId,
      environment: tx.environment ?? 'Production',
    };
  } catch (err) {
    return { isPro: false, reason: err.message };
  }
}

/**
 * Extracts a stable SHA-256 client hash for Free-tier quota enforcement from
 * `appTransactionJWS || clientInstallID` (falling back to caller IP hash if both are absent).
 */
export function freeClientHash({ appTransactionJWS, clientInstallID, ip }, options = {}) {
  if (typeof appTransactionJWS === 'string' && appTransactionJWS.trim()) {
    try {
      const appTx = verifyAppleJWS(appTransactionJWS.trim(), options);
      if (appTx?.bundleId === BUNDLE_ID) {
        const stableId = appTx.appTransactionId ?? `${appTx.originalApplicationVersion ?? ''}:${appTx.originalPurchaseDate ?? ''}`;
        if (stableId && stableId !== ':') {
          return createHash('sha256').update(`brasspawn:apptx:${stableId}`).digest('hex');
        }
      }
    } catch {
      // Fall back to hashing the raw token string below.
    }
    return createHash('sha256').update(`brasspawn:rawtx:${appTransactionJWS.trim()}`).digest('hex');
  }
  if (typeof clientInstallID === 'string' && clientInstallID.trim()) {
    return createHash('sha256').update(`brasspawn:install:${clientInstallID.trim().slice(0, 256)}`).digest('hex');
  }
  return createHash('sha256').update(`brasspawn:ipfallback:${ip}`).digest('hex');
}

export function hashIP(ip) {
  return createHash('sha256').update(String(ip ?? 'unknown').trim().toLowerCase()).digest('hex');
}
