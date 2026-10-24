import {DatabaseSync} from 'node:sqlite';
import {existsSync} from 'node:fs';
import path from 'node:path';
if(!process.argv.includes('--confirm'))throw new Error('Run with --confirm to disable dashboard 2FA and sign out all browser sessions. Requires server filesystem access.');
const file=path.join(process.env.DATA_DIR || path.resolve('data'),'waypoint.sqlite');
if(!existsSync(file))throw new Error('Database not found. Run inside the server container or set DATA_DIR.');
const db=new DatabaseSync(file);
try{db.exec("BEGIN; DELETE FROM settings WHERE key='mfa'; DELETE FROM mfa_pending; DELETE FROM sessions; COMMIT");console.log('Dashboard two-factor authentication disabled; all browser sessions revoked. Device credentials are unchanged.');}finally{db.close();}
