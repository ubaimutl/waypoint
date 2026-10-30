import {test} from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import {once} from 'node:events';
import WebSocket from 'ws';
import {createBuiltinTransport} from '../builtin.mjs';
test('application heartbeats replace protocol pings and silence still expires',async t=>{
 const server=http.createServer();
 const transport=createBuiltinTransport(server,{authenticate:()=>true,heartbeatMs:25,applicationIdleMs:150});
 t.after(async()=>{transport.shutdown();await new Promise(resolve=>server.close(resolve));});
 server.listen(0,'127.0.0.1');await once(server,'listening');
 const client=new WebSocket(`ws://127.0.0.1:${server.address().port}/api/device/socket`);
 const ready=once(client,'message');await once(client,'open');await ready;
 let pings=0;client.on('ping',()=>pings++);
 const ack=once(client,'message');client.send(JSON.stringify({type:'heartbeat',version:1,nonce:'single-loop'}));await ack;
 pings=0;
 // This client automatically answers protocol pings. Only application activity should keep it alive.
 await once(client,'close',{signal:AbortSignal.timeout(2000)});
 assert.equal(pings,0);assert.equal(transport.status().connected,false);
});
test('legacy clients retain server protocol pings',async t=>{
 const server=http.createServer();
 const transport=createBuiltinTransport(server,{authenticate:()=>true,heartbeatMs:25});
 t.after(async()=>{transport.shutdown();await new Promise(resolve=>server.close(resolve));});
 server.listen(0,'127.0.0.1');await once(server,'listening');
 const client=new WebSocket(`ws://127.0.0.1:${server.address().port}/api/device/socket`);
 await once(client,'open');
 for(let i=0;i<3;i++)await once(client,'ping',{signal:AbortSignal.timeout(2000)});
 assert.equal(transport.status().connected,true);
 client.close();await once(client,'close');
});
test('built-in transport authenticates and replays pending requests on reconnect',async t=>{
 const server=http.createServer();let pending=false;
 const transport=createBuiltinTransport(server,{authenticate:r=>r.headers.authorization==='Bearer test-secret',hasPending:()=>pending});
 server.listen(0,'127.0.0.1');await once(server,'listening');
 t.after(async()=>{transport.shutdown();await new Promise(resolve=>server.close(resolve));});
 const url=`ws://127.0.0.1:${server.address().port}/api/device/socket`;
 const denied=new WebSocket(url);denied.on('error',()=>{});
 const [,response]=await once(denied,'unexpected-response');assert.equal(response.statusCode,401);response.resume();denied.terminate();
 assert.equal(transport.wake().state,'offline');
 const connect=()=>new WebSocket(url,{headers:{Authorization:'Bearer test-secret'}});
 const client=connect();const ready=once(client,'message');await once(client,'open');assert.deepEqual(JSON.parse((await ready)[0]),{type:'ready',version:1});
 assert.equal(transport.status().connected,true);
 const heartbeatAck=once(client,'message');
 client.send('{invalid JSON');client.send(JSON.stringify({type:'heartbeat',version:1,nonce:'bad nonce'}));
 client.send(JSON.stringify({type:'heartbeat',version:1,nonce:'test-nonce-123'}));
 assert.deepEqual(JSON.parse((await heartbeatAck)[0]),{type:'heartbeat_ack',version:1,nonce:'test-nonce-123'});
 const wake=once(client,'message');assert.equal(transport.wake().state,'builtin_sent');assert.deepEqual(JSON.parse((await wake)[0]),{type:'wake',version:1});
 const closed=once(client,'close');client.close();await closed;
 pending=true;const next=connect();const messages=[];
 await new Promise((resolve,reject)=>{next.on('error',reject);next.on('message',data=>{messages.push(JSON.parse(data));if(messages.length===2)resolve();});});
 assert.deepEqual(messages,[{type:'ready',version:1},{type:'wake',version:1}]);
 const replaced=once(next,'close');const replacement=connect();await once(replacement,'open');assert.equal((await replaced)[0],4001);
 const disconnected=once(replacement,'close');transport.disconnect();await disconnected;assert.equal(transport.status().connected,false);
});

