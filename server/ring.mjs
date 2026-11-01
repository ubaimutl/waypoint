import {randomBytes} from 'node:crypto';
const fail=(message,status=400)=>{throw Object.assign(new Error(message),{status});};
export function createRinging(db,{clock=Date.now}={}) {
 db.exec(`CREATE TABLE IF NOT EXISTS ringing (device_id TEXT PRIMARY KEY,id TEXT NOT NULL,action TEXT NOT NULL,status TEXT NOT NULL,duration INTEGER NOT NULL,volume INTEGER NOT NULL,vibrate INTEGER NOT NULL,created_at INTEGER NOT NULL,expires_at INTEGER NOT NULL,updated_at INTEGER NOT NULL,error TEXT);`);
 const get=id=>{
  const row=db.prepare('SELECT * FROM ringing WHERE device_id=?').get(id);
  if(row && ['queued','playing'].includes(row.status) && row.expires_at<=clock()){
   db.prepare("UPDATE ringing SET status=?,updated_at=? WHERE device_id=?").run(row.status==='playing'?'unconfirmed':'expired',clock(),id);
   return db.prepare('SELECT * FROM ringing WHERE device_id=?').get(id);
  }
  return row || null;
 };
 return {
  get,
  pending(id){const row=get(id);return row?.status==='queued'?row:null;},
  request(device,input){
   if(!['start','stop'].includes(input.action))fail('Choose start or stop.');
   const old=get(device);
   if(input.action==='start') {
    if(![15,30,60,120].includes(input.duration) || ![25,50,75,100].includes(input.volume) || typeof input.vibrate!=='boolean')fail('Invalid ring duration, volume or vibration.');
    if(old && ['queued','playing'].includes(old.status))fail('Stop the existing ring before starting another.',409);
    if(old && clock()-old.created_at<10000)fail('Wait 10 seconds before ringing again.',429);
   }else if(old?.action==='stop' && old.status==='queued')return old;
   const time=Math.max(clock(),(old?.created_at || 0)+1),id=randomBytes(16).toString('hex');
   db.prepare('INSERT OR REPLACE INTO ringing VALUES (?,?,?,?,?,?,?,?,?,?,NULL)').run(device,id,input.action,'queued',input.action==='start'?input.duration:0,input.action==='start'?input.volume:0,input.action==='start'&&input.vibrate?1:0,time,time+60000,time);
   return get(device);
  },
  acknowledge(device,input){
   const row=get(device);
   if(!row || input.id!==row.id)fail('Ring request was superseded.',409);
   if(!['playing','stopped','failed'].includes(input.status))fail('Invalid ring status.');
   if(input.error!==undefined && (typeof input.error!=='string' || input.error.length>200))fail('Invalid error.');
   if(input.status==='playing' && (row.action!=='start' || !['queued','playing'].includes(row.status)))fail('Ring request is no longer active.',409);
   if(['stopped','failed'].includes(row.status))return row; // Late/repeated acknowledgements cannot resurrect audio.
   const expires=input.status==='playing' && row.status==='queued'?clock()+row.duration*1000:row.expires_at;
   db.prepare('UPDATE ringing SET status=?,expires_at=?,updated_at=?,error=? WHERE device_id=? AND id=?').run(input.status,expires,clock(),input.status==='failed'?(input.error || 'Playback could not start.'):null,device,row.id);
   return get(device);
  },
  revoke(device){db.prepare('DELETE FROM ringing WHERE device_id=?').run(device);}
 };
}
