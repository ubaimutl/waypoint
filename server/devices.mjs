import {randomBytes,createHash} from 'node:crypto';
const hash=value=>createHash('sha256').update(value).digest('hex');
export function createDevices(db,vault,{bootstrapToken='',bootstrapName='My phone'}={}) {
 db.exec(`CREATE TABLE IF NOT EXISTS devices (id TEXT PRIMARY KEY,name TEXT NOT NULL,token_hash TEXT NOT NULL UNIQUE,token_cipher TEXT NOT NULL,created_at TEXT NOT NULL,revoked_at TEXT,model TEXT,manufacturer TEXT,last_seen TEXT,transport TEXT NOT NULL DEFAULT 'off',last_request_at INTEGER NOT NULL DEFAULT 0,last_wake_at INTEGER NOT NULL DEFAULT 0);
 CREATE TABLE IF NOT EXISTS push_subscriptions (device_id TEXT PRIMARY KEY,endpoint TEXT,p256dh TEXT,auth TEXT,updated_at TEXT,state TEXT,last_error TEXT,last_sent_at TEXT);`);
 if(bootstrapToken){
  const old=db.prepare("SELECT * FROM devices WHERE id='legacy'").get();
  if(!old) {
   const mode=db.prepare("SELECT value FROM settings WHERE key='transport'").get()?.value || (db.prepare('SELECT id FROM push_subscription WHERE id=1').get()?'unifiedpush':'off');
   db.prepare('INSERT INTO devices (id,name,token_hash,token_cipher,created_at,transport) VALUES (?,?,?,?,?,?)').run('legacy',bootstrapName,hash(bootstrapToken),vault.seal(bootstrapToken),new Date().toISOString(),mode);
  }else if(!old.revoked_at && old.token_hash!==hash(bootstrapToken))db.prepare("UPDATE devices SET token_hash=?,token_cipher=? WHERE id='legacy'").run(hash(bootstrapToken),vault.seal(bootstrapToken));
 }
 for(const table of ['reports','commands'])if(!db.prepare(`PRAGMA table_info(${table})`).all().some(c=>c.name==='device_id'))db.exec(`ALTER TABLE ${table} ADD COLUMN device_id TEXT NOT NULL DEFAULT 'legacy'`);
 if(!db.prepare("SELECT value FROM settings WHERE key='devices_migrated'").get()){
  if(db.prepare("SELECT id FROM devices WHERE id='legacy'").get())db.exec("INSERT OR IGNORE INTO push_subscriptions SELECT 'legacy',endpoint,p256dh,auth,updated_at,state,last_error,last_sent_at FROM push_subscription WHERE id=1");
  db.exec("DELETE FROM push_subscription; INSERT INTO settings VALUES ('devices_migrated','1')");
 }
 db.exec('CREATE INDEX IF NOT EXISTS reports_device ON reports(device_id,id); CREATE INDEX IF NOT EXISTS commands_device ON commands(device_id,status)');
 for(const row of db.prepare('SELECT token_cipher FROM devices').all()){try{vault.open(row.token_cipher);}catch{throw new Error('Cannot decrypt stored device credentials. Restore the original data/secrets.key from backup.');}}
 const get=id=>db.prepare('SELECT * FROM devices WHERE id=?').get(id);
 return {
  get,
  authenticate(req){const value=req.headers.authorization;if(!value?.startsWith('Bearer ') || value.length>520)return null;return db.prepare('SELECT * FROM devices WHERE token_hash=? AND revoked_at IS NULL').get(hash(value.slice(7))) || null;},
  list(){return db.prepare('SELECT id,name,model,manufacturer,last_seen,created_at,revoked_at,transport FROM devices ORDER BY created_at,id').all();},
  create(name){if(db.prepare('SELECT count(*) AS n FROM devices WHERE revoked_at IS NULL').get().n>=20)throw Object.assign(new Error('Limit of 20 active devices reached.'),{status:409});const id=randomBytes(16).toString('hex'),token=randomBytes(32).toString('hex');db.prepare('INSERT INTO devices(id,name,token_hash,token_cipher,created_at) VALUES(?,?,?,?,?)').run(id,name,hash(token),vault.seal(token),new Date().toISOString());return {id,name,token};},
  token(id){return vault.open(get(id).token_cipher);},
  touch(id){db.prepare('UPDATE devices SET last_seen=? WHERE id=? AND revoked_at IS NULL').run(new Date().toISOString(),id);}
 };
}
