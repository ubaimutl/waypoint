import {WebSocketServer,WebSocket} from 'ws';

export function createBuiltinTransport(server,{authenticate,onConnect=()=>{},onContact=()=>{},hasPending=()=>false,heartbeatMs=60000}={}) {
 const hub=new WebSocketServer({noServer:true,maxPayload:1024,perMessageDeflate:false});
 const connections=new Map(),history=new Map();
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
  connections.get(id)?.close(4001,'Replaced by a new connection');connections.set(id,ws);
  history.set(id,{lastSentAt:null,lastConnectedAt:new Date().toISOString()});ws.alive=true;
  ws.on('pong',()=>{ws.alive=true;onContact(id);});ws.on('error',()=>{});
  // Application-level round trips prove the origin is reachable through a tunnel, not just its edge.
  ws.on('message',(raw,binary)=>{
   if(binary || connections.get(id)!==ws || ws.readyState!==WebSocket.OPEN)return;
   let data;try{data=JSON.parse(raw.toString());}catch{return;}
   if(data?.type!=='heartbeat' || data.version!==1 || typeof data.nonce!=='string' || !/^[a-zA-Z0-9-]{1,80}$/.test(data.nonce))return;
   ws.alive=true;onContact(id);ws.send(JSON.stringify({type:'heartbeat_ack',version:1,nonce:data.nonce}));
  });
  ws.on('close',()=>{if(connections.get(id)===ws)connections.delete(id);});
  onConnect(id);ws.send(JSON.stringify({type:'ready',version:1}));if(hasPending(id))wake(id);
 });
 const heartbeat=setInterval(()=>{for(const socket of connections.values()){if(!socket.alive){socket.terminate();continue;}socket.alive=false;socket.ping();}},heartbeatMs);heartbeat.unref();
 return {
  wake,
  status:(id='default')=>({connected:connections.get(id)?.readyState===WebSocket.OPEN,lastSentAt:null,lastConnectedAt:null,...history.get(id)}),
  disconnect(id='default'){connections.get(id)?.terminate();connections.delete(id);history.delete(id);},
  shutdown(){clearInterval(heartbeat);for(const socket of hub.clients)socket.terminate();hub.close();}
 };
}
