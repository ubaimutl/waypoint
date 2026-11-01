import {DatabaseSync} from 'node:sqlite';
import {existsSync} from 'node:fs';
import path from 'node:path';
if(!process.argv.includes('--confirm'))throw new Error('Run with --confirm to disable dashboard 2FA and sign out all browser sessions. Requires server filesystem access.');
const file=path.join(process.env.DATA_DIR || path.resolve('data'),'waypoint.sqlite');
if(!existsSync(file))throw new Error('Database not found. Run inside the server container or set DATA_DIR.');
const db=new DatabaseSync(file);
try{
 db.exec("BEGIN; DELETE FROM settings WHERE key='mfa'; DELETE FROM mfa_pending; DELETE FROM sessions");
 if(db.prepare("SELECT name FROM sqlite_master WHERE type='table' AND name='mobile_access'").get())db.exec("UPDATE mobile_access SET revoked_at=datetime('now') WHERE revoked_at IS NULL");
 db.exec('COMMIT');console.log('Dashboard two-factor authentication disabled; all browser sessions and phone account access revoked. Tracking credentials are unchanged.');
}finally{db.close();}
