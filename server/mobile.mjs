import {randomBytes,createHash} from 'node:crypto';
const hash=value=>createHash('sha256').update(value).digest('hex');
// Controller credentials never authenticate tracking requests or dashboard administration.
export function createMobileAccess(db,{passwordFingerprint}) {
 db.exec(`CREATE TABLE IF NOT EXISTS mobile_access (
  device_id TEXT PRIMARY KEY REFERENCES devices(id),token_hash TEXT NOT NULL UNIQUE,
  created_at TEXT NOT NULL,revoked_at TEXT,password_fingerprint TEXT NOT NULL
 ); CREATE TABLE IF NOT EXISTS mobile_enrollments (installation_hash TEXT PRIMARY KEY,device_id TEXT NOT NULL REFERENCES devices(id))`);
 db.prepare('UPDATE mobile_access SET revoked_at=? WHERE password_fingerprint<>? AND revoked_at IS NULL').run(new Date().toISOString(),passwordFingerprint);
 return {
  enrollment(installation){return db.prepare('SELECT device_id FROM mobile_enrollments WHERE installation_hash=?').get(hash(installation))?.device_id;},
  remember(installation,deviceId){db.prepare('INSERT OR REPLACE INTO mobile_enrollments VALUES(?,?)').run(hash(installation),deviceId);},
  issue(deviceId){
   const token=randomBytes(32).toString('hex');
   db.prepare('INSERT OR REPLACE INTO mobile_access VALUES(?,?,?,NULL,?)').run(deviceId,hash(token),new Date().toISOString(),passwordFingerprint);
   return token;
  },
  authenticate(req){
   const raw=req.headers.authorization;
   if(!/^Bearer [a-f0-9]{64}$/.test(raw || ''))return null;
   return db.prepare(`SELECT m.device_id FROM mobile_access m JOIN devices d ON d.id=m.device_id
    WHERE m.token_hash=? AND m.revoked_at IS NULL AND d.revoked_at IS NULL AND m.password_fingerprint=?`).get(hash(raw.slice(7)),passwordFingerprint) || null;
  },
  revoke(deviceId){db.prepare('UPDATE mobile_access SET revoked_at=? WHERE device_id=? AND revoked_at IS NULL').run(new Date().toISOString(),deviceId);},
  status(deviceId){const row=db.prepare('SELECT created_at,revoked_at FROM mobile_access WHERE device_id=?').get(deviceId);return row?{enabled:!row.revoked_at,...row}:{enabled:false};}
 };
}
