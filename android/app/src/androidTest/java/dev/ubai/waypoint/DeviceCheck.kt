package dev.ubai.waypoint
import android.app.Instrumentation
import android.app.Activity
import android.app.ActivityManager
import org.json.JSONObject
import android.content.Intent
import android.os.Bundle
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
class DeviceCheck:Instrumentation() {
 private var mode="location"
 override fun onCreate(arguments:Bundle?) {super.onCreate(arguments);mode=arguments?.getString("mode")?:"location";start()}
 override fun onStart() {
  try {
   if(mode=="screenshots") {
    // Actual native screens with public sample coordinates. Never change enrollment or send commands.
    fun ui(action:()->Unit){var failure:Throwable?=null;runOnMainSync{try{action()}catch(error:Throwable){failure=error}};failure?.let{throw it}}
    fun views(view:android.view.View):List<android.view.View> = listOf(view)+if(view is android.view.ViewGroup)(0 until view.childCount).flatMap{views(view.getChildAt(it))}else emptyList()
    val preferences=UiAppearance.preferences(targetContext)
    val originalTheme=preferences.getString("theme",null);val hadGlass=preferences.contains("glass");val originalGlass=preferences.getBoolean("glass",false)
    var activity:Activity?=null;var preview:LocatorScreen?=null
    val time=java.time.Instant.now().minusSeconds(180).toString()
    fun report(lat:Double,lng:Double,battery:Int)=JSONObject().put("lat",lat).put("lng",lng).put("accuracy",24).put("battery",battery).put("charging",false).put("recorded_at",time).put("received_at",time)
    val phone=JSONObject().put("id","preview-phone").put("name","Personal phone").put("last_seen",time).put("ring_supported",1).put("revoked_at",JSONObject.NULL).put("report",report(52.5208,13.4095,78))
    val tablet=JSONObject().put("id","preview-tablet").put("name","Tablet").put("last_seen",time).put("ring_supported",1).put("revoked_at",JSONObject.NULL).put("report",report(52.5192,13.4062,91))
    fun fixture(id:String):JSONObject {val device=if(id=="preview-tablet")tablet else phone;return JSONObject().put("devices",org.json.JSONArray().put(phone).put(tablet)).put("device",device).put("report",device.getJSONObject("report")).put("commands",org.json.JSONArray()).put("ring",JSONObject.NULL)}
    fun tilesReady(){
     val end=android.os.SystemClock.elapsedRealtime()+20000
     while(android.os.SystemClock.elapsedRealtime()<end){
      val count=java.util.concurrent.CompletableFuture<String>()
      ui{preview!!.map.evaluateJavascript("document.querySelectorAll('img.leaflet-tile-loaded').length"){count.complete(it)}}
      if(count.get(3,java.util.concurrent.TimeUnit.SECONDS).toIntOrNull()?.let{it>0}==true){Thread.sleep(400);return}
      Thread.sleep(200)
     }
     error("Sample map tiles did not load")
    }
    fun capture(name:String){
     waitForIdleSync();Thread.sleep(250)
     val bitmap=uiAutomation.takeScreenshot()?:error("Screenshot unavailable")
     java.io.File(targetContext.externalCacheDir?:error("External screenshot cache unavailable"),name).outputStream().use{check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it))};bitmap.recycle()
    }
    try{
     activity=startActivitySync(Intent(targetContext,AccountActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
     val host=activity as AccountActivity
     ui{
      host.javaClass.getDeclaredMethod("cancelRequest").apply{isAccessible=true}.invoke(host)
      host.javaClass.getDeclaredField("accountPageVisible").apply{isAccessible=true}.setBoolean(host,true)
      host.javaClass.getDeclaredField("locator").apply{isAccessible=true}.let{field->(field.get(host) as? LocatorScreen)?.release();field.set(host,null)}
     }
     for(theme in listOf("dark","light")){
      ui{
       preview?.release();preferences.edit().putString("theme",theme).putBoolean("glass",false).commit();UiAppearance.apply(host)
       preview=LocatorScreen(host,select={id->preview!!.update(fixture(id),"preview-phone")},locate={},ring={},refresh={},thisPhone={},account={})
       host.setContentView(preview);preview!!.update(fixture("preview-phone"),"preview-phone")
      }
      tilesReady()
      ui{views(preview!!).first{it.contentDescription=="Devices"}.performClick()};Thread.sleep(250)
      ui{preview!!.map.center()};tilesReady();capture("readme-app-devices-$theme.png")
      ui{views(preview!!).first{it.contentDescription=="Show Personal phone"}.performClick()};Thread.sleep(250)
      ui{preview!!.map.center()};tilesReady();capture("readme-app-$theme.png")
     }
    }finally{
     ui{preview?.release();preferences.edit().apply{if(originalTheme==null)remove("theme")else putString("theme",originalTheme);if(hadGlass)putBoolean("glass",originalGlass)else remove("glass")}.commit();activity?.finish()}
    }
    finish(Activity.RESULT_OK,Bundle().apply{putString("result","PASS: four actual app screenshots saved in cache with public sample locations; enrollment and appearance preferences retained; no recovery commands sent.")});return
   }
   if(mode=="map-ui") {
    check(Config.account(targetContext)!=null){"Sign in before checking the map screen"}
    fun ui(action:()->Unit){if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper()){action();return};var failure:Throwable?=null;runOnMainSync{try{action()}catch(error:Throwable){failure=error}};failure?.let{throw it}}
    fun views(view:android.view.View):List<android.view.View> = listOf(view)+if(view is android.view.ViewGroup)(0 until view.childCount).flatMap{views(view.getChildAt(it))}else emptyList()
    var activity=startActivitySync(Intent(targetContext,AccountActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    fun screen():LocatorScreen?{var found:LocatorScreen?=null;ui{found=views(activity.window.decorView).filterIsInstance<LocatorScreen>().firstOrNull()};return found}
    fun mapState():JSONObject {
     val result=java.util.concurrent.CompletableFuture<String>()
     ui{screen()?.map?.evaluateJavascript("JSON.stringify({ready:typeof window.waypoint==='object',markers:document.querySelectorAll('.phone-pin').length,attribution:!!document.querySelector('.leaflet-control-attribution'),tiles:document.querySelectorAll('img.leaflet-tile-loaded').length})"){result.complete(it)}?:result.complete("null")}
     val raw=org.json.JSONTokener(result.get(5,java.util.concurrent.TimeUnit.SECONDS)).nextValue()
     return if(raw is String)JSONObject(raw)else JSONObject()
    }
    val end=android.os.SystemClock.elapsedRealtime()+30000
    var state=JSONObject()
    while(android.os.SystemClock.elapsedRealtime()<end){state=runCatching{mapState()}.getOrDefault(JSONObject());if(state.optInt("markers")>0)break;Thread.sleep(300)}
    check(state.optBoolean("ready") && state.optInt("markers")>0){"Map did not display a saved device location"}
    check(state.optBoolean("attribution")){"Map attribution missing"}
    sendStatus(1,Bundle().apply{putString("stage","PASS: in-app map rendered saved device marker; tiles loaded="+state.optInt("tiles"))})
    fun dockBounds(current:Activity):android.graphics.Rect {
     waitForIdleSync();Thread.sleep(150)
     var bounds=android.graphics.Rect()
     ui{
      val all=views(current.window.decorView);val dock=all.filterIsInstance<AppNavigation>().single()
      check(dock.getGlobalVisibleRect(bounds) && bounds.height()==dock.height){"Dock clipped"}
      for(name in listOf("Devices","This phone","Account")){
       val tab=all.first{it.contentDescription==name};val rect=android.graphics.Rect()
       check(tab.getGlobalVisibleRect(rect) && rect.height()==tab.height){"Dock tab clipped: $name"}
      }
     }
     return bounds
    }
    val initialDock=dockBounds(activity)
    val original=screen()!!
    // Open the floating device list, then select a row to expose recovery actions.
    ui{views(activity.window.decorView).first{it.contentDescription=="Devices"}.performClick()}
    Thread.sleep(300)
    ui{
     val row=views(activity.window.decorView).first{it.contentDescription?.startsWith("Show ")==true && it is android.widget.LinearLayout}
     val rect=android.graphics.Rect();check(row.getGlobalVisibleRect(rect) && rect.height()==row.height){"Device row clipped in expanded sheet"}
     row.performClick()
    }
    Thread.sleep(500)
    ui{
     val all=views(activity.window.decorView)
     for(labels in listOf(listOf("Locate","Locating…"),listOf("Play sound","Stop sound"))){
      val action=all.filterIsInstance<android.widget.Button>().first{it.text.toString() in labels}
      val rect=android.graphics.Rect();check(action.getGlobalVisibleRect(rect) && rect.height()==action.height){"Recovery action clipped in detail sheet"}
     }
     for(name in listOf("Devices","This phone","Account")){
      val tab=all.first{it.contentDescription==name};val rect=android.graphics.Rect()
      check(tab.getGlobalVisibleRect(rect) && rect.height()==tab.height){"Bottom tab clipped: "+name+" "+rect+" / "+tab.height}
     }
     all.first{it.contentDescription=="Refresh device locations"}.performClick()
    }
    Thread.sleep(1500)
    check(screen()===original){"Refresh replaced the map view"}
    ui{views(activity.window.decorView).first{it.contentDescription=="Account"}.performClick()}
    check(dockBounds(activity)==initialDock){"Account dock differs from map dock"}
    ui{check(views(activity.window.decorView).filterIsInstance<android.widget.TextView>().any{it.text=="Your server"});views(activity.window.decorView).first{it.contentDescription=="Devices"}.performClick()}
    Thread.sleep(1500);check(screen()!=null){"Devices tab did not restore the map"}
    val mainMonitor=addMonitor(MainActivity::class.java.name,null,false)
    ui{views(activity.window.decorView).first{it.contentDescription=="This phone"}.performClick()}
    val settings=mainMonitor.waitForActivityWithTimeout(5000)?:error("This phone tab did not open settings");removeMonitor(mainMonitor)
    check(dockBounds(settings)==initialDock){"This phone dock differs from map dock"}
    ui{check(views(settings.window.decorView).filterIsInstance<android.widget.TextView>().any{it.text=="Permissions"})}
    val accountMonitor=addMonitor(AccountActivity::class.java.name,null,false)
    ui{views(settings.window.decorView).first{it.contentDescription=="Devices"}.performClick()}
    activity=accountMonitor.waitForActivityWithTimeout(5000)?:error("Devices tab did not return to map");removeMonitor(accountMonitor)
    Thread.sleep(1500);check(screen()!=null)
    finish(Activity.RESULT_OK,Bundle().apply{putString("result","PASS: real saved location on embedded map; attribution; native locate/sound controls; map preserved on refresh; Devices, This phone and Account navigation with identical dock bounds; saved account retained. No location or ring commands sent.")});return
   }
   if(mode=="account-dns") {
    val config=Config.read(targetContext)?:error("No existing configuration")
    val host=java.net.URI(config.getString("url")).host
    sendStatus(1,Bundle().apply{putString("stage","Configured host: "+host)})
    for((label,resolver) in listOf("Android async" to AccountDns,"Android system" to okhttp3.Dns.SYSTEM)){
     val future=java.util.concurrent.CompletableFuture<List<java.net.InetAddress>>()
     Thread{try{future.complete(resolver.lookup(host))}catch(error:Throwable){future.completeExceptionally(error)}}.apply{isDaemon=true;start()}
     val began=android.os.SystemClock.elapsedRealtime()
     val outcome=runCatching{future.get(10,java.util.concurrent.TimeUnit.SECONDS)}
     sendStatus(1,Bundle().apply{putString("stage",label+": "+(if(outcome.isSuccess)"resolved "+outcome.getOrThrow().size+" addresses" else outcome.exceptionOrNull()?.javaClass?.simpleName+": "+outcome.exceptionOrNull()?.cause?.message)+" in "+(android.os.SystemClock.elapsedRealtime()-began)+"ms")})
    }
    finish(Activity.RESULT_OK,Bundle().apply{putString("result","DNS comparison completed; no login attempts")});return
   }
   if(mode=="account-login-fixes" || mode=="account-keyboard" || mode=="keyboard-preview") {
    val context=targetContext
    if(mode!="keyboard-preview")check(Config.account(context)==null){"Run this check while signed out of phone account access"}
    var networkOutcome="Network probes skipped"
    if(mode=="account-login-fixes"){
    val config=Config.read(context)?:error("No existing tracking configuration")
    val started=android.os.SystemClock.elapsedRealtime()
    val error=runCatching{AccountApi.call(config,"/api/mobile/devices").execute().use{AccountApi.result(it,"/api/mobile/devices")}}.exceptionOrNull()
    networkOutcome=if(error is AccountFailure && error.status==401)"Live server returned the expected 401 without credentials" else "Live server probe: "+error?.javaClass?.simpleName+": "+error?.message
    check(android.os.SystemClock.elapsedRealtime()-started<30000){"Live request exceeded deadline"}
    sendStatus(1,Bundle().apply{putString("stage",networkOutcome+"; no password sent")})
    val limited=okhttp3.Response.Builder().request(okhttp3.Request.Builder().url("https://example.test").build()).protocol(okhttp3.Protocol.HTTP_1_1).code(429).message("Too Many Requests").header("Retry-After","120").body("{\"error\":\"Limited\"}".toResponseBody("application/json".toMediaType())).build()
    val limitedError=limited.use{runCatching{AccountApi.result(it,"/api/mobile/sign-in",JSONObject())}.exceptionOrNull()}
    check(limitedError is AccountFailure && limitedError.status==429 && limitedError.message.orEmpty().contains("2 minutes"))
    val socket=java.net.ServerSocket(0,1,java.net.InetAddress.getByName("127.0.0.1"))
    val release=java.util.concurrent.CountDownLatch(1)
    val stalled=Thread{runCatching{socket.accept().use{release.await(35,java.util.concurrent.TimeUnit.SECONDS)}}}.apply{isDaemon=true;start()}
    val timeoutStart=android.os.SystemClock.elapsedRealtime()
    try{
     val timeout=runCatching{AccountApi.call(JSONObject().put("url","https://127.0.0.1:"+socket.localPort),"/stall").execute().use{AccountApi.result(it,"/stall")}}.exceptionOrNull()
     check(timeout is java.io.InterruptedIOException){"Stalled TLS handshake did not time out"}
     check(android.os.SystemClock.elapsedRealtime()-timeoutStart<30000){"Stalled network exceeded deadline"}
    }finally{release.countDown();socket.close();stalled.join(1000)}
    sendStatus(1,Bundle().apply{putString("stage","PASS: stalled TLS handshake terminates; rate limit is distinct from wrong password")})
    }
    check(!context.getSystemService(android.app.KeyguardManager::class.java).isDeviceLocked){"Unlock the phone before the keyboard check"}
    val activity=startActivitySync(Intent(context,AccountActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    waitForIdleSync();Thread.sleep(500)
    if(mode=="keyboard-preview")runOnMainSync{activity.javaClass.getDeclaredMethod("cancelRequest").apply{isAccessible=true}.invoke(activity);activity.javaClass.getDeclaredMethod("signIn").apply{isAccessible=true}.invoke(activity)}
    fun ui(action:()->Unit){if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper()){action();return};var failure:Throwable?=null;runOnMainSync{try{action()}catch(error:Throwable){failure=error}};failure?.let{throw it}}
    fun views(view:android.view.View):List<android.view.View> = listOf(view)+if(view is android.view.ViewGroup)(0 until view.childCount).flatMap{views(view.getChildAt(it))}else emptyList()
    lateinit var password:android.widget.EditText
    lateinit var submit:android.widget.Button
    ui{
     val all=views(activity.window.decorView)
     password=all.filterIsInstance<android.widget.EditText>().first{it.contentDescription=="Dashboard password"}
     check(!all.filterIsInstance<android.widget.EditText>().first{it.contentDescription=="Authenticator or recovery code"}.isShown){"Code field appeared before password accepted"}
     submit=all.filterIsInstance<android.widget.Button>().first{it.text=="Continue"}
     check(activity.hasWindowFocus()){"Account screen does not have window focus"}
     password.requestRectangleOnScreen(android.graphics.Rect(0,0,password.width,password.height),true)
    }
    val fieldRect=android.graphics.Rect();ui{check(password.getGlobalVisibleRect(fieldRect))}
    val focusTime=android.os.SystemClock.uptimeMillis()
    sendPointerSync(android.view.MotionEvent.obtain(focusTime,focusTime,android.view.MotionEvent.ACTION_DOWN,fieldRect.centerX().toFloat(),fieldRect.centerY().toFloat(),0))
    sendPointerSync(android.view.MotionEvent.obtain(focusTime,focusTime+80,android.view.MotionEvent.ACTION_UP,fieldRect.centerX().toFloat(),fieldRect.centerY().toFloat(),0))
    var until=android.os.SystemClock.elapsedRealtime()+5000
    while(android.os.SystemClock.elapsedRealtime()<until && activity.window.decorView.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime())!=true)Thread.sleep(100)
    check(activity.window.decorView.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime())==true){"Keyboard did not open for dismissal test"}
    Thread.sleep(700)
    ui{submit.requestRectangleOnScreen(android.graphics.Rect(0,0,submit.width,submit.height),true)}
    Thread.sleep(400)
    val rect=android.graphics.Rect()
    var fullyVisible=false;var geometry=""
    ui{
     fullyVisible=submit.getGlobalVisibleRect(rect) && rect.height()==submit.height
     val scroll=views(activity.window.decorView).filterIsInstance<android.widget.ScrollView>().first()
     geometry="button="+rect+", buttonHeight="+submit.height+", scrollHeight="+scroll.height+", bottomPadding="+scroll.paddingBottom+", scrollY="+scroll.scrollY+", IME="+activity.window.decorView.rootWindowInsets?.getInsets(android.view.WindowInsets.Type.ime())?.bottom
    }
    sendStatus(1,Bundle().apply{putString("stage",geometry)})
    check(fullyVisible){"Continue button could not scroll above keyboard"}
    val down=android.os.SystemClock.uptimeMillis()
    sendPointerSync(android.view.MotionEvent.obtain(down,down,android.view.MotionEvent.ACTION_DOWN,rect.centerX().toFloat(),rect.centerY().toFloat(),0))
    sendPointerSync(android.view.MotionEvent.obtain(down,down+80,android.view.MotionEvent.ACTION_UP,rect.centerX().toFloat(),rect.centerY().toFloat(),0))
    until=android.os.SystemClock.elapsedRealtime()+5000
    while(android.os.SystemClock.elapsedRealtime()<until && activity.window.decorView.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime())==true)Thread.sleep(100)
    ui{
     check(activity.window.decorView.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime())!=true){"Outside tap did not dismiss keyboard"}
     check(views(activity.window.decorView).filterIsInstance<android.widget.TextView>().any{it.text=="Enter your server password."}){"Outside tap swallowed the Continue action"}
     activity.finish()
    }
    finish(Activity.RESULT_OK,Bundle().apply{putString("result",(if(mode=="account-login-fixes")"PASS: stalled TLS deadline and rate-limit message; "else "PASS: ")+"code hidden initially; controls visible above IME; outside tap dismisses keyboard and still activates Continue. "+networkOutcome+". No login attempts made.")});return
   }
   if(mode=="ring-policy") {
    val now=100000L
    val valid=JSONObject().put("id","a".repeat(32)).put("action","start").put("created_at",now).put("expires_at",now+60000).put("duration",30).put("volume",75).put("vibrate",1)
    check(RingPolicy.valid(valid,now))
    for((key,value) in listOf("id" to "bad","action" to "erase","duration" to 999,"volume" to 101,"vibrate" to 2,"expires_at" to now))check(!RingPolicy.valid(JSONObject(valid.toString()).put(key,value),now))
    check(RingPolicy.valid(JSONObject(valid.toString()).put("action","stop").put("duration",0),now))
    finish(Activity.RESULT_OK,Bundle().apply{putString("result","PASS: bounded ring payloads and expired command rejection; no audio played")});return
   }
   if(mode=="traffic-policy") {
    val sent=1000000L
    check(!RecoveryTrafficPolicy.canCoalesce(sent+109999,sent,0))
    check(RecoveryTrafficPolicy.canCoalesce(sent+110000,sent,0))
    check(RecoveryTrafficPolicy.canCoalesce(sent+118000,sent,0))
    check(!RecoveryTrafficPolicy.canCoalesce(sent+118001,sent,0))
    check(!RecoveryTrafficPolicy.canCoalesce(sent+115000,sent,sent-100000))
    check(RecoveryTrafficPolicy.canCoalesce(sent+115000,sent,sent-485000))
    check(!RecoveryTrafficPolicy.canCoalesce(sent+115000,0,0))
    check(!RecoveryTrafficPolicy.canSkipPoll(sent,0))
    check(!RecoveryTrafficPolicy.canSkipPoll(sent-1,sent))
    check(RecoveryTrafficPolicy.canSkipPoll(sent+140000,sent))
    check(!RecoveryTrafficPolicy.canSkipPoll(sent+140001,sent))
    finish(Activity.RESULT_OK,Bundle().apply{putString("result","PASS: early window, cooldown, missing/stale/future queue confirmation boundaries")});return
   }
   if(mode=="location-selection") {
    val start=200_000_000_000L
    fun fix(accuracy:Float,elapsed:Long=start,time:Long=10000)=android.location.Location("test").apply{
     this.accuracy=accuracy;elapsedRealtimeNanos=elapsed;this.time=time
    }
    val selection=FreshFixSelection(start,9000)
    check(selection.selected()==null)
    selection.offer(fix(100f),start)
    check(!selection.precise.isDone){"Rough network fix ended acquisition early"}
    selection.offer(fix(35f,start+1_000_000_000L),start+1_000_000_000L)
    selection.offer(fix(90f,start+2_000_000_000L),start+2_000_000_000L)
    check(selection.selected()?.accuracy==35f){"Best fallback was replaced by a worse fix"}
    selection.offer(fix(3f,start-10_000_000_000L),start)
    selection.offer(fix(3f,time=8000),start)
    selection.offer(fix(3f,start+5_000_000_000L),start)
    selection.offer(fix(Float.NaN),start)
    check(!selection.precise.isDone && selection.selected()?.accuracy==35f){"Invalid or stale fix accepted"}
    selection.offer(fix(12f,start+3_000_000_000L),start+3_000_000_000L)
    check(selection.precise.isDone && selection.selected()?.accuracy==12f)
    selection.offer(fix(5f,start+4_000_000_000L),start+4_000_000_000L)
    check(selection.selected()?.accuracy==5f){"Newer, better precise fix was discarded"}
    finish(Activity.RESULT_OK,Bundle().apply{putString("result","PASS: rough-first fix waits; best fallback retained; stale/invalid fixes rejected; precise fix completes.")});return
   }
   sendStatus(1,Bundle().apply{putString("stage","Reading app configuration")})
   val context=targetContext
   val config=Config.read(context)?:error("Not provisioned")
   if(mode=="traffic-live") {
    check(ConnectionModes.current(context)=="builtin"){"This check requires the existing built-in mode"}
    startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    val deadline=android.os.SystemClock.elapsedRealtime()+30000
    while(android.os.SystemClock.elapsedRealtime()<deadline && InternetRuntime.emptyConfirmedAt==0L)Thread.sleep(100)
    check(InternetRuntime.emptyConfirmedAt>0L){"No explicit empty-queue origin ACK received"}
    val scheduler=context.getSystemService(android.app.job.JobScheduler::class.java)
    val before=InternetRuntime.skippedPolls
    try {
     val job=android.app.job.JobInfo.Builder(102,android.content.ComponentName(context,CheckJob::class.java)).setOverrideDeadline(0).build()
     check(scheduler.schedule(job)==android.app.job.JobScheduler.RESULT_SUCCESS)
     val end=android.os.SystemClock.elapsedRealtime()+20000
     while(android.os.SystemClock.elapsedRealtime()<end && InternetRuntime.skippedPolls==before)Thread.sleep(100)
     check(InternetRuntime.skippedPolls==before+1){"Scheduled job did not skip its redundant HTTP poll"}
    }finally{scheduler.cancel(102)}
    finish(Activity.RESULT_OK,Bundle().apply{putString("result","PASS: real origin confirmed empty queue; real scheduled CheckJob skipped HTTP; user polling preference unchanged")});return
   }
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
