import https from 'node:https';
import dns from 'node:dns';
import { ECDH } from 'node:crypto';
import ipaddr from 'ipaddr.js';
import webpush from 'web-push';

export function publicAddress(address) {
  try {
    let parsed = ipaddr.parse(address);
    if (parsed.kind() === 'ipv6' && parsed.isIPv4MappedAddress()) parsed = parsed.toIPv4Address();
    return parsed.range() === 'unicast';
  } catch { return false; }
}
export function endpointUrl(value) {
  if (typeof value !== 'string' || value.length > 2048) throw new Error('Invalid push endpoint');
  const url = new URL(value);
  if (url.protocol !== 'https:' || url.username || url.password || url.hash || !url.hostname) throw new Error('Push endpoints must use HTTPS without credentials or fragments');
  return url;
}
export function validateKeys(keys) {
  if (!keys || typeof keys.p256dh !== 'string' || !/^[A-Za-z0-9_-]{87}$/.test(keys.p256dh) || typeof keys.auth !== 'string' || !/^[A-Za-z0-9_-]{22}$/.test(keys.auth)) throw new Error('Invalid Web Push keys');
  const key = Buffer.from(keys.p256dh, 'base64url');
  if (key.length !== 65 || key[0] !== 4 || Buffer.from(keys.auth, 'base64url').length !== 16) throw new Error('Invalid Web Push keys');
  ECDH.convertKey(key, 'prime256v1');
}
export function guardedLookup(privateHosts = [], lookup = dns.lookup) {
  const trusted = new Set(privateHosts.map(host => host.toLowerCase()));
  return (hostname, options, callback) => {
    lookup(hostname, { all: true, verbatim: true }, (error, addresses) => {
      if (error) return callback(error);
      if (!addresses.length || (!trusted.has(hostname.toLowerCase()) && addresses.some(a => !publicAddress(a.address)))) return callback(new Error('Private push addresses are not allowed'));
      if (options?.all) callback(null, addresses);
      else { const selected = addresses.find(a => !options?.family || a.family === options.family) || addresses[0]; callback(null, selected.address, selected.family); }
    });
  };
}
export function createPushClient({ privateHosts = [], allowedHosts = [], lookup = dns.lookup, send = webpush.sendNotification.bind(webpush) } = {}) {
  const resolve = guardedLookup(privateHosts, lookup);
  const agent = new https.Agent({ lookup: resolve });
  async function validate(endpoint) {
    const url = endpointUrl(endpoint);
    const host = url.hostname.replace(/^\[|\]$/g, '');
    if (allowedHosts.length && !allowedHosts.includes(host)) throw new Error('Push provider is not allowed');
    if (ipaddr.isValid(host)) {
      if (!privateHosts.includes(host) && !publicAddress(host)) throw new Error('Private push addresses are not allowed');
    } else await new Promise((resolvePromise, reject) => resolve(host, { all: true }, (err) => err ? reject(err) : resolvePromise()));
    return url;
  }
  return {
    validate,
    async notify(subscription, vapid) {
      try {
        await validate(subscription.endpoint);
        await send(subscription, JSON.stringify({ type: 'wake', version: 1 }), {
          TTL: 120, urgency: 'high', contentEncoding: 'aes128gcm', timeout: 8000,
          vapidDetails: vapid, agent, headers: { 'User-Agent': 'Waypoint/1.1' }
        });
        return { state: 'sent', error: null, expired: false };
      } catch (error) {
        const expired = error.statusCode === 404 || error.statusCode === 410;
        return { state: 'failed', expired, error: expired ? 'Push registration expired. Register again in the Android app.' : 'Push request failed. Retry or use SMS / periodic checks.' };
      }
    }
  };
}
