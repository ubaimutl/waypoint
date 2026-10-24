package dev.ubai.waypoint
import android.app.job.*
import android.content.*
import android.provider.Telephony
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.util.concurrent.atomic.AtomicBoolean
object Scheduling {
 fun apply(c:Context) {
  val s=c.getSystemService(JobScheduler::class.java)
  if(c.getSharedPreferences("state",0).getBoolean("fallback",false)) s.schedule(JobInfo.Builder(101,ComponentName(c,CheckJob::class.java)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPeriodic(15*60*1000L).setPersisted(true).build()) else s.cancel(101)
 }
}
class CheckJob:JobService() {
 private var cancelled=AtomicBoolean(false)
 override fun onStartJob(p:JobParameters):Boolean { val flag=AtomicBoolean(false);cancelled=flag;Thread {Tracker.locate(this,false,null,flag);if(!flag.get())jobFinished(p,false)}.start();return true }
 override fun onStopJob(p:JobParameters):Boolean {cancelled.set(true);return true}
}
class BootReceiver:BroadcastReceiver() {
 override fun onReceive(c:Context,i:Intent) {if(i.action==Intent.ACTION_BOOT_COMPLETED || i.action==Intent.ACTION_MY_PACKAGE_REPLACED)Scheduling.apply(c)}
}
class SmsReceiver:BroadcastReceiver() {
 override fun onReceive(c:Context,i:Intent) {
  if(i.action!=Telephony.Sms.Intents.SMS_RECEIVED_ACTION)return
  try {
   val config=Config.read(c)?:return
   val message=Telephony.Sms.Intents.getMessagesFromIntent(i).joinToString(""){it.messageBody}.trim()
   val m=Regex("^WP1 ([0-9]{10}) ([a-f0-9]{32}) ([a-f0-9]{64})$").matchEntire(message)?:return
   if(kotlin.math.abs(System.currentTimeMillis()/1000-m.groupValues[1].toLong())>600)return
   val unsigned="WP1 ${m.groupValues[1]} ${m.groupValues[2]}"
   val mac=Mac.getInstance("HmacSHA256").apply {init(SecretKeySpec(config.getString("token").toByteArray(),"HmacSHA256"))}
   val expected=mac.doFinal(unsigned.toByteArray()).joinToString(""){"%02x".format(it)}
   if(!MessageDigest.isEqual(expected.toByteArray(),m.groupValues[3].toByteArray()))return
   val state=c.getSharedPreferences("state",0)
   val used=state.getStringSet("consumed",emptySet())!!.toMutableSet()
   if(!used.add(m.groupValues[3]))return
   state.edit().putStringSet("consumed",used.toList().takeLast(32).toSet()).commit()
   Config.status(c,"Authenticated SMS received. Requesting location…")
   try {c.startForegroundService(Intent(c,LocateService::class.java).putExtra("command",m.groupValues[2]))}
   catch(_:Exception){Config.status(c,"Android blocked background location. Open Waypoint or enable fallback checks.")}
  }catch(_:Exception){ /* Ignore malformed and unrelated SMS. */ }
 }
}
