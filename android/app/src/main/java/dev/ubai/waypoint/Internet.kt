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
import java.text.DateFormat
import java.util.Date
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
class InternetService:Service() {
 private val handler=Handler(Looper.getMainLooper())
 private val cleanup=Executors.newSingleThreadExecutor()
 private val client=OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(0,TimeUnit.MILLISECONDS).pingInterval(60,TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
 private var socket:WebSocket?=null
 private var generation=0
 private var stopping=false
 private var terminalError:String?=null
 private var retrySeconds=2L
 private var networkCallback:ConnectivityManager.NetworkCallback?=null
 private var activeNetwork:Network?=null
 private var awaitingHeartbeat:String?=null
 private var heartbeatDeadline=0L
 private var lastProbeAt=0L
 private var verifiedAt=0L
 private val heartbeat=object:Runnable{override fun run(){checkHeartbeat()}}
 private val reconnect=Runnable{connect()}
 override fun onBind(intent:Intent?)=null
 override fun onCreate() {
  super.onCreate()
  getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("internet","Internet connection",NotificationManager.IMPORTANCE_LOW))
  activeNetwork=getSystemService(ConnectivityManager::class.java).activeNetwork
  val callback=object:ConnectivityManager.NetworkCallback(){
   override fun onAvailable(network:Network){handler.post{
    if(stopping)return@post
    val changed=activeNetwork!=network;activeNetwork=network
    if(changed || socket==null){retrySeconds=2;handler.removeCallbacks(reconnect);connect()}
   }}
   override fun onLost(network:Network){handler.post{if(!stopping && activeNetwork==network){activeNetwork=null;dropConnection("Network lost")}}}
  }
  runCatching{getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(callback);networkCallback=callback}
 }
 private fun notification(message:String):Notification {
  val open=PendingIntent.getActivity(this,12,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
  val stop=PendingIntent.getService(this,13,Intent(this,InternetService::class.java).setAction("stop"),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
  return Notification.Builder(this,"internet").setSmallIcon(R.drawable.ic_waypoint).setContentTitle("Waypoint internet connection").setContentText(message).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).addAction(Notification.Action.Builder(null,"Disconnect",stop).build()).build()
 }
 private fun status(message:String) {
  getSharedPreferences("state",0).edit().putString("internetStatus",message).apply()
  getSystemService(NotificationManager::class.java).notify(12,notification(message))
 }
 override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
  if(intent?.action=="stop"){ConnectionModes.stop(this);return START_NOT_STICKY}
  if(ConnectionModes.current(this)!="builtin"){stopSelf();return START_NOT_STICKY}
  if(intent?.getBooleanExtra("ensure",false)==true && socket!=null && !stopping && verifiedAt>0 && SystemClock.elapsedRealtime()-verifiedAt<90000)return START_STICKY
  stopping=false;terminalError=null
  try {startForeground(12,notification("Connecting to your server…"),if(Build.VERSION.SDK_INT>=34)ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)}
  catch(_:Exception){getSharedPreferences("state",0).edit().putString("internetStatus","Could not start listener. Check notifications and background permissions.").apply();stopSelf();return START_NOT_STICKY}
  connect()
  return START_STICKY
 }
 private fun connect() {
  if(stopping || ConnectionModes.current(this)!="builtin")return
  handler.removeCallbacks(reconnect);handler.removeCallbacks(heartbeat);awaitingHeartbeat=null;verifiedAt=0
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
    status("Verifying connection to your server…");lastProbeAt=0;handler.post(heartbeat)
    Thread{runCatching{Tracker.api(config,"/api/device/identity",JSONObject().put("manufacturer",Build.MANUFACTURER).put("model",Build.MODEL))}}.start()
   }}}
   override fun onMessage(webSocket:WebSocket,text:String){handler.post{
    if(mine!=generation || stopping || ConnectionModes.current(this@InternetService)!="builtin")return@post
    val data=runCatching{JSONObject(text)}.getOrNull()?:return@post
    if(data.optInt("version")!=1)return@post
    if(data.optString("type")=="heartbeat_ack"){
     if(awaitingHeartbeat!=null && data.optString("nonce")==awaitingHeartbeat){
      awaitingHeartbeat=null;verifiedAt=SystemClock.elapsedRealtime();retrySeconds=2
      val now=System.currentTimeMillis();val state=getSharedPreferences("state",0)
      state.edit().putLong("internetVerifiedAt",now).putLong("internetVerifications",state.getLong("internetVerifications",0)+1).apply()
      status("Connected · verified "+DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(now)))
      handler.removeCallbacks(heartbeat);handler.postDelayed(heartbeat,45000)
     }
     return@post
    }
    if(data.optString("type")!="wake")return@post
    Config.status(this@InternetService,"Built-in wake-up received. Fetching server command…")
    try{startForegroundService(Intent(this@InternetService,LocateService::class.java))}
    catch(_:Exception){Config.status(this@InternetService,"Request received. Allow background location and background activity to report.")}
   }}
   override fun onClosing(webSocket:WebSocket,code:Int,reason:String){webSocket.close(code,reason)}
   override fun onClosed(webSocket:WebSocket,code:Int,reason:String){handler.post{if(mine==generation && !stopping){socket=null;handler.removeCallbacks(heartbeat);awaitingHeartbeat=null;retry()}}}
   override fun onFailure(webSocket:WebSocket,t:Throwable,response:Response?){handler.post{
    if(mine!=generation || stopping)return@post
    socket=null;handler.removeCallbacks(heartbeat);awaitingHeartbeat=null
    if(response?.code in listOf(301,302,303,307,308,401,403)) {
     terminalError="Connection rejected (HTTP ${response?.code}). Check the device token and, if using Cloudflare Access, its service token and Service Auth policy.";status(terminalError!!);stopSelf()
    }else retry()
   }}
  })
 }
 private fun checkHeartbeat() {
  if(stopping || socket==null || ConnectionModes.current(this)!="builtin")return
  val now=SystemClock.elapsedRealtime()
  if(awaitingHeartbeat!=null){
   if(now>=heartbeatDeadline){dropConnection("Server heartbeat timed out");return}
   handler.postDelayed(heartbeat,min(5000,heartbeatDeadline-now));return
  }
  if(lastProbeAt==0L || now-lastProbeAt>=45000){
   val nonce=UUID.randomUUID().toString();awaitingHeartbeat=nonce;heartbeatDeadline=now+20000;lastProbeAt=now
   if(socket?.send(JSONObject().put("type","heartbeat").put("version",1).put("nonce",nonce).toString())!=true){dropConnection("Connection is closed");return}
   handler.postDelayed(heartbeat,5000)
  }else handler.postDelayed(heartbeat,45000-(now-lastProbeAt))
 }
 private fun dropConnection(reason:String) {
  generation++;handler.removeCallbacks(heartbeat);awaitingHeartbeat=null;verifiedAt=0
  val previous=socket;socket=null;previous?.let{cleanup.execute{runCatching{it.cancel()}}}
  val state=getSharedPreferences("state",0)
  state.edit().putString("internetDisconnectReason",reason).putLong("internetDisconnectAt",System.currentTimeMillis()).putLong("internetReconnects",state.getLong("internetReconnects",0)+1).apply()
  retry()
 }
 private fun retry() {
  if(stopping || ConnectionModes.current(this)!="builtin")return
  val delay=retrySeconds+Random.nextLong(0,3)
  status("Disconnected · retrying in ${delay}s")
  handler.removeCallbacks(reconnect);handler.postDelayed(reconnect,delay*1000)
  retrySeconds=min(retrySeconds*2,60)
 }
 override fun onDestroy() {
  stopping=true;generation++;handler.removeCallbacksAndMessages(null)
  val previous=socket;socket=null
  networkCallback?.let{runCatching{getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it)}}
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
