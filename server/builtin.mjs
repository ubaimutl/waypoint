import { WebSocketServer, WebSocket } from 'ws';

export function createBuiltinTransport(server, { authenticate, onConnect = () => {}, hasPending = () => false, heartbeatMs = 60000 } = {}) {
  const hub = new WebSocketServer({ noServer: true, maxPayload: 1024, perMessageDeflate: false });
  let current = null;
  let lastSentAt = null;
  let lastConnectedAt = null;
  const wake = () => {
    if (!current || current.readyState !== WebSocket.OPEN) return {state:'offline',error:'Built-in connection is offline. The queued request will be sent when it reconnects.'};
    current.send(JSON.stringify({type:'wake',version:1}));
    lastSentAt = new Date().toISOString();
    return {state:'builtin_sent',error:null};
  };
  server.on('upgrade', (req, socket, head) => {
    let route;
    try { route = new URL(req.url,'http://localhost').pathname; } catch { socket.destroy(); return; }
    if (route !== '/api/device/socket') { socket.end('HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n'); return; }
    if (req.method !== 'GET' || !authenticate(req)) { socket.end('HTTP/1.1 401 Unauthorized\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n'); return; }
    hub.handleUpgrade(req,socket,head,ws=>hub.emit('connection',ws,req));
  });
  hub.on('connection', ws => {
    if (current) current.close(4001,'Replaced by a new connection');
    current=ws;lastConnectedAt=new Date().toISOString();ws.alive=true;
    ws.on('pong',()=>{ws.alive=true;});
    ws.on('error',()=>{});
    ws.on('close',()=>{if(current===ws)current=null;});
    onConnect();
    ws.send(JSON.stringify({type:'ready',version:1}));
    if(hasPending())wake();
  });
  const heartbeat=setInterval(()=>{
    if(!current)return;
    if(!current.alive) {current.terminate();return;}
    current.alive=false;current.ping();
  },heartbeatMs);
  heartbeat.unref();
  return {
    wake,
    status:()=>({connected:current?.readyState===WebSocket.OPEN,lastSentAt,lastConnectedAt}),
    disconnect(){if(current)current.close(1000,'Connection disabled');},
    shutdown(){clearInterval(heartbeat);for(const ws of hub.clients)ws.terminate();hub.close();}
  };
}
