import {generateSecret,generateURI,verify} from 'otplib';
import QRCode from 'qrcode';
import {randomBytes,createHash} from 'node:crypto';
const hash=value=>createHash('sha256').update(value).digest('hex');
const failure=(message,status=400)=>Object.assign(new Error(message),{status});
export function createSecurity(db,vault,{origin,passwordFingerprint}) {
 db.exec('CREATE TABLE IF NOT EXISTS mfa_pending (session TEXT PRIMARY KEY,secret TEXT NOT NULL,expires INTEGER NOT NULL)');
 const previous=db.prepare("SELECT value FROM settings WHERE key='password_fingerprint'").get()?.value;
 if(previous && previous!==passwordFingerprint)db.exec('DELETE FROM sessions; DELETE FROM mfa_pending');
 db.prepare("INSERT OR REPLACE INTO settings VALUES ('password_fingerprint',?)").run(passwordFingerprint);
 const read=()=>{const row=db.prepare("SELECT value FROM settings WHERE key='mfa'").get();return row?JSON.parse(vault.open(row.value)):null;};
 const save=value=>db.prepare("INSERT OR REPLACE INTO settings VALUES ('mfa',?)").run(vault.seal(JSON.stringify(value)));
 // Fail closed at startup if the persistent encryption key is lost or wrong.
 read();
 return {
  enabled:()=>!!read(),
  status(){const mfa=read();return {twoFactorEnabled:!!mfa,recoveryCodesRemaining:mfa?.recovery.length || 0};},
  async validate(code){
   const mfa=read();if(!mfa)return true;
   if(typeof code!=='string' || code.length>128)return false;
   const normalized=code.trim().replaceAll('-','').toLowerCase();
   const index=mfa.recovery.indexOf(hash(normalized));
   if(index>=0){mfa.recovery.splice(index,1);save(mfa);return true;}
   if(!/^\d{6}$/.test(code.trim()))return false;
   const result=await verify({secret:mfa.secret,token:code.trim(),epochTolerance:[30,0],...(mfa.lastStep===undefined?{}:{afterTimeStep:mfa.lastStep})});
   if(!result.valid)return false;
   // Re-read after asynchronous verification to reject concurrent use of a code.
   const current=read();if(!current || (current.lastStep!==undefined && result.timeStep<=current.lastStep))return false;
   current.lastStep=result.timeStep;save(current);return true;
  },
  async begin(session){
   if(read())throw failure('Two-factor authentication is already enabled.',409);
   const secret=generateSecret(),uri=generateURI({issuer:'Waypoint',label:new URL(origin).hostname,secret});
   db.prepare('DELETE FROM mfa_pending WHERE expires<?').run(Date.now());
   db.prepare('INSERT OR REPLACE INTO mfa_pending VALUES(?,?,?)').run(session,vault.seal(secret),Date.now()+600000);
   return {secret,uri,qr:await QRCode.toDataURL(uri,{width:240,margin:2})};
  },
  async confirm(session,code){
   if(read())throw failure('Two-factor authentication is already enabled.',409);
   const row=db.prepare('SELECT * FROM mfa_pending WHERE session=? AND expires>?').get(session,Date.now());
   if(!row || typeof code!=='string' || !/^\d{6}$/.test(code.trim()))throw failure('Enter a current authenticator code. Setup expires after 10 minutes.');
   const secret=vault.open(row.secret),result=await verify({secret,token:code.trim(),epochTolerance:[30,0]});
   if(!result.valid)throw failure('Incorrect authenticator code.');
   if(read())throw failure('Two-factor authentication is already enabled.',409);
   const codes=Array.from({length:10},()=>randomBytes(10).toString('hex').match(/.{1,5}/g).join('-'));
   save({secret,lastStep:result.timeStep,recovery:codes.map(c=>hash(c.replaceAll('-','')))});
   db.exec('DELETE FROM mfa_pending; DELETE FROM sessions');return {recoveryCodes:codes};
  },
  disable(){db.exec("DELETE FROM settings WHERE key='mfa'; DELETE FROM mfa_pending; DELETE FROM sessions");}
 };
}
