import http from 'node:http';
import webpush from 'web-push';
import { createPushClient, validateKeys } from './push.mjs';
import { randomBytes, scryptSync, timingSafeEqual, createHash, createHmac } from 'node:crypto';
import { DatabaseSync } from 'node:sqlite';
import { mkdirSync, readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.dirname(fileURLToPath(import.meta.url));
const demo = process.env.DEMO_MODE === 'true';
const secure = process.env.COOKIE_SECURE !== 'false';
const origin = process.env.APP_ORIGIN || 'http://localhost:3000';
const passwordHash = process.env.ADMIN_PASSWORD_HASH;
const deviceToken = process.env.DEVICE_TOKEN || '';
if (!demo && (!passwordHash || deviceToken.length < 32 || !process.env.APP_ORIGIN || !secure || !origin.startsWith('https://'))) {
  throw new Error('Set ADMIN_PASSWORD_HASH, DEVICE_TOKEN (32+ characters), APP_ORIGIN (https), and keep COOKIE_SECURE=true. See README.md.');
}
if (passwordHash && !/^[a-f0-9]{32}:[a-f0-9]{128}$/.test(passwordHash)) throw new Error('Invalid password hash format');
const dataDir = process.env.DATA_DIR || path.join(root, 'data');
mkdirSync(dataDir, { recursive: true });
const db = new DatabaseSync(path.join(dataDir, 'waypoint.sqlite'));
db.exec(`PRAGMA journal_mode=WAL;
CREATE TABLE IF NOT EXISTS sessions (token TEXT PRIMARY KEY, expires INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS reports (id INTEGER PRIMARY KEY, lat REAL, lng REAL, accuracy REAL, battery INTEGER, charging INTEGER, recorded_at TEXT, received_at TEXT);
CREATE TABLE IF NOT EXISTS commands (id TEXT PRIMARY KEY, status TEXT NOT NULL, created_at TEXT NOT NULL, completed_at TEXT);
CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS push_subscription (id INTEGER PRIMARY KEY CHECK(id=1), endpoint TEXT, p256dh TEXT, auth TEXT, updated_at TEXT, state TEXT, last_error TEXT, last_sent_at TEXT);
CREATE TABLE IF NOT EXISTS attempts (id INTEGER PRIMARY KEY CHECK(id=1), count INTEGER, reset_at INTEGER);
`);
const now = () => new Date().toISOString();
const sha = value => createHash('sha256').update(value).digest('hex');
const constantEqual = (a, b) => timingSafeEqual(Buffer.from(sha(a)), Buffer.from(sha(b)));
const storedVapid = db.prepare("SELECT value FROM settings WHERE key='vapid'").get();
const vapidKeys = storedVapid ? JSON.parse(storedVapid.value) : webpush.generateVAPIDKeys();
if (!storedVapid) db.prepare("INSERT INTO settings VALUES ('vapid',?)").run(JSON.stringify(vapidKeys));
const pushClient = createPushClient({
  privateHosts: (process.env.PUSH_PRIVATE_HOSTS || '').split(',').map(s => s.trim().toLowerCase()).filter(Boolean),
  allowedHosts: (process.env.PUSH_ALLOWED_HOSTS || '').split(',').map(s => s.trim().toLowerCase()).filter(Boolean)
});
let sendingPush = false;
async function sendWake() {
  const sub = db.prepare('SELECT * FROM push_subscription WHERE id=1').get();
  if (!sub) return { state: 'not_registered' };
  if (sendingPush) return { state: 'sending' };
  if (sub.last_sent_at && Date.now() - Date.parse(sub.last_sent_at) < 10000) {
    const error='A push was sent recently. Wait 10 seconds, then retry.';
    db.prepare("UPDATE push_subscription SET state='rate_limited',last_error=? WHERE id=1").run(error);
    return {state:'rate_limited',error};
  }
  sendingPush = true;
  try {
    db.prepare("UPDATE push_subscription SET state='sending',last_sent_at=? WHERE id=1").run(now());
    const result = await pushClient.notify({ endpoint: sub.endpoint, keys: {p256dh:sub.p256dh, auth:sub.auth} }, {subject: process.env.VAPID_SUBJECT || origin, ...vapidKeys});
    db.prepare('UPDATE push_subscription SET state=?,last_error=? WHERE id=1 AND endpoint=?').run(result.expired ? 'expired' : result.state, result.error, sub.endpoint);
    return result;
  } finally { sendingPush = false; }
}
function pushStatus() {
 const sub = db.prepare('SELECT state,last_error,last_sent_at FROM push_subscription WHERE id=1').get();
 return {registered:!!sub && sub.state!=='expired',state:sub?.state || 'not_registered',lastError:sub?.last_error || null,lastSentAt:sub?.last_sent_at || null};
}
const fakeReport = { lat: 52.5208, lng: 13.4095, accuracy: 24, battery: 78, charging: 0, recorded_at: now(), received_at: now() };
function checkPassword(value) {
  if (demo && !passwordHash) return constantEqual(value, 'waypoint-demo');
  const [salt, hash] = passwordHash.split(':');
  return timingSafeEqual(scryptSync(value, salt, 64), Buffer.from(hash, 'hex'));
}
function session(req) {
  const raw = req.headers.cookie?.match(/(?:^|;\s*)waypoint_session=([a-f0-9]{64})(?:;|$)/)?.[1];
  return raw && db.prepare('SELECT token FROM sessions WHERE token=? AND expires>?').get(sha(raw), Date.now());
}
function json(res, code, body) { res.writeHead(code, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(body)); }
async function body(req) {
  if (!req.headers['content-type']?.startsWith('application/json')) throw Object.assign(new Error('JSON required'), { status: 415 });
  let data = '';
  for await (const chunk of req) { data += chunk; if (data.length > 8192) throw Object.assign(new Error('Request too large'), { status: 413 }); }
  try { const parsed = JSON.parse(data); if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) throw new Error(); return parsed; } catch { throw Object.assign(new Error('Invalid JSON object'), { status: 400 }); }
}
const expireCommands = () => db.prepare("UPDATE commands SET status='expired' WHERE status='queued' AND created_at<?").run(new Date(Date.now() - 30 * 60000).toISOString());
const server = http.createServer(async (req, res) => {
  res.setHeader('Cache-Control', 'no-store');
  res.setHeader('X-Content-Type-Options', 'nosniff');
  res.setHeader('Referrer-Policy', 'strict-origin-when-cross-origin');
  res.setHeader('X-Frame-Options', 'DENY');
  res.setHeader('Content-Security-Policy', "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: https://tile.openstreetmap.org; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'");
  try {
    const url = new URL(req.url, 'http://localhost');
    const route = url.pathname;
    if (req.method === 'GET' && route === '/healthz') return json(res, 200, { ok: true });
    const deviceRoute = route.startsWith('/api/device/');
    if (deviceRoute && (demo || !constantEqual(req.headers.authorization || '', `Bearer ${deviceToken}`))) return json(res, 401, { error: 'Invalid device token' });
    if (req.method === 'POST' && !deviceRoute && req.headers.origin !== origin) return json(res, 403, { error: 'Origin not allowed' });
    if (req.method === 'GET' && route === '/api/session') return json(res, 200, { authenticated: !!session(req), demo, deviceName: process.env.DEVICE_NAME || 'My phone' });
    if (req.method === 'POST' && route === '/api/login') {
      const attempt = db.prepare('SELECT * FROM attempts WHERE id=1').get();
      if (attempt && attempt.reset_at > Date.now() && attempt.count >= 8) { res.setHeader('Retry-After', '900'); return json(res, 429, { error: 'Too many attempts. Try again in 15 minutes.' }); }
      const input = await body(req);
      if (typeof input.password !== 'string' || input.password.length > 1024 || !checkPassword(input.password)) {
        const current = attempt && attempt.reset_at > Date.now() ? attempt : { count: 0, reset_at: Date.now() + 900000 };
        db.prepare('INSERT OR REPLACE INTO attempts VALUES (1,?,?)').run(current.count + 1, current.reset_at);
        return json(res, 401, { error: 'Incorrect password.' });
      }
      db.prepare('DELETE FROM attempts').run();
      db.prepare('DELETE FROM sessions WHERE expires < ?').run(Date.now());
      const token = randomBytes(32).toString('hex');
      db.prepare('INSERT INTO sessions VALUES (?,?)').run(sha(token), Date.now() + 12 * 3600000);
      res.setHeader('Set-Cookie', `waypoint_session=${token}; HttpOnly; SameSite=Strict; Path=/; Max-Age=43200${secure ? '; Secure' : ''}`);
      return json(res, 200, { ok: true });
    }
    if (route.startsWith('/api/') && !deviceRoute && !session(req)) return json(res, 401, { error: 'Please sign in.' });
    if (req.method === 'POST' && route === '/api/logout') {
      const token = req.headers.cookie?.match(/waypoint_session=([a-f0-9]{64})/)?.[1];
      if (token) db.prepare('DELETE FROM sessions WHERE token=?').run(sha(token));
      res.setHeader('Set-Cookie', `waypoint_session=; HttpOnly; SameSite=Strict; Path=/; Max-Age=0${secure ? '; Secure' : ''}`);
      return json(res, 200, { ok: true });
    }
    if (req.method === 'GET' && route === '/api/dashboard') {
      expireCommands();
      const reports = db.prepare('SELECT * FROM reports ORDER BY id DESC LIMIT 20').all();
      return json(res, 200, { demo, report: demo ? fakeReport : reports[0] || null, reports: demo ? [fakeReport] : reports,
        push: pushStatus(), commands: db.prepare('SELECT * FROM commands ORDER BY created_at DESC LIMIT 15').all() });
    }
    if (req.method === 'POST' && route === '/api/locate') {
      expireCommands();
      const pending = db.prepare("SELECT * FROM commands WHERE status='queued' LIMIT 1").get();
      if (pending) return json(res, 200, {...pending,push:await sendWake()});
      const id = randomBytes(16).toString('hex');
      db.prepare("INSERT INTO commands VALUES (?,'queued',?,NULL)").run(id, now());
      return json(res, 201, {...db.prepare('SELECT * FROM commands WHERE id=?').get(id),push:await sendWake()});
    }
    if (req.method === 'POST' && route === '/api/retry-push') {
      expireCommands();
      if (!db.prepare("SELECT id FROM commands WHERE status='queued'").get()) return json(res,409,{error:'No pending request.'});
      return json(res,200,await sendWake());
    }
    if (req.method === 'GET' && route === '/api/device/push-info') return json(res,200,{publicKey:vapidKeys.publicKey});
    if (req.method === 'POST' && route === '/api/device/push-subscription') {
      const input = await body(req);
      try { validateKeys(input.keys); await pushClient.validate(input.endpoint); }
      catch { return json(res,400,{error:'Invalid push endpoint or keys. Use a reachable HTTPS push provider.'}); }
      db.prepare("INSERT OR REPLACE INTO push_subscription (id,endpoint,p256dh,auth,updated_at,state) VALUES (1,?,?,?,?,'registered')").run(input.endpoint,input.keys.p256dh,input.keys.auth,now());
      return json(res,200,{ok:true});
    }
    if (req.method === 'POST' && route === '/api/device/push-unregister') {
      db.prepare('DELETE FROM push_subscription').run();
      return json(res,200,{ok:true});
    }
    if (req.method === 'POST' && route === '/api/wake-message') {
      if (demo) return json(res, 409, { error: 'SMS wake-up is available on your real dashboard.' });
      expireCommands();
      const command = db.prepare("SELECT id FROM commands WHERE status='queued' LIMIT 1").get();
      if (!command) return json(res, 409, { error: 'Request a location first.' });
      const unsigned = `WP1 ${Math.floor(Date.now() / 1000)} ${command.id}`;
      const signature = createHmac('sha256', deviceToken).update(unsigned).digest('hex');
      return json(res, 200, { message: `${unsigned} ${signature}`, expiresIn: 600 });
    }
    if (req.method === 'POST' && route === '/api/cancel') {
      db.prepare("UPDATE commands SET status='cancelled' WHERE status='queued'").run();
      return json(res, 200, { ok: true });
    }
    if (req.method === 'GET' && route === '/api/device/commands') {
      expireCommands();
      return json(res, 200, { command: db.prepare("SELECT id,created_at,'locate' AS type FROM commands WHERE status='queued' LIMIT 1").get() || null });
    }
    if (req.method === 'POST' && route === '/api/device/report') {
      const input = await body(req);
      if (!Number.isFinite(input.lat) || input.lat < -90 || input.lat > 90 || !Number.isFinite(input.lng) || input.lng < -180 || input.lng > 180 || !Number.isFinite(input.accuracy) || input.accuracy < 0 || input.accuracy > 100000 || !Number.isInteger(input.battery) || input.battery < 0 || input.battery > 100 || typeof input.charging !== 'boolean' || typeof input.recorded_at !== 'string' || !Number.isFinite(Date.parse(input.recorded_at)) || Date.parse(input.recorded_at) > Date.now() + 300000 || Date.parse(input.recorded_at) < Date.now() - 7 * 86400000 || (input.command_id !== undefined && (typeof input.command_id !== 'string' || !/^[a-f0-9]{32}$/.test(input.command_id)))) return json(res, 400, { error: 'Invalid location report' });
      const recordedAt = new Date(input.recorded_at).toISOString();
      if (input.command_id) {
        expireCommands();
        const command = db.prepare("SELECT * FROM commands WHERE id=? AND status='queued'").get(input.command_id);
        if (!command || Date.parse(recordedAt) < Date.parse(command.created_at) - 5000) return json(res, 409, { error: 'Command expired, completed, cancelled, or report predates command' });
      }
      db.exec('BEGIN');
      try {
        db.prepare('INSERT INTO reports (lat,lng,accuracy,battery,charging,recorded_at,received_at) VALUES (?,?,?,?,?,?,?)').run(input.lat, input.lng, input.accuracy, input.battery, Number(input.charging), recordedAt, now());
        if (input.command_id) db.prepare("UPDATE commands SET status='completed', completed_at=? WHERE id=? AND status='queued'").run(now(), input.command_id);
        db.prepare('DELETE FROM reports WHERE id NOT IN (SELECT id FROM reports ORDER BY id DESC LIMIT 100)').run();
        db.exec('COMMIT');
      } catch (error) { db.exec('ROLLBACK'); throw error; }
      return json(res, 201, { ok: true });
    }
    if (req.method === 'GET' && !route.startsWith('/api/')) {
      const files = { '/': ['public/index.html','text/html; charset=utf-8'], '/app.js': ['public/app.js','text/javascript'], '/style.css': ['public/style.css','text/css'], '/vendor/leaflet.js': ['node_modules/leaflet/dist/leaflet.js','text/javascript'], '/vendor/leaflet.css': ['node_modules/leaflet/dist/leaflet.css','text/css'] };
      if (files[route]) { const [file,type] = files[route]; res.writeHead(200, { 'Content-Type': type }); return res.end(readFileSync(path.join(root,file))); }
    }
    json(res, 404, { error: 'Not found' });
  } catch (error) { json(res, error.status || 500, { error: error.status ? error.message : 'Something went wrong. Please try again.' }); }
});
server.requestTimeout = 15000;
server.headersTimeout = 10000;
server.listen(Number(process.env.PORT || 3000), process.env.HOST || '0.0.0.0', () => console.log(`Waypoint listening on port ${process.env.PORT || 3000}${demo ? ' (demo mode)' : ''}`));
for (const signal of ['SIGINT','SIGTERM']) process.on(signal, () => server.close(() => { db.close(); process.exit(0); }));
