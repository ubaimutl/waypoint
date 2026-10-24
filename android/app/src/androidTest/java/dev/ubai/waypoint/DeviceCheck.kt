package dev.ubai.waypoint
import android.app.Instrumentation
import android.app.Activity
import android.app.ActivityManager
import org.json.JSONObject
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
   if(mode=="connection-errors") {
    val original=JSONObject(config.toString())
    val originalMode=ConnectionModes.current(context)
    try {
     val invalid=JSONObject(original.toString()).put("cfId","bad\nheader").put("cfSecret","test-secret")
     check(runCatching{Config.validate(invalid)}.isFailure){"Invalid header characters accepted"}
     startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
     runOnMainSync{context.stopService(Intent(context,InternetService::class.java))}
     Thread.sleep(1200)
     Config.save(context,JSONObject(original.toString()).put("token","invalid-token-for-regression-test-0000000000000000000000"))
     ConnectionModes.set(context,"builtin")
     runOnMainSync{context.startForegroundService(Intent(context,InternetService::class.java))}
     val deadline=System.currentTimeMillis()+45000
     var rejected=false
     while(System.currentTimeMillis()<deadline){
      val state=context.getSharedPreferences("state",0).getString("internetStatus","").orEmpty()
      if(state.startsWith("Connection rejected")){rejected=true;break}
      Thread.sleep(250)
     }
     check(rejected){"Rejected authentication was not handled"}
     Thread.sleep(2000)
     check(context.getSystemService(ActivityManager::class.java).getRunningServices(100).none{it.service.className==InternetService::class.java.name}){"Rejected listener did not stop"}
     sendStatus(1,Bundle().apply{putString("stage","PASS: authentication rejection stopped the TLS listener without crashing the app")})
    }finally{
     runOnMainSync{context.stopService(Intent(context,InternetService::class.java))}
     Config.save(context,original);ConnectionModes.set(context,originalMode)
    }
    val actual=runCatching{Tracker.api(original,"/api/device/commands");"Authenticated server API reachable"}.getOrElse{it.message?:"Server check failed"}
    finish(Activity.RESULT_OK,Bundle().apply{putString("result","PASS: rejected-auth cleanup and invalid-header validation; original credentials restored. Current connection: "+actual)});return
   }
   sendStatus(1,Bundle().apply{putString("stage","Checking configured server")})
   Tracker.api(config,"/api/device/commands")
   if(mode=="builtin") {
    startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    runOnMainSync{ConnectionModes.startBuiltin(context)}
    val deadline=System.currentTimeMillis()+30000
    while(System.currentTimeMillis()<deadline){
     if(context.getSharedPreferences("state",0).getString("internetStatus","")!!.startsWith("Connected")){
      finish(Activity.RESULT_OK,Bundle().apply{putString("result","PASS: built-in authenticated connection established.")});return
     }
     Thread.sleep(500)
    }
    error("Built-in connection did not connect")
   }
   if(mode=="register-push") {
    config.put("pushEnabled",true);Config.save(context,config)
    ConnectionModes.set(context,"unifiedpush")
    context.stopService(Intent(context,InternetService::class.java))
    ConnectionModes.notifyServer(context,"unifiedpush")
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
