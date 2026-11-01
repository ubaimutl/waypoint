package dev.ubai.waypoint

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.media.*
import android.os.*
import org.json.JSONObject
import java.util.concurrent.Executors

internal object RingPolicy {
 fun valid(command:JSONObject,now:Long):Boolean =
  command.optString("id").matches(Regex("[a-f0-9]{32}")) && command.optString("action") in listOf("start","stop") &&
  command.optLong("created_at")>0 && command.optLong("expires_at")>now && command.optLong("expires_at")-now<=120000 &&
  (command.optString("action")=="stop" || (command.optInt("duration") in listOf(15,30,60,120) && command.optInt("volume") in listOf(25,50,75,100) && command.optInt("vibrate") in 0..1))
}
internal object RingCommands {
 fun handle(c:Context,command:JSONObject?) {
  if(command==null || !RingPolicy.valid(command,System.currentTimeMillis()))return
  try{c.startForegroundService(Intent(c,RingService::class.java).putExtra("ring",command.toString()))}
  catch(e:Exception){
   runCatching{Config.read(c)?.let{Tracker.api(it,"/api/device/ring-status",JSONObject().put("id",command.getString("id")).put("status","failed").put("error","Android blocked playback. Open Waypoint and allow background activity."))}}
   Config.status(c,"Android blocked ringing. Open Waypoint and allow background activity.")
  }
 }
}

