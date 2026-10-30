import {WebSocketServer,WebSocket} from 'ws';
import {randomUUID} from 'node:crypto';
import {performance} from 'node:perf_hooks';

export function createBuiltinTransport(server,{authenticate,onConnect=()=>{},onContact=()=>{},hasPending=()=>false,onEvent=()=>{},heartbeatMs=60000,applicationIdleMs=600000,serverHeartbeatMs=120000,ackTimeoutMs=20000,closeGraceMs=5000}={}) {
 const hub=new WebSocketServer({noServer:true,maxPayload:1024,perMessageDeflate:false});
 const connections=new Map(),history=new Map();
 const emit=(ws,event,extra={})=>onEvent({at:new Date().toISOString(),event,connectionId:ws.connectionId,ageMs:Math.round(performance.now()-ws.openedAt),...extra});
 const stopTimers=ws=>{clearTimeout(ws.probeTimer);clearTimeout(ws.closeTimer);};
 const close=(ws,code,reason,cause)=>{
  if(ws.readyState!==WebSocket.OPEN)return;
  clearTimeout(ws.probeTimer);ws.closeCause=cause;
  emit(ws,'closing',{cause,idleMs:Math.round(performance.now()-(ws.lastApplicationHeartbeatAt??ws.openedAt))});
  ws.close(code,reason);
  ws.closeTimer=setTimeout(()=>ws.terminate(),closeGraceMs);ws.closeTimer.unref();
 };
 const schedule=ws=>{
  clearTimeout(ws.probeTimer);
  ws.probeTimer=setTimeout(()=>{
   if(ws.readyState!==WebSocket.OPEN)return;
   ws.promptedAt=performance.now();
   ws.send(JSON.stringify({type:'heartbeat_due',version:1}));
   ws.probeTimer=setTimeout(()=>close(ws,4002,'Heartbeat reply timed out','heartbeat_timeout'),ackTimeoutMs);
   ws.probeTimer.unref();
  },serverHeartbeatMs);ws.probeTimer.unref();
 };
 const wake=(id='default')=>{
  const socket=connections.get(id);
  if(!socket || socket.readyState!==WebSocket.OPEN)return {state:'offline',error:'Built-in connection is offline. The queued request will be sent when it reconnects.'};
  socket.send(JSON.stringify({type:'wake',version:1}));
  history.get(id).lastSentAt=new Date().toISOString();return {state:'builtin_sent',error:null};
 };
 server.on('upgrade',(req,socket,head)=>{
  let route;try{route=new URL(req.url,'http://localhost').pathname;}catch{socket.destroy();return;}
  if(route!=='/api/device/socket'){socket.end('HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n');return;}
  const identity=req.method==='GET' && authenticate(req);
  if(!identity){socket.end('HTTP/1.1 401 Unauthorized\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n');return;}
  const id=typeof identity==='string'?identity:'default';
  hub.handleUpgrade(req,socket,head,ws=>hub.emit('connection',ws,id));
 });
 hub.on('connection',(ws,id)=>{
  const previous=connections.get(id);if(previous)close(previous,4001,'Replaced by a new connection','replaced');
  connections.set(id,ws);ws.connectionId=randomUUID();ws.openedAt=performance.now();ws.heartbeats=0;
  history.set(id,{lastSentAt:null,lastConnectedAt:new Date().toISOString()});ws.alive=true;
  emit(ws,'open');
  ws.on('pong',()=>{ws.alive=true;onContact(id);});
  ws.on('error',()=>{ws.closeCause??='transport_error';});
  ws.on('message',(raw,binary)=>{
   if(binary || connections.get(id)!==ws || ws.readyState!==WebSocket.OPEN)return;
   let data;try{data=JSON.parse(raw.toString());}catch{return;}
   if(data?.type!=='heartbeat' || data.version!==1 || typeof data.nonce!=='string' || !/^[a-zA-Z0-9-]{1,80}$/.test(data.nonce))return;
   const now=performance.now(),gapMs=ws.lastApplicationHeartbeatAt===undefined?null:Math.round(now-ws.lastApplicationHeartbeatAt);
   ws.lastApplicationHeartbeatAt=now;ws.heartbeats++;
   // Opt-in keeps existing clients on their original client-timed protocol.
   ws.serverHeartbeat ||= data.serverHeartbeat===true;
   const reply={type:'heartbeat_ack',version:1,nonce:data.nonce};
   if(ws.serverHeartbeat)Object.assign(reply,{serverHeartbeatMs,connectionId:ws.connectionId});
   if(data.pendingState===true)reply.pending=!!hasPending(id);
   ws.alive=true;onContact(id);ws.send(JSON.stringify(reply));
   emit(ws,'verified',{heartbeats:ws.heartbeats,gapMs,promptReplyMs:ws.promptedAt===undefined?null:Math.round(now-ws.promptedAt),serverHeartbeat:!!ws.serverHeartbeat});
   ws.promptedAt=undefined;
   if(ws.serverHeartbeat)schedule(ws);
  });
  ws.on('close',code=>{
   stopTimers(ws);
   emit(ws,'closed',{code,cause:ws.closeCause??'peer_closed',heartbeats:ws.heartbeats,idleMs:Math.round(performance.now()-(ws.lastApplicationHeartbeatAt??ws.openedAt))});
   if(connections.get(id)===ws)connections.delete(id);
  });
  onConnect(id);ws.send(JSON.stringify({type:'ready',version:1}));if(hasPending(id))wake(id);
 });
 // Negotiated clients have one server-paced application loop, never protocol pings.
 const heartbeat=setInterval(()=>{for(const socket of connections.values()){
  if(socket.serverHeartbeat || socket.readyState!==WebSocket.OPEN)continue;
  if(socket.lastApplicationHeartbeatAt!==undefined){
   if(performance.now()-socket.lastApplicationHeartbeatAt>=applicationIdleMs)close(socket,4003,'Application heartbeat idle','application_idle');
   continue;
  }
  if(!socket.alive){close(socket,4002,'Protocol pong timed out','protocol_timeout');continue;}socket.alive=false;socket.ping();
 }},heartbeatMs);heartbeat.unref();
 return {
  wake,
  status:(id='default')=>({connected:connections.get(id)?.readyState===WebSocket.OPEN,lastSentAt:null,lastConnectedAt:null,...history.get(id)}),
  disconnect(id='default'){const ws=connections.get(id);if(ws){ws.closeCause='revoked';stopTimers(ws);ws.terminate();}connections.delete(id);history.delete(id);},
  shutdown(){clearInterval(heartbeat);for(const ws of hub.clients){ws.closeCause='shutdown';stopTimers(ws);ws.terminate();}hub.close();}
 };
}
