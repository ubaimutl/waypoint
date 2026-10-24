import {test} from 'node:test';
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {mkdtempSync,rmSync,readFileSync,statSync} from 'node:fs';
import {tmpdir} from 'node:os';
import path from 'node:path';
import {randomBytes,scryptSync,createHmac} from 'node:crypto';
import {once} from 'node:events';
import {DatabaseSync} from 'node:sqlite';
import {generate,generateSecret} from 'otplib';
import WebSocket from 'ws';
import {createVault} from '../vault.mjs';
import {createSecurity} from '../security.mjs';
const origin='https://locator.example.test';
test('TOTP replay protection, encrypted storage, and persistent one-use recovery codes',async()=>{
 const directory=mkdtempSync(path.join(tmpdir(),'waypoint-mfa-')),db=new DatabaseSync(':memory:');
 try{
  db.exec('CREATE TABLE settings(key TEXT PRIMARY KEY,value TEXT); CREATE TABLE sessions(token TEXT PRIMARY KEY,expires INTEGER)');
  const vault=createVault(directory),security=createSecurity(db,vault,{origin,passwordFingerprint:'test'});
  const enrollment=await security.begin('session');const token=await generate({secret:enrollment.secret});
  const result=await security.confirm('session',token);assert.equal(security.enabled(),true);assert.equal(result.recoveryCodes.length,10);
  assert.equal(await security.validate(token),false,'enrollment code cannot be reused');
  assert.equal(await security.validate('not-valid'),false);
  assert.equal(await security.validate(result.recoveryCodes[0]),true);assert.equal(await security.validate(result.recoveryCodes[0]),false);
  const restored=createSecurity(db,createVault(directory),{origin,passwordFingerprint:'test'});assert.equal(restored.status().recoveryCodesRemaining,9);
  const stored=db.prepare("SELECT value FROM settings WHERE key='mfa'").get().value;assert.ok(!stored.includes(enrollment.secret));assert.ok(!stored.includes(result.recoveryCodes[1]));
  assert.equal(statSync(path.join(directory,'secrets.key')).mode & 0o777,0o600);
  assert.throws(()=>createSecurity(db,{open(){throw new Error('wrong key');}},{origin,passwordFingerprint:'test'}));
  const concurrentSecret=generateSecret();db.prepare("INSERT OR REPLACE INTO settings VALUES ('mfa',?)").run(vault.seal(JSON.stringify({secret:concurrentSecret,recovery:[]})));
  const concurrentToken=await generate({secret:concurrentSecret});const concurrent=await Promise.all([security.validate(concurrentToken),security.validate(concurrentToken)]);assert.equal(concurrent.filter(Boolean).length,1,'concurrent TOTP replay must be rejected');
  const secret=generateSecret(),previousToken=await generate({secret,epoch:Math.floor(Date.now()/30000)*30-30});
  await assert.rejects(security.confirm('wrong-session',previousToken));
 }finally{db.close();rmSync(directory,{recursive:true,force:true});}
});
test('multi-device isolation, revocation, MFA login enforcement and session rotation',async t=>{
 const directory=mkdtempSync(path.join(tmpdir(),'waypoint-accounts-'));
 const password=randomBytes(24).toString('hex'),token=randomBytes(32).toString('hex'),salt=randomBytes(16).toString('hex');
 const passwordHash=`${salt}:${scryptSync(password,salt,64).toString('hex')}`;
 let child,cookie;
 const start=async(hash=passwordHash)=>{
  child=spawn(process.execPath,['server.mjs'],{cwd:new URL('..',import.meta.url),env:{...process.env,PORT:'3199',HOST:'127.0.0.1',DEMO_MODE:'false',COOKIE_SECURE:'true',APP_ORIGIN:origin,ADMIN_PASSWORD_HASH:hash,DEVICE_TOKEN:token,DEVICE_NAME:'Existing phone',DATA_DIR:directory},stdio:['ignore','pipe','pipe']});
  let errors='';child.stderr.on('data',chunk=>errors+=chunk);
  await new Promise((resolve,reject)=>{child.once('error',reject);child.once('exit',code=>reject(new Error(`Server exited ${code}: ${errors}`)));child.stdout.on('data',chunk=>{if(chunk.toString().includes('listening'))resolve();});});
 };
 const stop=async()=>{const exit=once(child,'exit');child.kill('SIGTERM');await exit;};
 const request=(route,{data,device,session=cookie,headers={}}={})=>fetch('http://127.0.0.1:3199'+route,{method:data===undefined?'GET':'POST',headers:{...(data===undefined?{}:{Origin:origin,'Content-Type':'application/json'}),...(session?{Cookie:session}:{}),...(device?{Authorization:'Bearer '+device}:{}),...headers},body:data===undefined?undefined:JSON.stringify(data)});
 const login=async(code)=>{const result=await request('/api/login',{data:{password,code},session:''});if(result.headers.get('set-cookie'))cookie=result.headers.get('set-cookie').split(';')[0];return result;};
 try{
  await start();await login();let added,firstCommand,secondCommand,report;
  await t.test('add and rename device without exposing saved credentials',async()=>{
   assert.equal((await request('/api/devices/add',{data:{name:'Pixel'}})).status,403);
   const result=await request('/api/devices/add',{data:{name:'Work Pixel',password}});assert.equal(result.status,201);added=await result.json();assert.equal(added.token.length,64);
   const list=await (await request('/api/devices')).json();assert.equal(list.devices.length,2);assert.ok(!JSON.stringify(list).includes(added.token));assert.ok(!JSON.stringify(list).includes('token_hash'));
   assert.equal((await request('/api/devices/rename',{data:{device_id:added.id,name:'Travel Pixel'}})).status,200);
   const detail=await (await request('/api/dashboard?device='+added.id)).json();assert.equal(detail.device.name,'Travel Pixel');assert.ok(!JSON.stringify(detail).includes('token_cipher'));assert.ok(!JSON.stringify(detail).includes('token_hash'));
   assert.equal((await request('/api/device/identity',{device:added.token,data:{manufacturer:'Google',model:'Pixel test'}})).status,200);
  });
  await t.test('commands, locations and SMS credentials are isolated per device',async()=>{
   firstCommand=await (await request('/api/locate',{data:{device_id:'legacy'}})).json();
   secondCommand=await (await request('/api/locate',{data:{device_id:added.id}})).json();assert.notEqual(firstCommand.id,secondCommand.id);
   const first=await (await request('/api/device/commands',{device:token})).json(),second=await (await request('/api/device/commands',{device:added.token})).json();assert.equal(first.command.id,firstCommand.id);assert.equal(second.command.id,secondCommand.id);
   report={lat:1,lng:2,accuracy:20,battery:60,charging:false,recorded_at:new Date().toISOString()};
   assert.equal((await request('/api/device/report',{device:added.token,data:{...report,command_id:firstCommand.id}})).status,409);
   assert.equal((await request('/api/device/report',{device:added.token,data:{...report,command_id:secondCommand.id}})).status,201);
   const firstDashboard=await (await request('/api/dashboard?device=legacy')).json(),secondDashboard=await (await request('/api/dashboard?device='+added.id)).json();assert.equal(firstDashboard.report,null);assert.equal(secondDashboard.report.lat,1);
   const sms=await (await request('/api/wake-message',{data:{device_id:'legacy'}})).json();const parts=sms.message.split(' ');assert.equal(parts[3],createHmac('sha256',token).update(parts.slice(0,3).join(' ')).digest('hex'));
   assert.equal((await request('/api/locate',{data:{device_id:added.id}})).status,429);
  });
  await t.test('two devices have independent sockets and revocation closes only its socket',async()=>{
   const first=new WebSocket('ws://127.0.0.1:3199/api/device/socket',{headers:{Authorization:'Bearer '+token}});await once(first,'open');
   const second=new WebSocket('ws://127.0.0.1:3199/api/device/socket',{headers:{Authorization:'Bearer '+added.token}});await once(second,'open');
   try{
    const firstState=await (await request('/api/dashboard?device=legacy')).json(),secondState=await (await request('/api/dashboard?device='+added.id)).json();assert.equal(firstState.push.connected,true);assert.equal(secondState.push.connected,true);
    const closed=once(second,'close');assert.equal((await request('/api/devices/revoke',{data:{device_id:added.id,password}})).status,200);await closed;
    assert.equal((await request('/api/device/commands',{device:added.token})).status,401);assert.equal((await request('/api/device/commands',{device:token})).status,200);
    assert.equal((await request('/api/dashboard?device=added-invalid')).status,404);assert.equal((await (await request('/api/dashboard?device=legacy')).json()).push.connected,true);
   }finally{first.terminate();second.terminate();}
  });
  let recovery;
  await t.test('MFA enrollment requires password, verifies a code, and rotates all sessions',async()=>{
   const old=cookie;
   assert.equal((await request('/api/security/setup',{data:{password:'wrong'}})).status,403);
   const setup=await (await request('/api/security/setup',{data:{password}})).json();assert.ok(setup.qr.startsWith('data:image/png;base64,'));
   assert.equal((await request('/api/security/confirm',{data:{code:'bad'}})).status,400);
   const response=await request('/api/security/confirm',{data:{code:await generate({secret:setup.secret})}});assert.equal(response.status,200);recovery=(await response.json()).recoveryCodes;
   cookie=response.headers.get('set-cookie').split(';')[0];assert.notEqual(cookie,old);assert.equal((await request('/api/dashboard',{session:old})).status,401);
   const info=await (await request('/api/security')).json();assert.equal(info.twoFactorEnabled,true);assert.equal(info.recoveryCodesRemaining,10);
  });
  await t.test('no dashboard session before second factor, recovery is one-use, devices bypass browser MFA',async()=>{
   const pending=await request('/api/login',{data:{password},session:''});assert.equal((await pending.json()).requiresTwoFactor,true);assert.equal(pending.headers.get('set-cookie'),null);
   assert.equal((await request('/api/dashboard',{session:''})).status,401);
   assert.equal((await request('/api/login',{data:{password,code:'bad'},session:''})).status,401);
   assert.equal((await login(recovery[0])).status,200);
   assert.equal((await request('/api/login',{data:{password,code:recovery[0]},session:''})).status,401);
   assert.equal((await request('/api/device/commands',{device:token,session:''})).status,200);
   assert.equal((await request('/api/devices/add',{data:{name:'New phone',password}})).status,403);
   assert.equal((await request('/api/devices/add',{data:{name:'New phone',password,code:recovery[1]}})).status,201);
  });
  await t.test('MFA disable requires a factor and revokes old sessions',async()=>{
   const old=cookie;
   assert.equal((await request('/api/security/disable',{data:{password}})).status,403);
   const response=await request('/api/security/disable',{data:{password,code:recovery[2]}});assert.equal(response.status,200);cookie=response.headers.get('set-cookie').split(';')[0];
   assert.equal((await request('/api/security',{session:old})).status,401);assert.equal((await (await request('/api/security')).json()).twoFactorEnabled,false);
   const setup=await (await request('/api/security/setup',{data:{password}})).json();const enabled=await request('/api/security/confirm',{data:{code:await generate({secret:setup.secret})}});assert.equal(enabled.status,200);cookie=enabled.headers.get('set-cookie').split(';')[0];
  });
  await t.test('security survives restart and password rotation revokes existing sessions',async()=>{
   await stop();await start();assert.equal((await request('/api/security')).status,200);assert.equal((await (await request('/api/security')).json()).twoFactorEnabled,true);
   await stop();const nextSalt=randomBytes(16).toString('hex');await start(`${nextSalt}:${scryptSync('changed password',nextSalt,64).toString('hex')}`);assert.equal((await request('/api/dashboard')).status,401);
  });
 }finally{if(child?.exitCode===null)await stop();rmSync(directory,{recursive:true,force:true});}
});
