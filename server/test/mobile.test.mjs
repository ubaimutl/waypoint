import {test} from 'node:test';
import http from 'node:http';
import assert from 'node:assert/strict';
import {spawn,spawnSync} from 'node:child_process';
import {mkdtempSync,rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import path from 'node:path';
import {randomBytes,scryptSync} from 'node:crypto';
import {once} from 'node:events';
import {generate} from 'otplib';

const origin='https://phones.example.test';
test('phone sign-in, controller scope, revocation and existing tracking migration',async t=>{
 const directory=mkdtempSync(path.join(tmpdir(),'waypoint-mobile-'));
 const password=randomBytes(24).toString('hex'),trackingToken=randomBytes(32).toString('hex'),salt=randomBytes(16).toString('hex');
 let child,cookie,phone,other;
 const start=async(pass=password)=>{
  child=spawn(process.execPath,['server.mjs'],{cwd:new URL('..',import.meta.url),env:{...process.env,PORT:'3201',HOST:'127.0.0.1',DEMO_MODE:'false',COOKIE_SECURE:'true',APP_ORIGIN:origin,ADMIN_PASSWORD_HASH:`${salt}:${scryptSync(pass,salt,64).toString('hex')}`,DEVICE_TOKEN:trackingToken,DATA_DIR:directory},stdio:['ignore','pipe','pipe']});
  let errors='';child.stderr.on('data',chunk=>errors+=chunk);
  await new Promise((resolve,reject)=>{child.once('error',reject);child.once('exit',code=>reject(new Error(`Server exited ${code}: ${errors}`)));child.stdout.on('data',chunk=>{if(chunk.toString().includes('listening'))resolve();});});
 };
 const stop=async()=>{const exit=once(child,'exit');child.kill('SIGTERM');await exit;child=null;};
 const req=(route,{data,token,admin=false,headers={}}={})=>fetch('http://127.0.0.1:3201'+route,{method:data===undefined?'GET':'POST',headers:{Connection:'close',...(data===undefined?{}:{'Content-Type':'application/json'}),...(admin?{Cookie:cookie,Origin:origin}:{}),...(token?{Authorization:'Bearer '+token}:{}),...headers},body:data===undefined?undefined:JSON.stringify(data)});
 const login=async()=>{const response=await req('/api/login',{data:{password},headers:{Origin:origin}});cookie=response.headers.get('set-cookie').split(';')[0];};
 try{
  await start();await login();
  await t.test('native sign-in requires account credentials and rejects foreign browser origins',async()=>{
   assert.equal((await req('/api/mobile/sign-in',{data:{name:'Phone',password:'wrong'}})).status,401);
   assert.equal((await req('/api/mobile/sign-in',{data:{name:'Phone',password},headers:{Origin:'https://evil.test'}})).status,403);
   assert.equal((await req('/api/mobile/devices',{admin:true})).status,401);
   assert.equal((await req('/api/mobile/devices',{token:trackingToken})).status,401);
  });
  await t.test('sign-in reuses existing tracking identity; new phones enroll automatically',async()=>{
   phone=await (await req('/api/mobile/sign-in',{data:{password,name:'Existing phone',device_token:trackingToken}})).json();
   assert.equal(phone.device.id,'legacy');assert.equal(phone.device.token,trackingToken);assert.match(phone.access_token,/^[a-f0-9]{64}$/);
   assert.notEqual(phone.access_token,trackingToken);
   const installation=randomBytes(16).toString('hex');
   other=await (await req('/api/mobile/sign-in',{data:{password,name:'Second phone',installation_id:installation}})).json();assert.notEqual(other.device.id,'legacy');
   const retried=await (await req('/api/mobile/sign-in',{data:{password,name:'Second phone',installation_id:installation}})).json();assert.equal(retried.device.id,other.device.id);assert.equal(retried.device.token,other.device.token);assert.notEqual(retried.access_token,other.access_token);other=retried;
   const list=await (await req('/api/mobile/devices',{token:phone.access_token})).json();assert.equal(list.devices.length,2);
   assert.ok(!JSON.stringify(list).includes(trackingToken));assert.ok(!JSON.stringify(list).includes(other.device.token));assert.ok(!JSON.stringify(list).includes('token_hash'));
   assert.equal((await req('/api/device/commands',{token:phone.access_token})).status,401);
   assert.equal((await req('/api/device/commands',{token:trackingToken})).status,200);
  });
  await t.test('controller credentials can only read device locations and request location/ringing',async()=>{
   const token=phone.access_token;
   for(const route of ['/api/dashboard','/api/devices','/api/security'])assert.equal((await req(route,{token})).status,401);
   for(const route of ['/api/mobile/devices/revoke','/api/mobile/security/disable','/api/mobile/devices/add','/api/mobile/retry-push'])assert.equal((await req(route,{token,data:{password,device_id:other.device.id}})).status,403);
   assert.equal((await req('/api/mobile/locate',{token,data:{device_id:other.device.id}})).status,201);
   const command=(await (await req('/api/device/commands',{token:other.device.token})).json()).command;assert.ok(command);
   const report={lat:1,lng:2,accuracy:10,battery:80,charging:false,recorded_at:new Date().toISOString(),command_id:command.id};
   assert.equal((await req('/api/device/report',{token:other.device.token,data:report})).status,201);
   const detail=await (await req('/api/mobile/dashboard?device='+other.device.id,{token})).json();assert.equal(detail.report.lat,1);
   assert.equal((await req('/api/device/identity',{token:other.device.token,data:{manufacturer:'Test',model:'Phone',ring_supported:true}})).status,200);
   assert.equal((await req('/api/mobile/ring',{token,data:{device_id:other.device.id,action:'start',duration:15,volume:25,vibrate:false}})).status,201);
   assert.equal((await req('/api/mobile/ring',{token,data:{device_id:other.device.id,action:'stop'}})).status,201);
   assert.equal((await req('/api/mobile/ring',{token,data:{device_id:other.device.id,action:'stop'},headers:{Origin:'https://evil.test'}})).status,403);
  });
  await t.test('dashboard revokes account access without revoking tracking or other phones',async()=>{
   assert.equal((await req('/api/devices/revoke-access',{admin:true,data:{device_id:'legacy',password:'wrong'}})).status,403);
   // A request whose body is still arriving must not retain access after revocation.
   const delayed=http.request('http://127.0.0.1:3201/api/mobile/ring',{method:'POST',headers:{Authorization:'Bearer '+phone.access_token,'Content-Type':'application/json','Transfer-Encoding':'chunked',Connection:'close'}});
   const response=new Promise((resolve,reject)=>{delayed.on('response',res=>{res.resume();resolve(res.statusCode)});delayed.on('error',reject)});
   delayed.write('{');await new Promise(resolve=>setTimeout(resolve,40));
   assert.equal((await req('/api/devices/revoke-access',{admin:true,data:{device_id:'legacy',password}})).status,200);
   delayed.end('"device_id":"'+other.device.id+'","action":"stop"}');assert.equal(await response,401);
   assert.equal((await req('/api/mobile/devices',{token:phone.access_token})).status,401);
   assert.equal((await req('/api/mobile/devices',{token:other.access_token})).status,200);
   assert.equal((await req('/api/device/commands',{token:trackingToken})).status,200);
   assert.equal((await req('/api/locate',{admin:true,data:{device_id:'legacy'}})).status,201);
   const dashboard=await (await req('/api/dashboard?device=legacy',{admin:true})).json();assert.equal(dashboard.device.account_access.enabled,false);assert.equal(dashboard.device.revoked_at,null);
   phone=await (await req('/api/mobile/sign-in',{data:{password,name:'Existing phone',device_token:trackingToken}})).json();
   assert.equal((await req('/api/mobile/devices',{token:phone.access_token})).status,200);
  });
  await t.test('sign-out only removes controller access and cannot use another device tracking token',async()=>{
   assert.equal((await req('/api/mobile/sign-out',{token:other.access_token,data:{device_id:'legacy'}})).status,200);
   assert.equal((await req('/api/mobile/devices',{token:other.access_token})).status,401);
   assert.equal((await req('/api/mobile/devices',{token:phone.access_token})).status,200);
   assert.equal((await req('/api/device/commands',{token:other.device.token})).status,200);
   assert.equal((await req('/api/mobile/sign-in',{data:{password,name:'Bad',device_token:'invalid'}})).status,409);
  });
  let recovery;
  await t.test('MFA applies to phone enrollment and enabling it invalidates old controller credentials',async()=>{
   const setup=await (await req('/api/security/setup',{admin:true,data:{password}})).json();
   const confirm=await req('/api/security/confirm',{admin:true,data:{code:await generate({secret:setup.secret})}});recovery=(await confirm.json()).recoveryCodes;cookie=confirm.headers.get('set-cookie').split(';')[0];
   assert.equal((await req('/api/mobile/devices',{token:phone.access_token})).status,401);
   const pending=await (await req('/api/mobile/sign-in',{data:{password,name:'Existing phone',device_token:trackingToken}})).json();assert.equal(pending.requiresTwoFactor,true);assert.equal(pending.access_token,undefined);
   assert.equal((await req('/api/mobile/sign-in',{data:{password,name:'Existing phone',device_token:trackingToken,code:'bad'}})).status,401);
   phone=await (await req('/api/mobile/sign-in',{data:{password,name:'Existing phone',device_token:trackingToken,code:recovery[0]}})).json();assert.ok(phone.access_token);
   assert.equal((await req('/api/mobile/sign-in',{data:{password,name:'Existing phone',device_token:trackingToken,code:recovery[0]}})).status,401);
  });
  await t.test('controller sign-in survives restart; changing dashboard password revokes it permanently',async()=>{
   await stop();await start();assert.equal((await req('/api/mobile/devices',{token:phone.access_token})).status,200);
   await stop();await start('changed-password');assert.equal((await req('/api/mobile/devices',{token:phone.access_token})).status,401);assert.equal((await req('/api/device/commands',{token:trackingToken})).status,200);
   await stop();await start();assert.equal((await req('/api/mobile/devices',{token:phone.access_token})).status,401);
  });
  await t.test('filesystem MFA reset revokes phone account access but preserves recovery tracking',async()=>{
   phone=await (await req('/api/mobile/sign-in',{data:{password,name:'Existing phone',device_token:trackingToken,code:recovery[1]}})).json();assert.ok(phone.access_token);
   await stop();const reset=spawnSync(process.execPath,['scripts/reset-2fa.mjs','--confirm'],{cwd:new URL('..',import.meta.url),env:{...process.env,DATA_DIR:directory},encoding:'utf8'});assert.equal(reset.status,0,reset.stderr);
   await start();assert.equal((await req('/api/mobile/devices',{token:phone.access_token})).status,401);assert.equal((await req('/api/device/commands',{token:trackingToken})).status,200);
  });
 }finally{if(child)await stop();rmSync(directory,{recursive:true,force:true});}
});
