package dev.ubai.waypoint

import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** Compact floating navigation and a three-position native sheet over the map. */
internal class LocatorScreen(
 private val activity:Activity,
 private val select:(String)->Unit,
 private val locate:()->Unit,
 private val ring:()->Unit,
 private val refresh:()->Unit,
 private val thisPhone:()->Unit,
 private val account:()->Unit
):FrameLayout(activity) {
 private val dark=UiAppearance.dark(activity)
 private val green=UiAppearance.accent(activity)
 private val ink=UiAppearance.ink(activity)
 private val muted=UiAppearance.muted(activity)
 val map=DeviceMap(activity){id->snap(1);select(id)}
 private val glass=if(UiAppearance.preferences(activity).getBoolean("glass",false))MapGlass(map,dark)else null
 val statusView:TextView
 private val summary:TextView
 private val title:TextView
 private val subtitle:TextView
 private val battery:TextView
 private val location:TextView
 private val detail:TextView
 private val locateButton:Button
 private val ringButton:Button
 private val refreshButton:ImageButton
 private val sheet:LinearLayout
 private val detailContent:LinearLayout
 private val deviceList=LinearLayout(activity).apply{orientation=LinearLayout.VERTICAL}
 private var devices=JSONArray()
 private var selected=""
 private var listKey=""
 private var pending=false
 private var ringReady=false
 private var ringing=false
 private var busy=false
 private var position=0
 private var animator:ValueAnimator?=null
 private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
 private fun background(color:Int,radius:Int=20)=GradientDrawable().apply{setColor(color);cornerRadius=dp(radius).toFloat()}
 private fun surface(view:View,radius:Int){view.background=glass?.surface(view,dp(radius).toFloat())?:background(UiAppearance.surface(activity),radius);view.elevation=dp(5).toFloat();view.clipToOutline=true}
 private fun text(value:String,size:Int=14,bold:Boolean=false,color:Int=ink)=TextView(activity).apply{
  text=value;textSize=size.toFloat();setTextColor(color)
  if(bold)setTypeface(null,Typeface.BOLD)
 }
 private fun icon(resource:Int,color:Int=green)=activity.getDrawable(resource)!!.mutate().apply{setTint(color);setBounds(0,0,dp(22),dp(22))}
 private fun imageButton(resource:Int,description:String,action:()->Unit)=ImageButton(activity).apply{
  stateListAnimator=null;setImageResource(resource);imageTintList=android.content.res.ColorStateList.valueOf(ink);contentDescription=description
  background=background(Color.TRANSPARENT);setPadding(dp(13),dp(13),dp(13),dp(13));setOnClickListener{action();glass?.refresh()}
 }
 private fun action(label:String,resource:Int,primary:Boolean,action:()->Unit)=Button(activity).apply{
  text=label;isAllCaps=false;textSize=14f;minHeight=dp(48);minimumWidth=0;stateListAnimator=null;elevation=0f
  setTextColor(if(primary)Color.WHITE else green);background=background(if(primary)UiAppearance.primary(activity) else UiAppearance.soft(activity),16)
  compoundDrawablePadding=dp(6);setCompoundDrawables(icon(resource,if(primary)Color.WHITE else green),null,null,null)
  setPadding(dp(12),0,dp(12),0);setOnClickListener{action()}
 }
 init {
  setBackgroundColor(UiAppearance.page(activity));addView(map,LayoutParams(-1,-1))
  // The map has no app header. A single small control capsule stays out of its center.
  val tools=LinearLayout(activity).apply{orientation=LinearLayout.VERTICAL}
  surface(tools,28)
  addView(tools,LayoutParams(dp(52),-2,Gravity.TOP or Gravity.RIGHT).apply{topMargin=dp(12);rightMargin=dp(16)})
  tools.addView(imageButton(R.drawable.ic_all,"Show all devices"){map.showAll()},LinearLayout.LayoutParams(dp(52),dp(52)))
  tools.addView(View(activity).apply{setBackgroundColor(UiAppearance.line(activity))},LinearLayout.LayoutParams(dp(28),dp(1)).apply{gravity=Gravity.CENTER_HORIZONTAL})
  tools.addView(imageButton(R.drawable.ic_locate,"Center selected device"){map.center()},LinearLayout.LayoutParams(dp(52),dp(52)))
  sheet=LinearLayout(activity).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(12),dp(16),0)}
  surface(sheet,28)
  addView(sheet,LayoutParams(-1,0,Gravity.BOTTOM).apply{setMargins(dp(8),0,dp(8),dp(80))})
  val header=LinearLayout(activity).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
  summary=text("Devices",20,true).apply{maxLines=1;ellipsize=TextUtils.TruncateAt.END;isClickable=true;isFocusable=true;contentDescription="Open device list";setOnClickListener{snap(if(position==2)0 else 2)}}
  header.addView(summary,LinearLayout.LayoutParams(0,dp(44),1f).apply{summary.gravity=Gravity.CENTER_VERTICAL})
  refreshButton=imageButton(R.drawable.ic_refresh,"Refresh device locations",refresh);header.addView(refreshButton,LinearLayout.LayoutParams(dp(44),dp(44)))
  sheet.addView(header,LinearLayout.LayoutParams(-1,dp(44)))
  val scroll=ScrollView(activity).apply{isFillViewport=false;isVerticalScrollBarEnabled=false;overScrollMode=View.OVER_SCROLL_NEVER}
  sheet.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
  val body=LinearLayout(activity).apply{orientation=LinearLayout.VERTICAL;setPadding(0,dp(8),0,dp(16))};scroll.addView(body)
  detailContent=LinearLayout(activity).apply{orientation=LinearLayout.VERTICAL};body.addView(detailContent)
  val content=detailContent
  val identity=LinearLayout(activity).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL};content.addView(identity)
  identity.addView(ImageView(activity).apply{setImageDrawable(icon(R.drawable.ic_phone));setPadding(dp(10),dp(10),dp(10),dp(10));background=background(UiAppearance.soft(activity),24)},LinearLayout.LayoutParams(dp(44),dp(44)).apply{marginEnd=dp(12)})
  val names=LinearLayout(activity).apply{orientation=LinearLayout.VERTICAL};identity.addView(names,LinearLayout.LayoutParams(0,-2,1f))
  title=text("Loading your devices…",16,true).apply{maxLines=2;ellipsize=TextUtils.TruncateAt.END};names.addView(title)
  subtitle=text("Connecting to your server",12,false,muted).apply{setPadding(0,dp(4),0,0)};names.addView(subtitle)
  battery=text("",12,true).apply{compoundDrawablePadding=dp(4);setCompoundDrawables(icon(R.drawable.ic_battery),null,null,null)};identity.addView(battery,LinearLayout.LayoutParams(-2,-2).apply{marginStart=dp(8)})
  location=text("Fetching saved locations",14,false,muted);content.addView(location,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(12)})
  detail=text("",12,false,muted);content.addView(detail,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(4)})
  val actions=LinearLayout(activity).apply{orientation=LinearLayout.HORIZONTAL};content.addView(actions,LinearLayout.LayoutParams(-1,dp(48)).apply{topMargin=dp(16)})
  locateButton=action("Locate",R.drawable.ic_locate,true,locate);actions.addView(locateButton,LinearLayout.LayoutParams(0,-1,1f).apply{marginEnd=dp(4)})
  ringButton=action("Play sound",R.drawable.ic_bell,false,ring);actions.addView(ringButton,LinearLayout.LayoutParams(0,-1,1f).apply{marginStart=dp(4)})
  statusView=text("",12,false,muted).apply{accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE;visibility=View.GONE}
  statusView.addTextChangedListener(object:android.text.TextWatcher{override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){};override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){statusView.visibility=if(s.isNullOrEmpty())View.GONE else View.VISIBLE};override fun afterTextChanged(s:android.text.Editable?){}})
  content.addView(statusView,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(8)})
  body.addView(deviceList,LinearLayout.LayoutParams(-1,-2));deviceList.visibility=View.GONE
  val dock=AppNavigation(activity,0){index->when(index){0->snap(if(position==0)2 else 0);1->thisPhone();2->account()}}
  surface(dock,36);dock.elevation=0f
  addView(dock,LayoutParams(-1,dp(64),Gravity.BOTTOM).apply{setMargins(dp(24),0,dp(24),dp(8))})
  installDrag(summary)
  sheet.addOnLayoutChangeListener{_,_,_,_,_,_,_,_,_->map.bottomInset(bottomInset())}
  addOnLayoutChangeListener{_,_,_,_,_,_,_,_,_->if(animator?.isRunning!=true)setSheetHeight(stops()[position])}
  setOnApplyWindowInsetsListener{view,insets->val bars=insets.getInsets(WindowInsets.Type.systemBars());view.setPadding(bars.left,bars.top,bars.right,bars.bottom);insets}
  map.onSettled={glass?.refresh()}
  setBusy(true)
 }
 private fun bottomInset()=(sheet.height/resources.displayMetrics.density).toInt()+80
 private fun stops():IntArray {
  val available=(height-paddingTop-paddingBottom-dp(100)).coerceAtLeast(dp(64))
  return intArrayOf(0,dp(300).coerceAtMost(available),(available*0.52f).toInt().coerceAtLeast(dp(340)).coerceAtMost(available)).sorted().toIntArray()
 }
 private fun setSheetHeight(value:Int){val params=sheet.layoutParams;if(params.height!=value){params.height=value;sheet.layoutParams=params}}
 private fun snap(target:Int){
  position=target.coerceIn(0,2);detailContent.visibility=if(position==2)View.GONE else View.VISIBLE;deviceList.visibility=if(position==2)View.VISIBLE else View.GONE;animator?.cancel()
  animator=ValueAnimator.ofInt(sheet.height,stops()[position]).apply{duration=180;addUpdateListener{setSheetHeight(it.animatedValue as Int)};start()}
 }
 private fun installDrag(view:View){
  var down=0f;var start=0;var dragging=false
  view.setOnTouchListener{v,event->
   when(event.actionMasked){
    MotionEvent.ACTION_DOWN->{animator?.cancel();down=event.rawY;start=sheet.height;dragging=false;true}
    MotionEvent.ACTION_MOVE->{val delta=down-event.rawY;if(kotlin.math.abs(delta)>ViewConfiguration.get(activity).scaledTouchSlop)dragging=true;if(dragging){v.parent.requestDisallowInterceptTouchEvent(true);setSheetHeight((start+delta).toInt().coerceIn(stops().first(),stops().last()))};true}
    MotionEvent.ACTION_UP->{if(dragging){snap(stops().indices.minByOrNull{kotlin.math.abs(stops()[it]-sheet.height)}?:0)}else v.performClick();true}
    MotionEvent.ACTION_CANCEL->{snap(position);true}
    else->false
   }
  }
 }
 fun setBusy(value:Boolean){busy=value;refreshButton.isEnabled=!value;locateButton.isEnabled=!value && !pending && selected.isNotEmpty();ringButton.isEnabled=!value && ringReady}
 private fun age(value:String):String {
  val minutes=runCatching{((System.currentTimeMillis()-Instant.parse(value).toEpochMilli())/60000).coerceAtLeast(0)}.getOrDefault(0)
  return when{minutes<1->"just now";minutes<60->"$minutes min ago";minutes<1440->"${minutes/60} hr ago";else->"${minutes/1440} days ago"}
 }
 fun update(data:JSONObject,currentId:String){
  val incoming=data.getJSONArray("devices");devices=JSONArray();for(i in 0 until incoming.length()){val item=incoming.getJSONObject(i);if(item.isNull("revoked_at"))devices.put(item)};val device=data.optJSONObject("device");selected=device?.optString("id").orEmpty()
  title.text=device?.optString("name")?:"No devices yet"
  subtitle.text=if(device==null)"Sign in from another phone to add it" else (if(selected==currentId)"This phone · " else "")+if(device.isNull("last_seen"))"Not connected yet" else "Seen "+age(device.getString("last_seen"))
  val report=data.optJSONObject("report")
  battery.visibility=if(report==null)View.GONE else View.VISIBLE
  battery.text=report?.let{"${it.getInt("battery")}%"}?:""
  location.text=if(report==null)"No location received yet"else "Location updated "+age(report.getString("recorded_at"))
  detail.text=if(report==null)"Tap Locate to request this phone’s location." else "Accurate to ${report.getDouble("accuracy").toInt()} m"
  val commands=data.getJSONArray("commands");pending=(0 until commands.length()).any{commands.getJSONObject(it).optString("status")=="queued"}
  locateButton.text=if(pending)"Locating…" else "Locate"
  val ring=data.optJSONObject("ring");val status=ring?.optString("status").orEmpty()
  ringing=status in listOf("queued","playing","unconfirmed");ringReady=device?.optInt("ring_supported")==1
  ringButton.text=if(ringing)"Stop sound"else "Play sound"
  statusView.setTextColor(muted)
  statusView.text=when{pending->"Waiting for this phone to return a fresh location.";status=="queued"->if(ring?.optString("action")=="stop")"Stop requested. Waiting for the phone." else "Sound requested. Waiting for the phone.";status=="playing"->"Phone confirmed sound is playing.";status=="failed"->"Sound failed: "+ring?.optString("error");status=="expired"->"Sound request expired without confirmation.";status=="unconfirmed"->"Sound should have ended; stop is not yet confirmed.";!ringReady && device!=null->"Update and connect this phone’s app to enable sound.";else->""}
  summary.text="Devices"
  val key=devices.toString()+selected
  if(listKey!=key){
   deviceList.removeAllViews()
   if(devices.length()==0)deviceList.addView(text("Sign in to this server on another phone to add it.",14,false,muted))
   for(i in 0 until devices.length()){
    val item=devices.getJSONObject(i);val id=item.getString("id");val reportItem=item.optJSONObject("report")
    val row=LinearLayout(activity).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(8),dp(12),dp(8),dp(12));minimumHeight=dp(76);background=background(if(id==selected)UiAppearance.soft(activity)else Color.TRANSPARENT,16);isClickable=true;isFocusable=true;contentDescription="Show "+item.getString("name");setOnClickListener{snap(1);select(id)}}
    row.addView(ImageView(activity).apply{setImageDrawable(icon(R.drawable.ic_phone,if(id==selected)green else muted));background=background(UiAppearance.soft(activity),24);setPadding(dp(12),dp(12),dp(12),dp(12))},LinearLayout.LayoutParams(dp(48),dp(48)).apply{marginEnd=dp(12)})
    val labels=LinearLayout(activity).apply{orientation=LinearLayout.VERTICAL}
    row.addView(labels,LinearLayout.LayoutParams(0,-2,1f))
    labels.addView(text(item.getString("name"),16,true).apply{maxLines=2;ellipsize=TextUtils.TruncateAt.END})
    labels.addView(text(if(reportItem==null)"No location found"else "Updated "+age(reportItem.getString("recorded_at")),12,false,muted))
    if(reportItem!=null)row.addView(text("${reportItem.getInt("battery")}%",12,false,muted),LinearLayout.LayoutParams(-2,-2).apply{marginStart=dp(8)})
    deviceList.addView(row,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(4)})
   }
   listKey=key
  }
  map.update(JSONObject().put("devices",devices).put("selected",selected).put("bottom",bottomInset()).put("dark",dark))
  setBusy(busy)
 }
 fun soundActive()=ringing
 fun release(){animator?.cancel();glass?.release();map.onSettled=null;map.release()}
}