test('server pacing keeps a client with no heartbeat timer alive, without protocol pings',async t=>{
 const server=http.createServer(),events=[];
 const transport=createBuiltinTransport(server,{authenticate:()=>true,heartbeatMs:15,applicationIdleMs:35,serverHeartbeatMs:60,ackTimeoutMs:100,onEvent:e=>events.push(e)});
 t.after(async()=>{transport.shutdown();await new Promise(resolve=>server.close(resolve));});
 server.listen(0,'127.0.0.1');await once(server,'listening');
 const client=new WebSocket(`ws://127.0.0.1:${server.address().port}/api/device/socket`);
 let probes=0,pings=0,acks=0;
 client.on('ping',()=>{if(acks>0)pings++;});
 const verified=new Promise((resolve,reject)=>{
  client.on('error',reject);
  client.on('close',()=>reject(new Error('Server-paced client disconnected')));
  client.on('message',raw=>{
   const data=JSON.parse(raw);
   if(data.type==='ready' || data.type==='heartbeat_due'){
    if(data.type==='heartbeat_due')probes++;
    client.send(JSON.stringify({type:'heartbeat',version:1,nonce:`probe-${probes}`,serverHeartbeat:true}));
   }
   if(data.type==='heartbeat_ack'){
    assert.equal(data.nonce,`probe-${probes}`);assert.equal(data.serverHeartbeatMs,60);assert.match(data.connectionId,/^[a-f0-9-]{36}$/);
    if(++acks===4)resolve();
   }
  });
 });
 await Promise.race([verified,new Promise((_,reject)=>{const timeout=setTimeout(()=>reject(new Error('No server-paced heartbeat')),2000);timeout.unref();})]);
 assert.equal(probes,3);assert.equal(pings,0);assert.equal(transport.status().connected,true);
 assert.equal(events.filter(e=>e.event==='open').length,1);
 client.close();await once(client,'close');
});

test('missing reply to server prompt has an explicit timeout close and diagnostic cause',async t=>{
 const server=http.createServer(),events=[];
 const transport=createBuiltinTransport(server,{authenticate:()=>true,heartbeatMs:15,serverHeartbeatMs:30,ackTimeoutMs:40,closeGraceMs:50,onEvent:e=>events.push(e)});
 t.after(async()=>{transport.shutdown();await new Promise(resolve=>server.close(resolve));});
 server.listen(0,'127.0.0.1');await once(server,'listening');
 const client=new WebSocket(`ws://127.0.0.1:${server.address().port}/api/device/socket`);
 client.on('message',raw=>{if(JSON.parse(raw).type==='ready')client.send(JSON.stringify({type:'heartbeat',version:1,nonce:'initial',serverHeartbeat:true}));});
 const [code,reason]=await once(client,'close',{signal:AbortSignal.timeout(2000)});
 assert.equal(code,4002);assert.equal(reason.toString(),'Heartbeat reply timed out');
 await new Promise(resolve=>setImmediate(resolve));
 assert.ok(events.some(e=>e.event==='closing' && e.cause==='heartbeat_timeout'));
 assert.equal(transport.status().connected,false);
});

test('invalid traffic cannot satisfy a server heartbeat prompt',async t=>{
 const server=http.createServer();
 const transport=createBuiltinTransport(server,{authenticate:()=>true,heartbeatMs:15,serverHeartbeatMs:30,ackTimeoutMs:40});
 t.after(async()=>{transport.shutdown();await new Promise(resolve=>server.close(resolve));});
 server.listen(0,'127.0.0.1');await once(server,'listening');
 const client=new WebSocket(`ws://127.0.0.1:${server.address().port}/api/device/socket`);
 client.on('message',raw=>{
  const {type}=JSON.parse(raw);
  if(type==='ready')client.send(JSON.stringify({type:'heartbeat',version:1,nonce:'initial',serverHeartbeat:true}));
  if(type==='heartbeat_due'){
   client.pong();client.send(JSON.stringify({type:'heartbeat',version:1,nonce:'invalid nonce'}));
   client.send(JSON.stringify({type:'heartbeat',version:2,nonce:'wrong-version'}));
  }
 });
 assert.equal((await once(client,'close',{signal:AbortSignal.timeout(2000)}))[0],4002);
});

