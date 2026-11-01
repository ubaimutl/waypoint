package dev.ubai.waypoint

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.net.*
import android.os.*
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import java.util.UUID
import kotlin.math.min
import kotlin.random.Random

object ConnectionModes {
 fun current(context:Context):String {
  val state=context.getSharedPreferences("state",0)
  state.getString("internetMode",null)?.let{return it}
  val old=runCatching{Config.read(context)?.has("pushSubscription")==true}.getOrDefault(false)
  return if(old)"unifiedpush" else "off"
 }
 fun set(context:Context,mode:String) {require(mode in listOf("builtin","unifiedpush","off"));context.getSharedPreferences("state",0).edit().putString("internetMode",mode).commit()}
 fun notifyServer(context:Context,mode:String) {Thread{runCatching{Config.read(context)?.let{Tracker.api(it,"/api/device/transport",JSONObject().put("mode",mode))}}}.start()}
 fun startBuiltin(context:Context) {
  check(Config.read(context)!=null){"Configure a server first."}
  PushSetup.disable(context)
  set(context,"builtin");notifyServer(context,"builtin")
  context.startForegroundService(Intent(context,InternetService::class.java))
 }
 fun stop(context:Context) {
  set(context,"off");context.stopService(Intent(context,InternetService::class.java));PushSetup.disable(context);notifyServer(context,"off")
 }
}
// Connection verification; process-local state avoids a disk write per origin ACK.
object InternetRuntime {
 const val INTERVAL=RecoveryTrafficPolicy.INTERVAL
 const val FRESHNESS=180000L
 @Volatile var emptyConfirmedAt=0L
 @Volatile var skippedPolls=0L
 @Volatile var running=false
 @Volatile var status="Disconnected"
 @Volatile var verifiedAt=0L
}
class InternetService:Service() {
 private val handler=Handler(Looper.getMainLooper())
 private val cleanup=Executors.newSingleThreadExecutor()
 private val client=OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(0,TimeUnit.MILLISECONDS).followRedirects(false).followSslRedirects(false).build()
 private var socket:WebSocket?=null
 private var generation=0
 private var stopping=false
 private var terminalError:String?=null
 private var retrySeconds=2L
 private var networkCallback:ConnectivityManager.NetworkCallback?=null
 private var activityListener:ConnectivityManager.OnNetworkActiveListener?=null
 private var lastEarlyAt=0L
 private fun onNetworkActive() {
  if(stopping || socket==null || !serverPaced || awaitingHeartbeat!=null || ConnectionModes.current(this)!="builtin")return
  val now=SystemClock.elapsedRealtime()
  if(!RecoveryTrafficPolicy.canCoalesce(now,lastProbeAt,lastEarlyAt))return
  val cm=getSystemService(ConnectivityManager::class.java)
  if(!cm.isDefaultNetworkActive || cm.activeNetwork!=activeNetwork || !networkReady())return
  val caps=cm.getNetworkCapabilities(cm.activeNetwork)?:return
  if(!caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))return
  lastEarlyAt=now;count("internetCoalescedHeartbeats");record("network_active_heartbeat")
  handler.removeCallbacks(heartbeat);sendHeartbeat()
 }
 private var activeNetwork:Network?=null
 private var awaitingHeartbeat:String?=null
 private var heartbeatDeadline=0L
 private var lastProbeAt=0L
 private var verifiedAt=0L
 private var lastCheckpoint=0L
 private var serverPaced=false
 private var openedAt=0L
 private var lastAckElapsed=0L
 private var lastAckUptime=0L
 private var connectionId=""
 private val events=java.util.ArrayDeque<JSONObject>()
 private fun record(event:String,detail:String="") {
  val elapsed=SystemClock.elapsedRealtime();val uptime=SystemClock.uptimeMillis()
  val data=JSONObject().put("at",System.currentTimeMillis()).put("event",event).put("detail",detail)
   .put("connectionId",connectionId).put("ageMs",if(openedAt==0L)0 else elapsed-openedAt)
   .put("sinceAckMs",if(lastAckElapsed==0L)0 else elapsed-lastAckElapsed)
   .put("sleepSinceAckMs",if(lastAckElapsed==0L)0 else (elapsed-lastAckElapsed)-(uptime-lastAckUptime))
   .put("awaitingAck",awaitingHeartbeat!=null).put("serverPaced",serverPaced)
  events.addLast(data);while(events.size>32)events.removeFirst()
  android.util.Log.i("WaypointConnection",data.toString())
 }
 private val counters=mutableMapOf<String,Long>()
 private fun count(name:String){counters[name]=(counters[name]?:0L)+1}
 private fun checkpoint(force:Boolean=false){
  val now=SystemClock.elapsedRealtime()
  if(!force && now-lastCheckpoint<600000)return
  val edit=getSharedPreferences("state",0).edit()
   .putLong("internetVerifiedAt",InternetRuntime.verifiedAt)
   .putLong("internetHeartbeatMs",InternetRuntime.INTERVAL)
   .putString("internetExperiment","server-paced-v1")
   .putLong("internetSkippedPolls",InternetRuntime.skippedPolls)
   .putString("internetConnectionEvents",org.json.JSONArray(events.toList()).toString())
   .putBoolean("internetServerPaced",serverPaced)
  counters.forEach{(name,value)->edit.putLong(name,value)}
  edit.apply();lastCheckpoint=now
 }
 private fun networkReady():Boolean {
  val cm=getSystemService(ConnectivityManager::class.java)
  val network=cm.activeNetwork?:return false
  val caps=cm.getNetworkCapabilities(network)?:return false
  return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
 }
 private val heartbeat=object:Runnable{override fun run(){checkHeartbeat()}}
 private val reconnect=Runnable{connect()}
 override fun onBind(intent:Intent?)=null
 override fun onCreate() {
  super.onCreate()
  InternetRuntime.running=true;InternetRuntime.verifiedAt=0;InternetRuntime.emptyConfirmedAt=0
  val saved=getSharedPreferences("state",0)
  InternetRuntime.skippedPolls=saved.getLong("internetSkippedPolls",0)
  for(name in listOf("internetVerifications","internetReconnects","internetAttempts","internetOpens","internetFailures","internetCloses","internetHeartbeats","internetTimeouts","internetWakeups","internetNetworkChanges","internetCoalescedHeartbeats","internetPendingReplays"))counters[name]=saved.getLong(name,0)
  checkpoint(true)
  getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("internet","Internet connection",NotificationManager.IMPORTANCE_LOW))
  activeNetwork=getSystemService(ConnectivityManager::class.java).activeNetwork
  val callback=object:ConnectivityManager.NetworkCallback(){
   override fun onCapabilitiesChanged(network:Network,capabilities:NetworkCapabilities){handler.post{
    if(stopping)return@post
    if(!networkReady()){if(socket!=null)dropConnection("Waiting for network");else status("Waiting for network");return@post}
    val current=getSystemService(ConnectivityManager::class.java).activeNetwork
    val changed=activeNetwork!=current;activeNetwork=current
    if(changed || socket==null){if(changed)count("internetNetworkChanges");retrySeconds=2;handler.removeCallbacks(reconnect);connect()}
   }}
   override fun onLost(network:Network){handler.post{if(!stopping && activeNetwork==network){activeNetwork=null;dropConnection("Network lost")}}}
  }
  runCatching{getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(callback);networkCallback=callback}
  val listener=ConnectivityManager.OnNetworkActiveListener{handler.post{onNetworkActive()}}
  runCatching{getSystemService(ConnectivityManager::class.java).addDefaultNetworkActiveListener(listener);activityListener=listener}
 }
 private fun notification(message:String):Notification {
  val open=PendingIntent.getActivity(this,12,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
  val stop=PendingIntent.getService(this,13,Intent(this,InternetService::class.java).setAction("stop"),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
  return Notification.Builder(this,"internet").setSmallIcon(R.drawable.ic_waypoint).setContentTitle("Waypoint internet connection").setContentText(message).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).addAction(Notification.Action.Builder(null,"Disconnect",stop).build()).build()
 }
 private fun status(message:String) {
  if(InternetRuntime.status==message)return
  InternetRuntime.status=message
  getSharedPreferences("state",0).edit().putString("internetStatus",message).apply()
  checkpoint(true)
  getSystemService(NotificationManager::class.java).notify(12,notification(message))
 }
 override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
  if(intent?.action=="stop"){ConnectionModes.stop(this);return START_NOT_STICKY}
  if(ConnectionModes.current(this)!="builtin"){stopSelf();return START_NOT_STICKY}
  if(intent?.getBooleanExtra("ensure",false)==true && socket!=null && !stopping){
   // Opening the activity must not replace an in-flight handshake or heartbeat.
   if(verifiedAt>0 && SystemClock.elapsedRealtime()-verifiedAt>=InternetRuntime.FRESHNESS && awaitingHeartbeat==null){handler.removeCallbacks(heartbeat);checkHeartbeat()}
   return START_STICKY
  }
  stopping=false;terminalError=null;InternetRuntime.running=true
  try {startForeground(12,notification("Connecting to your server…"),if(Build.VERSION.SDK_INT>=34)ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)}
  catch(_:Exception){getSharedPreferences("state",0).edit().putString("internetStatus","Could not start listener. Check notifications and background permissions.").apply();stopSelf();return START_NOT_STICKY}
  connect()
  return START_STICKY
 }
 private fun connect() {
  if(stopping || ConnectionModes.current(this)!="builtin")return
  if(!networkReady()){status("Waiting for network");if(networkCallback==null)handler.postDelayed(reconnect,60000);return}
  count("internetAttempts")
  serverPaced=false;openedAt=0;lastAckElapsed=0;lastAckUptime=0;connectionId=""
  handler.removeCallbacks(reconnect);handler.removeCallbacks(heartbeat);awaitingHeartbeat=null;verifiedAt=0;InternetRuntime.verifiedAt=0;InternetRuntime.emptyConfirmedAt=0
  val config=runCatching{Config.read(this)}.getOrNull()?:run{status("Configure a server first.");stopSelf();return}
  generation++;val mine=generation
  socket?.let{previous->cleanup.execute{runCatching{previous.cancel()}}};socket=null
  status("Connecting…")
  val request=try {
   Config.validate(config)
   val builder=Request.Builder().url(config.getString("url").trimEnd('/')+"/api/device/socket").header("Authorization","Bearer "+config.getString("token"))
   if(config.optString("cfId").isNotEmpty()){builder.header("CF-Access-Client-Id",config.getString("cfId"));builder.header("CF-Access-Client-Secret",config.optString("cfSecret"))}
   builder.build()
  }catch(_:Exception){terminalError="Invalid connection settings. Open Connection → Edit and check the URL and credentials.";status(terminalError!!);stopSelf();return}
  socket=client.newWebSocket(request,object:WebSocketListener(){
   override fun onOpen(webSocket:WebSocket,response:Response){handler.post{if(mine==generation && !stopping){
    openedAt=SystemClock.elapsedRealtime();record("open")
    count("internetOpens");status("Verifying connection to your server…");lastProbeAt=0;handler.post(heartbeat)
    Thread{runCatching{
     val fingerprint=java.security.MessageDigest.getInstance("SHA-256").digest((config.getString("url")+"|"+config.getString("token")+"|"+Build.MANUFACTURER+"|"+Build.MODEL+"|ring-v1").toByteArray()).joinToString(""){"%02x".format(it)}
     val state=getSharedPreferences("state",0)
     if(state.getString("identitySynced",null)!=fingerprint){
      Tracker.api(config,"/api/device/identity",JSONObject().put("manufacturer",Build.MANUFACTURER).put("model",Build.MODEL).put("ring_supported",true))
      state.edit().putString("identitySynced",fingerprint).apply()
     }
    }}.start()
   }}}
   override fun onMessage(webSocket:WebSocket,text:String){handler.post{
    if(mine!=generation || stopping || ConnectionModes.current(this@InternetService)!="builtin")return@post
    val data=runCatching{JSONObject(text)}.getOrNull()?:return@post
    if(data.optInt("version")!=1)return@post
    if(data.optString("type")=="heartbeat_due"){
     if(serverPaced && awaitingHeartbeat==null){handler.removeCallbacks(heartbeat);sendHeartbeat()}
     return@post
    }
    if(data.optString("type")=="heartbeat_ack"){
     if(awaitingHeartbeat!=null && data.optString("nonce")==awaitingHeartbeat){
      serverPaced=data.optLong("serverHeartbeatMs")==InternetRuntime.INTERVAL
      connectionId=data.optString("connectionId").take(80)
      record("verified")
      awaitingHeartbeat=null;verifiedAt=SystemClock.elapsedRealtime();retrySeconds=2
      lastAckElapsed=verifiedAt;lastAckUptime=SystemClock.uptimeMillis()
      // Only a matching origin ACK carrying an explicit boolean can suppress a poll.
      val pending=data.opt("pending")
      InternetRuntime.emptyConfirmedAt=if(pending==false)lastProbeAt else 0L
      if(pending==true){count("internetPendingReplays");receiveWake()}
      InternetRuntime.verifiedAt=System.currentTimeMillis();count("internetVerifications")
      status("Connected");checkpoint()
      handler.removeCallbacks(heartbeat);handler.postDelayed(heartbeat,InternetRuntime.INTERVAL+if(serverPaced)20000L else 0L)
     }
     return@post
    }
    if(data.optString("type")!="wake")return@post
    receiveWake()
   }}
   override fun onClosing(webSocket:WebSocket,code:Int,reason:String){webSocket.close(code,reason)}
   override fun onClosed(webSocket:WebSocket,code:Int,reason:String){handler.post{if(mine==generation && !stopping){count("internetCloses");dropConnection("Server closed connection ($code)")}}}
   override fun onFailure(webSocket:WebSocket,t:Throwable,response:Response?){handler.post{
    if(mine!=generation || stopping)return@post
    record("failure",t.javaClass.simpleName+" HTTP "+(response?.code?:0))
    count("internetFailures")
    getSharedPreferences("state",0).edit().putString("internetFailureClass",t.javaClass.simpleName).putInt("internetFailureHttp",response?.code?:0).apply()
    socket=null;handler.removeCallbacks(heartbeat);awaitingHeartbeat=null;verifiedAt=0;InternetRuntime.verifiedAt=0;InternetRuntime.emptyConfirmedAt=0
    if(response?.code in listOf(301,302,303,307,308,401,403)) {
     terminalError="Connection rejected (HTTP ${response?.code}). Check the device token and, if using Cloudflare Access, its service token and Service Auth policy.";status(terminalError!!);stopSelf()
    }else dropConnection("Connection failed" )
   }}
  })
 }
 private fun receiveWake() {
  InternetRuntime.emptyConfirmedAt=0
  count("internetWakeups");checkpoint(true)
  Config.status(this,"Built-in wake-up received. Fetching server command…")
  try{startForegroundService(Intent(this,CommandService::class.java))}
  catch(_:Exception){Config.status(this,"Request received. Allow background location and background activity to report.")}
 }
 private fun checkHeartbeat() {
  if(stopping || socket==null || ConnectionModes.current(this)!="builtin")return
  val now=SystemClock.elapsedRealtime()
  if(awaitingHeartbeat!=null){
   if(now>=heartbeatDeadline){count("internetTimeouts");dropConnection("Server heartbeat timed out");return}
   handler.postDelayed(heartbeat,heartbeatDeadline-now);return
  }
  if(lastProbeAt==0L || now-lastProbeAt>=InternetRuntime.INTERVAL){
   record("client_fallback")
   sendHeartbeat()
  }else handler.postDelayed(heartbeat,InternetRuntime.INTERVAL-(now-lastProbeAt))
 }
 private fun sendHeartbeat() {
  val now=SystemClock.elapsedRealtime()
  count("internetHeartbeats")
  val nonce=UUID.randomUUID().toString();awaitingHeartbeat=nonce;heartbeatDeadline=now+20000;lastProbeAt=now
  if(socket?.send(JSONObject().put("type","heartbeat").put("version",1).put("nonce",nonce).put("serverHeartbeat",true).put("pendingState",true).toString())!=true){dropConnection("Connection is closed");return}
  handler.postDelayed(heartbeat,20000)
 }
 private fun dropConnection(reason:String) {
  record("disconnect",reason)
  generation++;handler.removeCallbacks(heartbeat);awaitingHeartbeat=null;verifiedAt=0;InternetRuntime.verifiedAt=0;InternetRuntime.emptyConfirmedAt=0
  val previous=socket;socket=null;previous?.let{cleanup.execute{runCatching{it.cancel()}}}
  val state=getSharedPreferences("state",0)
  count("internetReconnects");checkpoint(true)
  state.edit().putString("internetDisconnectReason",reason).putLong("internetDisconnectAt",System.currentTimeMillis()).apply()
  retry()
 }
 private fun retry() {
  if(stopping || ConnectionModes.current(this)!="builtin")return
  if(!networkReady()){handler.removeCallbacks(reconnect);status("Waiting for network");if(networkCallback==null)handler.postDelayed(reconnect,60000);return}
  val delay=retrySeconds+Random.nextLong(0,3)
  status("Disconnected · retrying in ${delay}s")
  handler.removeCallbacks(reconnect);handler.postDelayed(reconnect,delay*1000)
  retrySeconds=min(retrySeconds*2,60)
 }
 override fun onDestroy() {
  stopping=true;generation++;InternetRuntime.running=false;InternetRuntime.verifiedAt=0;InternetRuntime.emptyConfirmedAt=0;checkpoint(true);handler.removeCallbacksAndMessages(null)
  val previous=socket;socket=null
  networkCallback?.let{runCatching{getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it)}}
  activityListener?.let{runCatching{getSystemService(ConnectivityManager::class.java).removeDefaultNetworkActiveListener(it)}}
  // Closing a pooled TLS socket can write close_notify. Never do it on Android's main thread.
  cleanup.execute {
   runCatching{previous?.cancel()}
   runCatching{client.dispatcher.cancelAll()}
   runCatching{client.connectionPool.evictAll()}
   client.dispatcher.executorService.shutdown()
  }
  cleanup.shutdown()
  getSystemService(NotificationManager::class.java).cancel(12)
  getSharedPreferences("state",0).edit().putString("internetStatus",terminalError?:"Disconnected").apply()
  super.onDestroy()
 }
}
