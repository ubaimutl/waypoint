package dev.ubai.waypoint
import android.app.Instrumentation
import android.app.Activity
import android.content.Intent
import android.os.Bundle
class DeviceCheck:Instrumentation() {
 private var mode="location"
 override fun onCreate(arguments:Bundle?) {super.onCreate(arguments);mode=arguments?.getString("mode")?:"location";start()}
 override fun onStart() {
  try {
   sendStatus(1,Bundle().apply{putString("stage","Reading app configuration")})
   val context=targetContext
   val config=Config.read(context)?:error("Not provisioned")
   sendStatus(1,Bundle().apply{putString("stage","Checking configured server")})
   Tracker.api(config,"/api/device/commands")
   if(mode=="register-push") {
    sendStatus(1,Bundle().apply{putString("stage","Fetching VAPID key")})
    val key=Tracker.api(config,"/api/device/push-info").getString("publicKey")
    sendStatus(1,Bundle().apply{putString("stage","Selecting push distributor")})
    val distributors=org.unifiedpush.android.connector.UnifiedPush.getDistributors(context)
    check(distributors.isNotEmpty()){ "No UnifiedPush distributor found" }
    val selected=distributors.firstOrNull{it=="io.heckel.ntfy"}?:distributors[0]
    org.unifiedpush.android.connector.UnifiedPush.saveDistributor(context,selected)
    org.unifiedpush.android.connector.UnifiedPush.register(context,instance=PushSetup.instance(config),messageForDistributor="Waypoint location requests",vapid=key)
    sendStatus(1,Bundle().apply{putString("stage","Waiting for subscription callback")})
    val deadline=System.currentTimeMillis()+40000
    while(System.currentTimeMillis()<deadline) {
     if(context.getSharedPreferences("state",0).getString("pushStatus","")=="Internet push registered") {
      finish(Activity.RESULT_OK,Bundle().apply{putString("result","PASS: real distributor registered an encrypted Web Push subscription with the configured server.")});return
     }
     Thread.sleep(500)
    }
    error(context.getSharedPreferences("state",0).getString("pushStatus","Push registration timed out")!!)
   }
   startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
   Config.status(context,"Starting on-device foreground service check…")
   runOnMainSync {context.startForegroundService(Intent(context,LocateService::class.java).putExtra("manual",true))}
   val deadline=System.currentTimeMillis()+115000
   var status=""
   while(System.currentTimeMillis()<deadline) {
    status=context.getSharedPreferences("state",0).getString("status","")!!
    if(status.startsWith("Location sent successfully"))break
    Thread.sleep(500)
   }
   val success=status.startsWith("Location sent successfully")
   if(success)Thread.sleep(1000)
   finish(if(success)Activity.RESULT_OK else Activity.RESULT_CANCELED,Bundle().apply{putString("result",if(success)"PASS: foreground service uploaded a real location and completed." else status)})
  }catch(e:Exception){finish(Activity.RESULT_CANCELED,Bundle().apply{putString("result",e.message)})}
 }
}