test('replacing a negotiated socket cancels its timer without closing the replacement',async t=>{
 const server=http.createServer(),events=[];
 let closedResolve;const serverClosed=new Promise(resolve=>closedResolve=resolve);
 const transport=createBuiltinTransport(server,{authenticate:()=>true,heartbeatMs:15,serverHeartbeatMs:30,ackTimeoutMs:40,onEvent:e=>{events.push(e);if(e.event==='closed')closedResolve(e);}});
 t.after(async()=>{transport.shutdown();await new Promise(resolve=>server.close(resolve));});
 server.listen(0,'127.0.0.1');await once(server,'listening');
 const url=`ws://127.0.0.1:${server.address().port}/api/device/socket`;
 const first=new WebSocket(url);
 first.on('message',raw=>{if(JSON.parse(raw).type==='ready')first.send(JSON.stringify({type:'heartbeat',version:1,nonce:'first',serverHeartbeat:true}));});
 await new Promise(resolve=>first.on('message',raw=>{if(JSON.parse(raw).type==='heartbeat_ack')resolve();}));
 const closed=once(first,'close');const next=new WebSocket(url);await once(next,'open');
 assert.equal((await closed)[0],4001);
 await new Promise(resolve=>setTimeout(resolve,120));
 assert.equal(transport.status().connected,true);
 const old=await Promise.race([serverClosed,new Promise((_,reject)=>{const timeout=setTimeout(()=>reject(new Error('Server close event missing')),2000);timeout.unref();})]);assert.equal(old.cause,'replaced');
 assert.equal(events.some(e=>e.connectionId===old.connectionId && e.cause==='heartbeat_timeout'),false);
 next.close();await once(next,'close');
});

test('opt-in queue state is current and scoped to the authenticated device',async t=>{
 const server=http.createServer();const pending=new Set();
 const transport=createBuiltinTransport(server,{authenticate:req=>req.headers['x-device'],hasPending:id=>pending.has(id)});
 t.after(async()=>{transport.shutdown();await new Promise(resolve=>server.close(resolve));});
 server.listen(0,'127.0.0.1');await once(server,'listening');
 const client=new WebSocket(`ws://127.0.0.1:${server.address().port}/api/device/socket`,{headers:{'x-device':'a'}});
 const ready=once(client,'message');await once(client,'open');await ready;
 async function heartbeat(nonce,pendingState){const reply=once(client,'message');client.send(JSON.stringify({type:'heartbeat',version:1,nonce,pendingState}));return JSON.parse((await reply)[0]);}
 assert.equal((await heartbeat('empty',true)).pending,false);
 pending.add('b');assert.equal((await heartbeat('other-device',true)).pending,false);
 pending.add('a');assert.equal((await heartbeat('queued',true)).pending,true);
 pending.delete('a');assert.equal((await heartbeat('completed',true)).pending,false);
 assert.equal('pending' in await heartbeat('old-client',undefined),false);
 client.close();await once(client,'close');
});

test('an early heartbeat replaces the scheduled server prompt',async t=>{
 const server=http.createServer();
 const transport=createBuiltinTransport(server,{authenticate:()=>true,serverHeartbeatMs:250,ackTimeoutMs:500});
 t.after(async()=>{transport.shutdown();await new Promise(resolve=>server.close(resolve));});
 server.listen(0,'127.0.0.1');await once(server,'listening');
 const client=new WebSocket(`ws://127.0.0.1:${server.address().port}/api/device/socket`);
 const ready=once(client,'message');await once(client,'open');await ready;
 const send=async nonce=>{const ack=once(client,'message');client.send(JSON.stringify({type:'heartbeat',version:1,nonce,serverHeartbeat:true}));assert.equal(JSON.parse((await ack)[0]).type,'heartbeat_ack');};
 await send('initial');await new Promise(resolve=>setTimeout(resolve,150));
 await send('early');const start=performance.now();
 const [raw]=await once(client,'message',{signal:AbortSignal.timeout(2000)});
 assert.equal(JSON.parse(raw).type,'heartbeat_due');assert.ok(performance.now()-start>=200,'Old prompt fired despite the early verification');
 client.close();await once(client,'close');
});
