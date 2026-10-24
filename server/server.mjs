import http from 'node:http';
import webpush from 'web-push';
import { createVault } from './vault.mjs';
import { createDevices } from './devices.mjs';
import { createSecurity } from './security.mjs';
import { createBuiltinTransport } from './builtin.mjs';
import { createPushClient, validateKeys } from './push.mjs';
import { randomBytes, scryptSync, timingSafeEqual, createHash, createHmac } from 'node:crypto';
import { DatabaseSync } from 'node:sqlite';
import { mkdirSync, readFileSync, chmodSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.dirname(fileURLToPath(import.meta.url));
const demo = process.env.DEMO_MODE === 'true';
const secure = process.env.COOKIE_SECURE !== 'false';
const origin = process.env.APP_ORIGIN || 'http://localhost:3000';
const passwordHash = process.env.ADMIN_PASSWORD_HASH;
const deviceToken = process.env.DEVICE_TOKEN || '';
if (!demo && (!passwordHash || (deviceToken && deviceToken.length < 32) || !process.env.APP_ORIGIN || !secure || !origin.startsWith('https://'))) {
  throw new Error('Set ADMIN_PASSWORD_HASH, APP_ORIGIN (https), and keep COOKIE_SECURE=true. See README.md.');
}
if (passwordHash && !/^[a-f0-9]{32}:[a-f0-9]{128}$/.test(passwordHash)) throw new Error('Invalid password hash format');
const dataDir = process.env.DATA_DIR || path.join(root, 'data');
mkdirSync(dataDir, { recursive: true, mode:0o700 });
chmodSync(dataDir,0o700);
const db = new DatabaseSync(path.join(dataDir, 'waypoint.sqlite'));
chmodSync(path.join(dataDir,'waypoint.sqlite'),0o600);
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
const vault=createVault(dataDir);
const devices=createDevices(db,vault,{bootstrapToken:deviceToken,bootstrapName:process.env.DEVICE_NAME || 'My phone'});
const security=createSecurity(db,vault,{origin,passwordFingerprint:sha(passwordHash || 'demo')});
const storedVapid = db.prepare("SELECT value FROM settings WHERE key='vapid'").get();
const vapidKeys = storedVapid ? JSON.parse(storedVapid.value) : webpush.generateVAPIDKeys();
if (!storedVapid) db.prepare("INSERT INTO settings VALUES ('vapid',?)").run(JSON.stringify(vapidKeys));
const pushClient = createPushClient({
  privateHosts: (process.env.PUSH_PRIVATE_HOSTS || '').split(',').map(s => s.trim().toLowerCase()).filter(Boolean),
  allowedHosts: (process.env.PUSH_ALLOWED_HOSTS || '').split(',').map(s => s.trim().toLowerCase()).filter(Boolean)
});
const sendingPush = new Set();
async function sendUnifiedWake(deviceId) {
  const sub = db.prepare('SELECT * FROM push_subscriptions WHERE device_id=?').get(deviceId);
  if (!sub) return { state: 'not_registered' };
  if (sendingPush.has(deviceId)) return { state: 'sending' };
  if (sub.last_sent_at && Date.now() - Date.parse(sub.last_sent_at) < 10000) {
    const error='A push was sent recently. Wait 10 seconds, then retry.';
    db.prepare("UPDATE push_subscriptions SET state='rate_limited',last_error=? WHERE device_id=?").run(error,deviceId);
    return {state:'rate_limited',error};
  }
  sendingPush.add(deviceId);
  try {
    db.prepare("UPDATE push_subscriptions SET state='sending',last_sent_at=? WHERE device_id=?").run(now(),deviceId);
    const result = await pushClient.notify({ endpoint: sub.endpoint, keys: {p256dh:sub.p256dh, auth:sub.auth} }, {subject: process.env.VAPID_SUBJECT || origin, ...vapidKeys});
    db.prepare('UPDATE push_subscriptions SET state=?,last_error=? WHERE device_id=? AND endpoint=?').run(result.expired ? 'expired' : result.state, result.error, deviceId, sub.endpoint);
    return result;
  } finally { sendingPush.delete(deviceId); }
}
let builtin;
const transportMode=id=>devices.get(id)?.transport || 'off';
async function sendWake(deviceId) {
  const mode=transportMode(deviceId);
  if(mode==='off')return {state:'not_registered',error:'Enable an internet connection in the Android app, or use SMS / periodic checks.'};
  const device=devices.get(deviceId);
  if(Date.now()-device.last_wake_at<10000)return {state:'rate_limited',error:'A wake-up was sent recently. Wait 10 seconds, then retry.'};
  db.prepare('UPDATE devices SET last_wake_at=? WHERE id=?').run(Date.now(),deviceId);
  return mode==='builtin'?builtin.wake(deviceId):sendUnifiedWake(deviceId);
}
function pushStatus(id) {
 const mode=transportMode(id);
 if(mode==='builtin') {
  const socket=builtin.status(id);
  return {mode,registered:socket.connected,state:socket.connected?(socket.lastSentAt?'builtin_sent':'connected'):'offline',lastError:null,lastSentAt:socket.lastSentAt,connected:socket.connected};
 }
 const sub=db.prepare('SELECT state,last_error,last_sent_at FROM push_subscriptions WHERE device_id=?').get(id || '');
 return {mode,registered:mode==='unifiedpush' && !!sub && sub.state!=='expired',state:mode==='off'?'not_registered':sub?.state || 'not_registered',lastError:mode==='off'?null:sub?.last_error || null,lastSentAt:sub?.last_sent_at || null,connected:false};
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
function issueSession(res) {
 const token=randomBytes(32).toString('hex');
 db.prepare('DELETE FROM sessions WHERE expires<?').run(Date.now());
 db.prepare('INSERT INTO sessions VALUES (?,?)').run(sha(token),Date.now()+12*3600000);
 res.setHeader('Set-Cookie',`waypoint_session=${token}; HttpOnly; SameSite=Strict; Path=/; Max-Age=43200${secure?'; Secure':''}`);
}
function authLimit(res) {
 const attempt=db.prepare('SELECT * FROM attempts WHERE id=1').get();
 if(attempt && attempt.reset_at>Date.now() && attempt.count>=8){res.setHeader('Retry-After','900');throw Object.assign(new Error('Too many attempts. Try again in 15 minutes.'),{status:429});}
}
function failedAuth() {
 const saved=db.prepare('SELECT * FROM attempts WHERE id=1').get();
 const current=saved && saved.reset_at>Date.now()?saved:{count:0,reset_at:Date.now()+900000};
 db.prepare('INSERT OR REPLACE INTO attempts VALUES(1,?,?)').run(current.count+1,current.reset_at);
}
async function reauthenticate(input,res) {
 authLimit(res);
 if(typeof input.password!=='string' || input.password.length>1024 || !checkPassword(input.password) || !await security.validate(input.code)){
  failedAuth();throw Object.assign(new Error('Incorrect password or authenticator / recovery code.'),{status:403});
 }
 db.exec('DELETE FROM attempts');
}
function deviceName(value) {
 if(typeof value!=='string' || !value.trim() || value.trim().length>80 || /[\x00-\x1f\x7f]/.test(value))throw Object.assign(new Error('Use a device name of 1–80 characters without control characters.'),{status:400});
 return value.trim();
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
    const deviceRoute=route.startsWith('/api/device/');
    const device=deviceRoute && !demo?devices.authenticate(req):null;
    if(deviceRoute && !device)return json(res,401,{error:'Invalid or revoked device token'});
    if(device)devices.touch(device.id);
    if(req.method==='POST' && !deviceRoute && req.headers.origin!==origin)return json(res,403,{error:'Origin not allowed'});
    if(req.method==='GET' && route==='/api/session')return json(res,200,{authenticated:!!session(req),demo,deviceName:'My phone'});
    if(req.method==='POST' && route==='/api/login') {
     authLimit(res);const input=await body(req);
     if(typeof input.password!=='string' || input.password.length>1024 || !checkPassword(input.password)){failedAuth();return json(res,401,{error:'Incorrect password or verification code.'});}
     if(security.enabled() && !input.code)return json(res,200,{requiresTwoFactor:true});
     if(!await security.validate(input.code)){failedAuth();return json(res,401,{error:'Incorrect password or verification code.'});}
     db.exec('DELETE FROM attempts');issueSession(res);return json(res,200,{ok:true});
    }
    if (route.startsWith('/api/') && !deviceRoute && !session(req)) return json(res, 401, { error: 'Please sign in.' });
    if (req.method === 'POST' && route === '/api/logout') {
      const token = req.headers.cookie?.match(/waypoint_session=([a-f0-9]{64})/)?.[1];
      if (token) db.prepare('DELETE FROM sessions WHERE token=?').run(sha(token));
      res.setHeader('Set-Cookie', `waypoint_session=; HttpOnly; SameSite=Strict; Path=/; Max-Age=0${secure ? '; Secure' : ''}`);
      return json(res, 200, { ok: true });
    }
    if(req.method==='GET' && route==='/api/security')return json(res,200,{...security.status(),sessionExpires:session(req)?.expires,sessionCount:db.prepare('SELECT count(*) AS n FROM sessions WHERE expires>?').get(Date.now()).n});
    if(req.method==='POST' && route==='/api/security/setup') {
     if(demo)return json(res,409,{error:'Security settings are unavailable in demo mode.'});
     const input=await body(req);await reauthenticate(input,res);return json(res,200,await security.begin(session(req).token));
    }
    if(req.method==='POST' && route==='/api/security/confirm') {
     authLimit(res);const input=await body(req);let result;
     try{result=await security.confirm(session(req).token,input.code);}catch(error){failedAuth();throw error;}
     db.exec('DELETE FROM attempts');issueSession(res);return json(res,200,result);
    }
    if(req.method==='POST' && route==='/api/security/disable') {
     if(demo)return json(res,409,{error:'Security settings are unavailable in demo mode.'});
     await reauthenticate(await body(req),res);security.disable();issueSession(res);return json(res,200,{ok:true});
    }
    if(req.method==='POST' && route==='/api/security/revoke-sessions') {
     await reauthenticate(await body(req),res);db.prepare('DELETE FROM sessions WHERE token<>?').run(session(req).token);return json(res,200,{ok:true});
    }
    if(req.method==='GET' && route==='/api/devices')return json(res,200,{devices:devices.list()});
    if(req.method==='POST' && route==='/api/devices/add') {
     if(demo)return json(res,409,{error:'Device management is unavailable in demo mode.'});
     const input=await body(req),name=deviceName(input.name);await reauthenticate(input,res);
     const added=devices.create(name);
     return json(res,201,{...added,url:origin});
    }
    if(req.method==='POST' && route==='/api/devices/rename') {
     if(demo)return json(res,409,{error:'Device management is unavailable in demo mode.'});
     const input=await body(req),name=deviceName(input.name),saved=devices.get(input.device_id || '');
     if(!saved || saved.revoked_at)return json(res,404,{error:'Device not found.'});
     db.prepare('UPDATE devices SET name=? WHERE id=?').run(name,saved.id);return json(res,200,{ok:true});
    }
    if(req.method==='POST' && route==='/api/devices/revoke') {
     if(demo)return json(res,409,{error:'Device management is unavailable in demo mode.'});
     const input=await body(req);await reauthenticate(input,res);const saved=devices.get(input.device_id || '');
     if(!saved || saved.revoked_at)return json(res,404,{error:'Device not found.'});
     db.prepare("UPDATE devices SET revoked_at=?,transport='off' WHERE id=?").run(now(),saved.id);
     db.prepare('DELETE FROM push_subscriptions WHERE device_id=?').run(saved.id);
     db.prepare("UPDATE commands SET status='cancelled' WHERE device_id=? AND status='queued'").run(saved.id);
     builtin.disconnect(saved.id);return json(res,200,{ok:true});
    }
    const defaultDevice=()=>devices.list().find(d=>!d.revoked_at);
    const selectDevice=id=>{
     const value=id===undefined?defaultDevice():typeof id==='string'?devices.get(id):null;
     if(!value || value.revoked_at)throw Object.assign(new Error('Select an active device.'),{status:404});return value;
    };
    if(req.method==='GET' && route==='/api/dashboard') {
     expireCommands();const selected=url.searchParams.has('device')?selectDevice(url.searchParams.get('device')):defaultDevice();
     const id=selected?.id || '';
     const reports=db.prepare('SELECT * FROM reports WHERE device_id=? ORDER BY id DESC LIMIT 20').all(id);
     return json(res,200,{demo,device:devices.list().find(item=>item.id===id) || null,devices:devices.list(),report:demo?fakeReport:reports[0] || null,reports:demo?[fakeReport]:reports,push:pushStatus(id),commands:db.prepare('SELECT * FROM commands WHERE device_id=? ORDER BY created_at DESC LIMIT 15').all(id)});
    }
    if(req.method==='POST' && route==='/api/locate') {
     if(demo)return json(res,409,{error:'Location requests are unavailable in demo mode.'});
     const selected=selectDevice((await body(req)).device_id);expireCommands();
     const pending=db.prepare("SELECT * FROM commands WHERE device_id=? AND status='queued'").get(selected.id);
     if(pending)return json(res,200,{...pending,push:await sendWake(selected.id)});
     if(Date.now()-devices.get(selected.id).last_request_at<10000)return json(res,429,{error:'Wait 10 seconds between location requests.'});
     const id=randomBytes(16).toString('hex');
     db.prepare("INSERT INTO commands(id,status,created_at,device_id) VALUES (?,'queued',?,?)").run(id,now(),selected.id);
     db.prepare('UPDATE devices SET last_request_at=? WHERE id=?').run(Date.now(),selected.id);
     return json(res,201,{...db.prepare('SELECT * FROM commands WHERE id=?').get(id),push:await sendWake(selected.id)});
    }
    if(req.method==='POST' && route==='/api/retry-push') {
     const selected=selectDevice((await body(req)).device_id);expireCommands();
     if(!db.prepare("SELECT id FROM commands WHERE device_id=? AND status='queued'").get(selected.id))return json(res,409,{error:'No pending request.'});
     return json(res,200,await sendWake(selected.id));
    }
    if (req.method === 'POST' && route === '/api/device/transport') {
      const input=await body(req);
      if(!['builtin','unifiedpush','off'].includes(input.mode))return json(res,400,{error:'Invalid internet mode'});
      db.prepare('UPDATE devices SET transport=? WHERE id=?').run(input.mode,device.id);
      if(input.mode!=='builtin')builtin.disconnect(device.id);
      return json(res,200,{ok:true});
    }
    if(req.method==='POST' && route==='/api/device/identity') {
     const input=await body(req);
     for(const key of ['model','manufacturer'])if(typeof input[key]!=='string' || input[key].length>100 || /[\x00-\x1f\x7f]/.test(input[key]))return json(res,400,{error:'Invalid device identity.'});
     db.prepare('UPDATE devices SET model=?,manufacturer=? WHERE id=?').run(input.model,input.manufacturer,device.id);
     return json(res,200,{id:device.id,name:device.name});
    }
    if (req.method === 'GET' && route === '/api/device/push-info') return json(res,200,{publicKey:vapidKeys.publicKey});
    if (req.method === 'POST' && route === '/api/device/push-subscription') {
      const input = await body(req);
      try { validateKeys(input.keys); await pushClient.validate(input.endpoint); }
      catch { return json(res,400,{error:'Invalid push endpoint or keys. Use a reachable HTTPS push provider.'}); }
      db.prepare("INSERT OR REPLACE INTO push_subscriptions (device_id,endpoint,p256dh,auth,updated_at,state) VALUES (?,?,?,?,?,'registered')").run(device.id,input.endpoint,input.keys.p256dh,input.keys.auth,now());
      return json(res,200,{ok:true});
    }
    if (req.method === 'POST' && route === '/api/device/push-unregister') {
      db.prepare('DELETE FROM push_subscriptions WHERE device_id=?').run(device.id);
      return json(res,200,{ok:true});
    }
    if (req.method === 'POST' && route === '/api/wake-message') {
      if (demo) return json(res, 409, { error: 'SMS wake-up is available on your real dashboard.' });
      expireCommands();
      const selected=selectDevice((await body(req)).device_id);
      const command = db.prepare("SELECT id FROM commands WHERE device_id=? AND status='queued' LIMIT 1").get(selected.id);
      if (!command) return json(res, 409, { error: 'Request a location first.' });
      const unsigned = `WP1 ${Math.floor(Date.now() / 1000)} ${command.id}`;
      const signature = createHmac('sha256', devices.token(selected.id)).update(unsigned).digest('hex');
      return json(res, 200, { message: `${unsigned} ${signature}`, expiresIn: 600 });
    }
    if (req.method === 'POST' && route === '/api/cancel') {
      const selected=selectDevice((await body(req)).device_id);
      db.prepare("UPDATE commands SET status='cancelled' WHERE device_id=? AND status='queued'").run(selected.id);
      return json(res, 200, { ok: true });
    }
    if (req.method === 'GET' && route === '/api/device/commands') {
      expireCommands();
      return json(res, 200, { command: db.prepare("SELECT id,created_at,'locate' AS type FROM commands WHERE device_id=? AND status='queued' LIMIT 1").get(device.id) || null });
    }
    if (req.method === 'POST' && route === '/api/device/report') {
      const input = await body(req);
      if (!Number.isFinite(input.lat) || input.lat < -90 || input.lat > 90 || !Number.isFinite(input.lng) || input.lng < -180 || input.lng > 180 || !Number.isFinite(input.accuracy) || input.accuracy < 0 || input.accuracy > 100000 || !Number.isInteger(input.battery) || input.battery < 0 || input.battery > 100 || typeof input.charging !== 'boolean' || typeof input.recorded_at !== 'string' || !Number.isFinite(Date.parse(input.recorded_at)) || Date.parse(input.recorded_at) > Date.now() + 300000 || Date.parse(input.recorded_at) < Date.now() - 7 * 86400000 || (input.command_id !== undefined && (typeof input.command_id !== 'string' || !/^[a-f0-9]{32}$/.test(input.command_id)))) return json(res, 400, { error: 'Invalid location report' });
      const recordedAt = new Date(input.recorded_at).toISOString();
      if (input.command_id) {
        expireCommands();
        const command = db.prepare("SELECT * FROM commands WHERE id=? AND device_id=? AND status='queued'").get(input.command_id,device.id);
        if (!command || Date.parse(recordedAt) < Date.parse(command.created_at) - 5000) return json(res, 409, { error: 'Command expired, completed, cancelled, or report predates command' });
      }
      db.exec('BEGIN');
      try {
        db.prepare('INSERT INTO reports (lat,lng,accuracy,battery,charging,recorded_at,received_at,device_id) VALUES (?,?,?,?,?,?,?,?)').run(input.lat, input.lng, input.accuracy, input.battery, Number(input.charging), recordedAt, now(),device.id);
        if (input.command_id) db.prepare("UPDATE commands SET status='completed', completed_at=? WHERE id=? AND status='queued'").run(now(), input.command_id);
        db.prepare('DELETE FROM reports WHERE device_id=? AND id NOT IN (SELECT id FROM reports WHERE device_id=? ORDER BY id DESC LIMIT 100)').run(device.id,device.id);
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
builtin=createBuiltinTransport(server,{
 authenticate:req=>!demo && (!req.headers.origin || req.headers.origin===origin) && devices.authenticate(req)?.id,
 onConnect:id=>{db.prepare("UPDATE devices SET transport='builtin' WHERE id=?").run(id);devices.touch(id);},
 onContact:id=>devices.touch(id),
 hasPending:id=>{expireCommands();return !!db.prepare("SELECT id FROM commands WHERE device_id=? AND status='queued'").get(id);}
});
server.requestTimeout = 15000;
server.headersTimeout = 10000;
server.listen(Number(process.env.PORT || 3000), process.env.HOST || '0.0.0.0', () => console.log(`Waypoint listening on port ${process.env.PORT || 3000}${demo ? ' (demo mode)' : ''}`));
for (const signal of ['SIGINT','SIGTERM']) process.on(signal, () => {builtin.shutdown();server.close(() => { db.close(); process.exit(0); });});
