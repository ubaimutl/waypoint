import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { randomBytes, scryptSync, createHmac } from 'node:crypto';
import { once } from 'node:events';
const password = randomBytes(20).toString('hex');
const salt = randomBytes(16).toString('hex');
const token = randomBytes(32).toString('hex');
const origin = 'https://phone.example.test';
const dataDir = mkdtempSync(path.join(tmpdir(), 'waypoint-test-'));
let child;
function start() {
  child = spawn(process.execPath, ['server.mjs'], {cwd: new URL('..',import.meta.url),env:{...process.env,PORT:'3198',HOST:'127.0.0.1',DEMO_MODE:'false',COOKIE_SECURE:'true',APP_ORIGIN:origin,ADMIN_PASSWORD_HASH:`${salt}:${scryptSync(password,salt,64).toString('hex')}`,DEVICE_TOKEN:token,DATA_DIR:dataDir},stdio:['ignore','pipe','pipe']});
  return new Promise((resolve,reject) => { child.once('error',reject); child.once('exit',code => reject(new Error(`Server exited ${code}`))); child.stdout.on('data',chunk => { if (chunk.toString().includes('listening')) resolve(); }); });
}
async function stop() { const exited = once(child,'exit'); child.kill('SIGTERM'); await exited; }
let cookie;
const request = (route,{data,auth=false,device=false,headers={}}={}) => fetch(`http://127.0.0.1:3198${route}`, {method:data === undefined?'GET':'POST',headers:{...(data === undefined?{}:{'Content-Type':'application/json','Origin':origin}),...(auth?{Cookie:cookie}:{}),...(device?{Authorization:`Bearer ${token}`} : {}),...headers},body:data === undefined?undefined:JSON.stringify(data)});

test('production authentication, command lifecycle, and location storage', async t => {
  try {
    await start();
    await t.test('private API rejects anonymous requests and secrets never reach session API',async () => {
      assert.equal((await request('/api/dashboard')).status,401);
      const session = await (await request('/api/session')).json(); assert.equal(session.authenticated,false); assert.equal(session.demo,false); assert.equal(Object.keys(session).length,3);
    });
    await t.test('login rejects wrong origin and wrong password',async () => {
      assert.equal((await request('/api/login',{data:{password},headers:{Origin:'https://evil.test'}})).status,403);
      assert.equal((await request('/api/login',{data:{password:'wrong'}})).status,401);
    });
    await t.test('login uses secure HttpOnly session and real dashboard starts empty',async () => {
      const response=await request('/api/login',{data:{password}}); assert.equal(response.status,200);
      const setCookie=response.headers.get('set-cookie'); assert.match(setCookie,/HttpOnly/);assert.match(setCookie,/Secure/);assert.match(setCookie,/SameSite=Strict/);cookie=setCookie.split(';')[0];
      const dashboard=await (await request('/api/dashboard',{auth:true})).json();assert.equal(dashboard.report,null);assert.deepEqual(dashboard.commands,[]);
    });
    await t.test('device routes require their own bearer token',async () => {
      assert.equal((await request('/api/device/commands')).status,401);
      assert.equal((await request('/api/device/commands',{auth:true})).status,401);
      assert.equal((await request('/api/device/commands',{device:true})).status,200);
    });
    await t.test('push registration requires device auth and rejects unsafe endpoints',async()=>{
      assert.equal((await request('/api/device/push-info')).status,401);
      const info=await (await request('/api/device/push-info',{device:true})).json();assert.match(info.publicKey,/^[A-Za-z0-9_-]{87}$/);assert.equal(Object.keys(info).length,1);
      assert.equal((await request('/api/device/push-subscription',{device:true,data:{endpoint:'http://localhost',keys:{}}})).status,400);
      const data=await (await request('/api/dashboard',{auth:true})).json();assert.equal(data.push.registered,false);assert.ok(!JSON.stringify(data.push).includes('endpoint'));
    });
    let command;
    await t.test('locate is idempotent while pending and visible to device',async () => {
      const response=await request('/api/locate',{data:{},auth:true});assert.equal(response.status,201);command=await response.json();
      const repeat=await (await request('/api/locate',{data:{},auth:true})).json();assert.equal(repeat.id,command.id);
      const device=await (await request('/api/device/commands',{device:true})).json();assert.equal(device.command.id,command.id);
    });
    await t.test('SMS wake messages are authenticated, short-lived, and tied to the pending request', async () => {
      assert.equal((await request('/api/wake-message',{data:{}})).status,401);
      const response = await request('/api/wake-message',{data:{},auth:true}); assert.equal(response.status,200);
      const message = await response.json(); const parts = message.message.split(' ');
      assert.equal(parts[0],'WP1'); assert.equal(parts[2],command.id); assert.equal(message.expiresIn,600);
      assert.ok(Math.abs(Date.now()/1000-Number(parts[1]))<5);
      assert.equal(parts[3],createHmac('sha256',token).update(parts.slice(0,3).join(' ')).digest('hex'));
      assert.ok(message.message.length<160);
    });
    const report={lat:52.52,lng:13.4,accuracy:18,battery:74,charging:false,recorded_at:new Date().toISOString()};
    await t.test('invalid reports are rejected without consuming command',async () => {
      assert.equal((await request('/api/device/report',{device:true,data:{...report,lat:999,command_id:command.id}})).status,400);
      assert.equal((await request('/api/device/report',{device:true,data:{...report,recorded_at:'invalid'}})).status,400);
    });
    await t.test('fresh location completes command, replay is rejected',async () => {
      assert.equal((await request('/api/device/report',{device:true,data:{...report,command_id:command.id}})).status,201);
      const dashboard=await (await request('/api/dashboard',{auth:true})).json(); assert.equal(dashboard.report.battery,74); assert.equal(dashboard.commands[0].status,'completed');
      assert.equal((await request('/api/device/report',{device:true,data:{...report,command_id:command.id}})).status,409);
    });
    await t.test('pending request can be cancelled',async () => {
      await request('/api/locate',{data:{},auth:true}); await request('/api/cancel',{data:{},auth:true});
      const result=await (await request('/api/device/commands',{device:true})).json();assert.equal(result.command,null);
    });
    await t.test('reports and session survive restart',async () => {
      await stop();await start(); const response=await request('/api/dashboard',{auth:true});assert.equal(response.status,200);assert.equal((await response.json()).report.lat,52.52);
    });
    await t.test('logout invalidates the server session',async () => {
      assert.equal((await request('/api/logout',{data:{},auth:true})).status,200);assert.equal((await request('/api/dashboard',{auth:true})).status,401);
    });
    await t.test('failed logins are rate limited',async () => {
      for (let i=0;i<8;i++) assert.equal((await request('/api/login',{data:{password:'wrong'}})).status,401);
      assert.equal((await request('/api/login',{data:{password}})).status,429);
    });
  } finally { if(child && child.exitCode===null) await stop(); rmSync(dataDir,{recursive:true,force:true}); }
});
