// AWS Systems Manager Parameter Store over plain SigV4 HTTPS — no SDK.
//
// Reads encrypted SecureString parameters (WithDecryption: true) at runtime
// using the Lambda execution role's IAM credentials, caching decrypted values
// in memory for the lifetime of the container. Secret values are NEVER logged
// or included in error messages.
import { createHash, createHmac } from 'node:crypto';

import { credentials } from './s3.mjs';

const sha256Hex = (data) => createHash('sha256').update(data).digest('hex');
const hmac = (key, data) => createHmac('sha256', key).update(data).digest();

const cache = new Map();

export async function getSecureParameter(name, { region = process.env.AWS_REGION ?? 'eu-central-1' } = {}) {
  const cacheKey = `${region}:${name}`;
  if (cache.has(cacheKey)) return cache.get(cacheKey);

  const host = `ssm.${region}.amazonaws.com`;
  const keys = credentials();
  const payload = JSON.stringify({ Name: name, WithDecryption: true });
  const amzDate = new Date().toISOString().replace(/[-:]/g, '').replace(/\.\d{3}/, '');
  const dateStamp = amzDate.slice(0, 8);

  const signed = [
    ['content-type', 'application/x-amz-json-1.1'],
    ['host', host],
    ['x-amz-date', amzDate],
    ...(keys.token ? [['x-amz-security-token', keys.token]] : []),
    ['x-amz-target', 'AmazonSSM.GetParameter'],
  ];
  const canonicalHeaders = signed.map(([k, v]) => `${k}:${v}\n`).join('');
  const signedHeaders = signed.map(([k]) => k).join(';');
  const canonicalRequest = ['POST', '/', '', canonicalHeaders, signedHeaders, sha256Hex(payload)].join('\n');
  const scope = `${dateStamp}/${region}/ssm/aws4_request`;
  const stringToSign = ['AWS4-HMAC-SHA256', amzDate, scope, sha256Hex(canonicalRequest)].join('\n');
  const signingKey = hmac(hmac(hmac(hmac(`AWS4${keys.secretKey}`, dateStamp), region), 'ssm'), 'aws4_request');
  const signature = createHmac('sha256', signingKey).update(stringToSign).digest('hex');

  const response = await fetch(`https://${host}/`, {
    method: 'POST',
    headers: {
      ...Object.fromEntries(signed.filter(([k]) => k !== 'host')),
      Authorization: `AWS4-HMAC-SHA256 Credential=${keys.accessKey}/${scope}, SignedHeaders=${signedHeaders}, Signature=${signature}`,
    },
    body: payload,
  });

  if (!response.ok) {
    throw new Error(`SSM GetParameter(${name}) failed with HTTP ${response.status}`);
  }
  const parsed = await response.json();
  const value = parsed?.Parameter?.Value;
  if (typeof value !== 'string' || !value.trim()) {
    throw new Error(`SSM GetParameter(${name}) returned empty value`);
  }
  const clean = value.trim();
  cache.set(cacheKey, clean);
  return clean;
}
