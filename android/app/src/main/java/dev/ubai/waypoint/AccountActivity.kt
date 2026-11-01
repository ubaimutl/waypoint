package dev.ubai.waypoint

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.*
import android.text.InputType
import android.view.*
import android.widget.*
import org.json.JSONObject
import java.net.URLEncoder
import java.text.DateFormat
import java.time.Instant
import java.util.Date
import java.util.concurrent.Executors


/** Account traffic exists only while this screen is visible or the user performs an action. */
class AccountActivity:Activity() {
 private val green get()=UiAppearance.accent(this)
 private val ink get()=UiAppearance.ink(this)
 private val muted get()=UiAppearance.muted(this)
 private val handler=Handler(Looper.getMainLooper())
 private val worker=Executors.newSingleThreadExecutor()
 private lateinit var root:LinearLayout
 private lateinit var footer:LinearLayout
 private var locator:LocatorScreen?=null
 private var accountPageVisible=false
 private lateinit var message:TextView
 private var selected:String?=null
 private var visible=false
 private var loading=false
 private var generation=0
 @Volatile private var activeCall:okhttp3.Call?=null
 private var activeTask:java.util.concurrent.Future<*>?=null
 private var deadline:Runnable?=null
 private var cancelButton:Button?=null
 private var durationChoice=1
 private var volumeChoice=2
 private var vibrateChoice=true
 private val refresh=object:Runnable {override fun run(){if(visible && account()!=null){if(!accountPageVisible)load();handler.postDelayed(this,15000)}}}
 private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
 private fun account()=runCatching{Config.account(this)}.getOrNull()
 private fun tracking()=runCatching{Config.read(this)}.getOrNull()
 private fun shape(color:Int)=GradientDrawable().apply{setColor(color);cornerRadius=dp(20).toFloat()}
 override fun onCreate(state:Bundle?){UiAppearance.apply(this);super.onCreate(state);window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);selected=state?.getString("selected");
  Scheduling.apply(this)
  if(ConnectionModes.current(this)=="builtin")runCatching{startForegroundService(Intent(this,InternetService::class.java).putExtra("ensure",true))}
  if(account()==null)signIn() else if(intent.getBooleanExtra("openAccount",false))showAccount() else{showLocator();load()}
  if(Build.VERSION.SDK_INT>=33)onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT){if(accountPageVisible){showLocator();load()}else finish()}}
 override fun onSaveInstanceState(state:Bundle){super.onSaveInstanceState(state);state.putString("selected",selected)}
 override fun onResume(){super.onResume();locator?.map?.onResume();visible=true;handler.removeCallbacks(refresh);handler.postDelayed(refresh,15000)}
 override fun onPause(){locator?.map?.onPause();visible=false;handler.removeCallbacks(refresh);super.onPause()}
 override fun onDestroy(){locator?.release();locator=null;cancelRequest();worker.shutdownNow();super.onDestroy()}
 private fun hideKeyboard(){
  currentFocus?.clearFocus()
  if(::root.isInitialized)root.requestFocus()
  window.insetsController?.hide(WindowInsets.Type.ime())
  getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(window.decorView.windowToken,0)
 }
 private var touchX=0f
 private var touchY=0f
 override fun dispatchTouchEvent(event:MotionEvent):Boolean {
  if(event.action==MotionEvent.ACTION_DOWN){touchX=event.rawX;touchY=event.rawY}
  val field=currentFocus as? EditText
  val bounds=android.graphics.Rect();field?.getGlobalVisibleRect(bounds)
  val slop=ViewConfiguration.get(this).scaledTouchSlop
  val outsideTap=event.action==MotionEvent.ACTION_UP && field!=null && !bounds.contains(event.rawX.toInt(),event.rawY.toInt()) && kotlin.math.abs(event.rawX-touchX)<=slop && kotlin.math.abs(event.rawY-touchY)<=slop
  val handled=super.dispatchTouchEvent(event)
  if(outsideTap && currentFocus===field)hideKeyboard()
  return handled
 }
 private fun cancelRequest(){
  generation++;deadline?.let{handler.removeCallbacks(it)};deadline=null
  activeCall?.cancel();activeTask?.cancel(true);activeTask=null;loading=false
  cancelButton?.visibility=View.GONE;locator?.setBusy(false)
 }
 private fun page(title:String,description:String,showBack:Boolean=true){
  locator?.release();locator=null;cancelButton=null
  val frame=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(UiAppearance.page(this@AccountActivity))}
  val scroll=ScrollView(this).apply{isFillViewport=true}
  root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;isFocusable=true;isFocusableInTouchMode=true;setPadding(dp(20),dp(24),dp(20),dp(24))};scroll.addView(root)
  frame.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
  footer=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(24),dp(4),dp(24),dp(16));visibility=View.GONE}
  frame.addView(footer,LinearLayout.LayoutParams(-1,-2))
  frame.setOnApplyWindowInsetsListener{v,i->val bars=i.getInsets(WindowInsets.Type.systemBars());val ime=i.getInsets(WindowInsets.Type.ime());v.setPadding(bars.left,bars.top,bars.right,maxOf(bars.bottom,ime.bottom));i}
  setContentView(frame)
  if(showBack)button(root,if(account()!=null)"Back to map"else "Back to this phone"){}.setOnClickListener{cancelRequest();hideKeyboard();if(account()!=null){showLocator();load()}else finish()}
  label(root,title,28,true).setPadding(0,0,0,0);label(root,description,14).setPadding(0,dp(8),0,dp(6))
  message=label(root,"",14).apply{visibility=View.GONE;setTextColor(if(UiAppearance.dark(this@AccountActivity))Color.rgb(255,150,139)else Color.rgb(155,54,45));accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE}
  message.addTextChangedListener(object:android.text.TextWatcher{
   override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
   override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){message.visibility=if(s.isNullOrEmpty())View.GONE else View.VISIBLE}
   override fun afterTextChanged(s:android.text.Editable?){}
  })
 }
 private fun label(parent:LinearLayout,value:String,size:Int=15,bold:Boolean=false)=TextView(this).apply{
  text=value;textSize=size.toFloat();setTextColor(if(bold)ink else muted);setPadding(0,dp(8),0,dp(8));setLineSpacing(dp(3).toFloat(),1f);if(bold)setTypeface(null,Typeface.BOLD);parent.addView(this)
 }
 private fun button(parent:LinearLayout,title:String,primary:Boolean=false,action:()->Unit)=Button(this).apply{
  text=title;isAllCaps=false;textSize=15f;stateListAnimator=null;elevation=0f;setTextColor(if(primary)Color.WHITE else green);background=shape(if(primary)UiAppearance.primary(this@AccountActivity) else UiAppearance.soft(this@AccountActivity));setOnClickListener{if(!loading)action()};parent.addView(this,LinearLayout.LayoutParams(-1,dp(52)).apply{topMargin=dp(10)})
 }
 private fun card():LinearLayout=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(12),dp(16),dp(16));background=shape(UiAppearance.surface(this@AccountActivity));root.addView(this,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(14)})}
 private fun field(parent:LinearLayout,title:String,value:String="",secret:Boolean=false):EditText{
  label(parent,title,14,true)
  return EditText(this).apply{contentDescription=title;setText(value);setSingleLine(true);textSize=16f;inputType=InputType.TYPE_CLASS_TEXT or if(secret)InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_VARIATION_NORMAL;isSaveEnabled=!secret;parent.addView(this,LinearLayout.LayoutParams(-1,dp(52)))}
 }
 private fun request(config:JSONObject,path:String,data:JSONObject?=null):JSONObject {
  if(Thread.currentThread().isInterrupted)throw java.io.InterruptedIOException("Request cancelled")
  val call=AccountApi.call(config,path,data)
  activeCall=call
  if(Thread.currentThread().isInterrupted){call.cancel();activeCall=null;throw java.io.InterruptedIOException("Request cancelled")}
  try{return call.execute().use{AccountApi.result(it,path,data)}}finally{if(activeCall===call)activeCall=null}
 }
 private fun job(config:JSONObject,action:()->JSONObject,done:(JSONObject)->Unit){
  if(loading)return
  loading=true;locator?.setBusy(true);val version=++generation
  cancelButton?.visibility=View.VISIBLE
  val timer=Runnable{
   if(loading && version==generation && !isFinishing && !isDestroyed){
    cancelRequest();message.setTextColor(if(UiAppearance.dark(this@AccountActivity))Color.rgb(255,150,139)else Color.rgb(155,54,45));message.text="The server did not respond in time. Check your connection and try again."
   }
  }
  deadline=timer;handler.postDelayed(timer,30000)
  activeTask=worker.submit{
   val result=runCatching(action)
   handler.post{
    if(isFinishing || isDestroyed || version!=generation)return@post
    deadline?.let{handler.removeCallbacks(it)};deadline=null;activeTask=null;loading=false;cancelButton?.visibility=View.GONE;locator?.setBusy(false)
    result.fold(onSuccess={value->runCatching{done(value)}.onFailure{message.text=it.message?:"Could not save account settings."}},onFailure={error->
     if(error is AccountFailure && error.status==401 && config.optString("access_token").isNotEmpty()){
      Config.saveAccount(this,null);selected=null;signIn();message.text="Account access was revoked or expired. This phone’s tracking is still configured. Sign in again to view your devices."
     }else if(error is AccountFailure && error.status==404 && selected!=null){selected=null;load()}else{if(!::message.isInitialized)page("Your devices",config.optString("url"));message.setTextColor(if(UiAppearance.dark(this@AccountActivity))Color.rgb(255,150,139)else Color.rgb(155,54,45));message.text=if(error is java.io.InterruptedIOException)"The server did not respond in time. Check your connection and try again." else error.message?:"Could not connect. Try again."}
    })
   }
  }
 }
 private fun signIn(){
  accountPageVisible=false;selected=null;val saved=tracking()?:JSONObject()
  page("Sign in to your server","Use your dashboard password. This phone will appear alongside your other devices.")
  val form=card()
  val url=field(form,"Server address",saved.optString("url")).apply{inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI}
  val name=field(form,"This device’s name",getSharedPreferences("state",0).getString("deviceName",Build.MODEL)?:Build.MODEL)
  val password=field(form,"Dashboard password",secret=true)
  val codeFields=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;visibility=View.GONE};form.addView(codeFields)
  val code=field(codeFields,"Authenticator or recovery code",secret=true).apply{inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD;isSaveEnabled=false}
  var needsCode=false
  val proxy=CheckBox(this).apply{text="Use an access proxy";isChecked=saved.optString("cfId").isNotEmpty()};form.addView(proxy)
  val proxyFields=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};form.addView(proxyFields)
  val cfId=field(proxyFields,"Service token ID",saved.optString("cfId"),true)
  val cfSecret=field(proxyFields,"Service token secret",saved.optString("cfSecret"),true)
  proxyFields.visibility=if(proxy.isChecked)View.VISIBLE else View.GONE
  proxy.setOnCheckedChangeListener{_,enabled->proxyFields.visibility=if(enabled)View.VISIBLE else View.GONE}
  footer.visibility=View.VISIBLE
  val submit=button(footer,"Continue",true){}
  cancelButton=button(footer,"Cancel request"){}.apply{visibility=View.GONE;setOnClickListener{cancelRequest();message.text="Request cancelled."}}
  val changePassword=button(footer,"Use a different password"){
   needsCode=false;code.text.clear();codeFields.visibility=View.GONE;password.isEnabled=true;url.isEnabled=true;name.isEnabled=true;proxy.isEnabled=true;cfId.isEnabled=true;cfSecret.isEnabled=true
   submit.text="Continue";message.text="";password.requestFocus();footer.findViewWithTag<View>("change-password")?.visibility=View.GONE
  }.apply{visibility=View.GONE;tag="change-password"}
  fun submit(){
   if(loading)return
   try{
    val address=url.text.toString().trim().trimEnd('/')
    val config=JSONObject().put("url",address).put("token","x".repeat(32)).put("cfId",if(proxy.isChecked)cfId.text.toString().trim() else "").put("cfSecret",if(proxy.isChecked)cfSecret.text.toString().trim() else "")
    Config.validate(config)
    require(name.text.toString().trim().isNotEmpty()){ "Enter a device name." }
    require(password.text.isNotEmpty()){ "Enter your server password." }
    if(needsCode)require(code.text.toString().trim().isNotEmpty()){ "Enter your authenticator or recovery code." }
    val enrollment=getSharedPreferences("enrollment",0)
    val installation=enrollment.getString("id",null)?:java.util.UUID.randomUUID().toString().replace("-","").also{check(enrollment.edit().putString("id",it).commit())}
    val input=JSONObject().put("installation_id",installation).put("name",name.text.toString().trim()).put("password",password.text.toString()).put("code",if(needsCode)code.text.toString().trim() else "")
    if(saved.optString("url")==address && saved.has("token"))input.put("device_token",saved.getString("token"))
    hideKeyboard()
    message.setTextColor(if(UiAppearance.dark(this@AccountActivity))Color.rgb(255,150,139)else Color.rgb(155,54,45));message.text=if(needsCode)"Verifying code…" else "Checking password…"
    job(config,{request(config,"/api/mobile/sign-in",input)}){result->
     if(result.optBoolean("requiresTwoFactor")){
      needsCode=true;codeFields.visibility=View.VISIBLE;changePassword.visibility=View.VISIBLE
      password.isEnabled=false;url.isEnabled=false;name.isEnabled=false;proxy.isEnabled=false;cfId.isEnabled=false;cfSecret.isEnabled=false
      submit.text="Sign in";message.setTextColor(green);message.text="Password accepted. Enter your authenticator or recovery code.";code.requestFocus()
     }
     else{
      password.text.clear();code.text.clear()
      val device=result.getJSONObject("device")
      val next=if(saved.optString("url")==address && saved.optString("token")==device.getString("token"))JSONObject(saved.toString()) else JSONObject()
      next.put("url",address).put("token",device.getString("token")).put("cfId",config.optString("cfId")).put("cfSecret",config.optString("cfSecret"))
      val replaced=saved.has("token") && (saved.optString("url")!=address || saved.optString("token")!=next.getString("token"))
      if(replaced)ConnectionModes.stop(this)
      Config.save(this,next)
      Config.saveAccount(this,JSONObject().put("url",address).put("access_token",result.getString("access_token")).put("device_id",device.getString("id")).put("cfId",config.optString("cfId")).put("cfSecret",config.optString("cfSecret")))
      // An established connection keeps its mode. New enrollments use the existing built-in listener.
      if(!saved.has("token") || replaced){
       if(Build.VERSION.SDK_INT<33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED)runCatching{ConnectionModes.startBuiltin(this)}
       else requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),21)
      }
      Config.status(this,"Signed in. Allow location and background location in this phone’s settings to enable recovery.")
      load()
     }
    }
   }catch(error:Exception){message.text=error.message}
  }
  for(editor in listOf(password,code)){
   editor.imeOptions=android.view.inputmethod.EditorInfo.IME_ACTION_DONE
   editor.setOnEditorActionListener{_,action,_->if(action==android.view.inputmethod.EditorInfo.IME_ACTION_DONE){hideKeyboard();submit();true}else false}
  }
  submit.setOnClickListener{
   if(loading)return@setOnClickListener
   if(saved.has("token") && url.text.toString().trim().trimEnd('/')!=saved.optString("url"))AlertDialog.Builder(this).setTitle("Change tracking server?").setMessage("Signing in will move this phone’s tracking connection to the new server.").setNegativeButton("Cancel",null).setPositiveButton("Change server"){_,_->submit()}.show() else submit()
  }
 }
 override fun onRequestPermissionsResult(requestCode:Int,permissions:Array<out String>,grantResults:IntArray){
  super.onRequestPermissionsResult(requestCode,permissions,grantResults)
  if(requestCode==21 && grantResults.firstOrNull()==PackageManager.PERMISSION_GRANTED)runCatching{ConnectionModes.startBuiltin(this)}
 }
 private fun time(value:String)=runCatching{DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(Date(Instant.parse(value).toEpochMilli()))}.getOrDefault("Unknown")
 private fun showLocator(){
  accountPageVisible=false;cancelButton=null
  if(locator==null){
   locator=LocatorScreen(this,
    select={id->cancelRequest();selected=id;load()},
    locate={account()?.let{config->selected?.let{id->locator?.map?.followNextLocation();command(config,"locate",JSONObject().put("device_id",id))}}},
    ring={if(locator?.soundActive()==true)account()?.let{config->selected?.let{id->command(config,"ring",JSONObject().put("device_id",id).put("action","stop"))}}else ringDialog()},
    refresh={load()},
    thisPhone={startActivity(Intent(this,MainActivity::class.java).putExtra("settings",true));finish()},
    account={cancelRequest();showAccount()})
   setContentView(locator)
  }
  message=locator!!.statusView
 }
 private fun load(){
  val config=account()?:return signIn()
  if(loading || accountPageVisible)return
  showLocator()
  val path="/api/mobile/dashboard"+(selected?.let{"?device="+URLEncoder.encode(it,"UTF-8")}?:"")
  job(config,{request(config,path)}){data->
   selected=data.optJSONObject("device")?.optString("id")
   locator?.update(data,config.optString("device_id"))
  }
 }
 private fun showAccount(){
  val config=account()?:return signIn()
  page("Account","Your server and access to your devices.",showBack=false);accountPageVisible=true
  val server=card();label(server,"Your server",20,true);label(server,config.getString("url"),15)
  val appearance=card();label(appearance,"Appearance",20,true)
  val choices=listOf("System", "Light", "Dark")
  val themeNames=listOf("system", "light", "dark")
  val preferences=UiAppearance.preferences(this)
  val theme=Spinner(this).apply{contentDescription="Color theme";adapter=ArrayAdapter(this@AccountActivity,android.R.layout.simple_spinner_dropdown_item,choices);setSelection(themeNames.indexOf(preferences.getString("theme","system")).coerceAtLeast(0))}
  appearance.addView(theme,LinearLayout.LayoutParams(-1,dp(52)))
  theme.onItemSelectedListener=object:AdapterView.OnItemSelectedListener{
   override fun onNothingSelected(parent:AdapterView<*>?){}
   override fun onItemSelected(parent:AdapterView<*>?,view:View?,position:Int,id:Long){
    if(preferences.getString("theme","system")!=themeNames[position]){preferences.edit().putString("theme",themeNames[position]).apply();UiAppearance.apply(this@AccountActivity);showAccount()}
   }
  }
  val glass=Switch(this).apply{text="Liquid glass";textSize=15f;setTextColor(ink);minHeight=dp(52);isChecked=preferences.getBoolean("glass",false);setOnCheckedChangeListener{_,checked->preferences.edit().putBoolean("glass",checked).apply()}}
  appearance.addView(glass,LinearLayout.LayoutParams(-1,-2))
  label(appearance,"Blur the map behind floating panels. Off uses solid surfaces.",14)
  val access=card();label(access,"Device access",20,true);label(access,"This phone can view locations and ring your devices. Manage devices and revoke access from your dashboard.",14)
  button(access,"Open dashboard"){startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(config.getString("url"))))}
  button(access,"Sign out"){
   AlertDialog.Builder(this).setTitle("Sign out of account?").setMessage("This phone will stay registered for tracking. You will need to sign in again to view your other devices.").setNegativeButton("Cancel",null).setPositiveButton("Sign out"){_,_->
    job(config,{request(config,"/api/mobile/sign-out",JSONObject())}){Config.saveAccount(this,null);accountPageVisible=false;selected=null;signIn()}
   }.show()
  }
  footer.visibility=View.VISIBLE;footer.setPadding(dp(24),0,dp(24),dp(8))
  val navigation=AppNavigation(this,2){index->when(index){0->{cancelRequest();showLocator();load()};1->{startActivity(Intent(this@AccountActivity,MainActivity::class.java).putExtra("settings",true));finish()}}}
  footer.addView(navigation,LinearLayout.LayoutParams(-1,dp(64)))
 }

 private fun ringDialog(){
  val config=account()?:return;val id=selected?:return
  val content=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(24),dp(8),dp(24),dp(8))}
  val durations=listOf(15,30,60,120);val levels=listOf(25,50,75,100)
  label(content,"Duration",14,true)
  val duration=Spinner(this).apply{adapter=ArrayAdapter(this@AccountActivity,android.R.layout.simple_spinner_dropdown_item,durations.map{"$it seconds"});setSelection(durationChoice)};content.addView(duration,LinearLayout.LayoutParams(-1,dp(52)))
  label(content,"Sound level",14,true)
  val volume=Spinner(this).apply{adapter=ArrayAdapter(this@AccountActivity,android.R.layout.simple_spinner_dropdown_item,levels.map{"$it%"});setSelection(volumeChoice)};content.addView(volume,LinearLayout.LayoutParams(-1,dp(52)))
  val vibrate=CheckBox(this).apply{text="Vibrate too";isChecked=vibrateChoice;minHeight=dp(48)};content.addView(vibrate)
  label(content,"Uses the phone’s alarm volume. You can stop it here or from its notification.",12)
  AlertDialog.Builder(this).setTitle("Play a sound").setView(content).setNegativeButton("Cancel",null).setPositiveButton("Play sound"){_,_->
   durationChoice=duration.selectedItemPosition;volumeChoice=volume.selectedItemPosition;vibrateChoice=vibrate.isChecked
   command(config,"ring",JSONObject().put("device_id",id).put("action","start").put("duration",durations[durationChoice]).put("volume",levels[volumeChoice]).put("vibrate",vibrateChoice))
  }.show()
 }
 private fun command(config:JSONObject,action:String,input:JSONObject){
  job(config,{request(config,"/api/mobile/$action",input)}){load();Toast.makeText(this,"Request sent. Waiting for the phone.",Toast.LENGTH_SHORT).show()}
 }
}