/** Dispatches authenticated commands without requiring a location permission for ringing. */
class CommandService:Service() {
 private val worker=Executors.newSingleThreadExecutor()
 override fun onBind(i:Intent?)=null
 override fun onStartCommand(i:Intent?,flags:Int,startId:Int):Int {
  val nm=getSystemService(NotificationManager::class.java)
  nm.createNotificationChannel(NotificationChannel("commands","Recovery requests",NotificationManager.IMPORTANCE_LOW))
  startForeground(14,Notification.Builder(this,"commands").setSmallIcon(R.drawable.ic_waypoint).setContentTitle("Waypoint recovery request").setContentText("Checking your server for a request.").build(),if(Build.VERSION.SDK_INT>=34)ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
  worker.execute {
   try {
    val config=Config.read(this)?:return@execute
    val result=Tracker.api(config,"/api/device/commands")
    RingCommands.handle(this,result.optJSONObject("ring"))
    val command=result.optJSONObject("command")
    val expected=i?.getStringExtra("command")
    if(command!=null && (expected==null || command.optString("id")==expected))startForegroundService(Intent(this,LocateService::class.java).putExtra("command",command.getString("id")))
   }catch(e:Exception){Config.status(this,e.message?:"Could not fetch recovery requests.")}
   finally{stopSelf(startId)}
  }
  return START_NOT_STICKY
 }
 override fun onDestroy(){worker.shutdown();super.onDestroy()}
}

class RingService:Service() {
 private val handler=Handler(Looper.getMainLooper())
 private val reports=Executors.newSingleThreadExecutor()
 private var player:MediaPlayer?=null
 private var focus:AudioFocusRequest?=null
 private var lock:PowerManager.WakeLock?=null
 private var currentId:String?=null
 private var config:JSONObject?=null
 private var deadline=0L
 private val stopTimer=Runnable{finishRing("stopped")}
 private val attrs=AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
 override fun onBind(i:Intent?)=null
 private fun report(id:String,status:String,error:String?=null){
  val saved=config?:return
  reports.execute{runCatching{Tracker.api(saved,"/api/device/ring-status",JSONObject().put("id",id).put("status",status).apply{if(error!=null)put("error",error.take(200))})}}
 }
 private fun notification():Notification {
  val stop=PendingIntent.getService(this,16,Intent(this,RingService::class.java).setAction("stop").putExtra("id",currentId),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
  return Notification.Builder(this,"ringing").setSmallIcon(R.drawable.ic_waypoint).setContentTitle("Find my phone: ringing")
   .setContentText("Tap Stop to silence this phone.").setOngoing(true).setOnlyAlertOnce(true)
   .addAction(Notification.Action.Builder(null,"Stop",stop).build()).build()
 }
 override fun onStartCommand(i:Intent?,flags:Int,startId:Int):Int {
  if(i?.action=="stop"){
   if(i.getStringExtra("id")==currentId)finishRing("stopped")
   if(currentId==null)stopSelf()
   return START_NOT_STICKY
  }
  val command=runCatching{JSONObject(i?.getStringExtra("ring")?:"")}.getOrNull()
  getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("ringing","Find my phone sound",NotificationManager.IMPORTANCE_LOW))
  try{startForeground(16,notification(),ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)}
  catch(e:Exception){stopSelf();return START_NOT_STICKY}
  if(command==null || !RingPolicy.valid(command,System.currentTimeMillis())){if(currentId==null)stopSelf();return START_NOT_STICKY}
  config=runCatching{Config.read(this)}.getOrNull()
  val id=command.getString("id")
  val state=getSharedPreferences("state",0)
  val created=command.optLong("created_at")
  if(created<state.getLong("lastRingCreated",0)){if(currentId==null)stopSelf();return START_NOT_STICKY}
  if(!state.edit().putLong("lastRingCreated",created).commit()){if(currentId==null)stopSelf();return START_NOT_STICKY}
  if(command.getString("action")=="stop"){
   releaseAudio();currentId=null;report(id,"stopped");stopSelf();return START_NOT_STICKY
  }
  if(currentId==id){report(id,"playing");return START_NOT_STICKY}
  if(state.getString("lastRingHandled",null)==id){report(id,"stopped");if(currentId==null)stopSelf();return START_NOT_STICKY}
  currentId?.let{report(it,"stopped")};releaseAudio()
  currentId=id
  // Persist before playback: a process restart must not replay a previously handled sound.
  if(!state.edit().putString("lastRingHandled",id).commit()){finishRing("failed","Could not save ring request.");return START_NOT_STICKY}
  try {
   check(config!=null){"Server is not configured."}
   getSystemService(NotificationManager::class.java).notify(16,notification())
   val audio=getSystemService(AudioManager::class.java)
   check(audio.getStreamVolume(AudioManager.STREAM_ALARM)>0){"Alarm volume is muted. Increase it on the phone."}
   focus=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attrs)
    .setOnAudioFocusChangeListener({change->if(currentId==id && change<0)finishRing("stopped")},handler).build()
   check(audio.requestAudioFocus(focus!!)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED){"Android denied audio focus."}
   val duration=command.getInt("duration")*1000L
   deadline=SystemClock.elapsedRealtime()+duration
   lock=getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"Waypoint:ring").apply{acquire(duration+5000)}
   player=MediaPlayer().apply{
    setAudioAttributes(attrs)
    resources.openRawResourceFd(R.raw.find_phone).use{setDataSource(it.fileDescriptor,it.startOffset,it.length)}
    isLooping=true
    val volume=command.getInt("volume")/100f;setVolume(volume,volume)
    setOnErrorListener{_,_,_->finishRing("failed","Could not play the recovery sound.");true}
    prepare();start()
   }
   if(command.getInt("vibrate")==1)getSystemService(VibratorManager::class.java).defaultVibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0,350,250,350,1050),0),attrs)
   handler.postDelayed(stopTimer,(deadline-SystemClock.elapsedRealtime()).coerceAtLeast(0))
   report(id,"playing");Config.status(this,"Recovery sound playing. Stop it from the notification.")
  }catch(e:Exception){finishRing("failed",e.message?:"Could not start sound.")}
  return START_NOT_STICKY
 }
 private fun releaseAudio(){
  handler.removeCallbacks(stopTimer)
  runCatching{player?.release()};player=null
  runCatching{getSystemService(VibratorManager::class.java).defaultVibrator.cancel()}
  focus?.let{runCatching{getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it)}};focus=null
  lock?.let{if(it.isHeld)it.release()};lock=null
 }
 private fun finishRing(status:String,error:String?=null){
  val id=currentId;currentId=null;releaseAudio()
  if(id!=null)report(id,status,error)
  if(error!=null)Config.status(this,error)
  stopSelf()
 }
 override fun onDestroy(){
  val id=currentId;currentId=null;releaseAudio();if(id!=null)report(id,"stopped")
  reports.shutdown();super.onDestroy()
 }
}
