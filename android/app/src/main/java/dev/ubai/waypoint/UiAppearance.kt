package dev.ubai.waypoint

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.view.WindowInsetsController

/** Presentation preferences only; independent of tracking and connection configuration. */
internal object UiAppearance {
 fun preferences(context:Context)=context.getSharedPreferences("appearance",Context.MODE_PRIVATE)
 fun dark(context:Context)=when(preferences(context).getString("theme","system")){
  "dark"->true;"light"->false
  else->context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
 }
 fun apply(activity:Activity){
  val dark=dark(activity)
  activity.setTheme(if(dark)R.style.AppThemeDark else R.style.AppTheme)
  val flags=WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
  activity.window.decorView.post { activity.window.decorView.windowInsetsController?.setSystemBarsAppearance(if(dark)0 else flags,flags) }
  @Suppress("DEPRECATION")
  activity.window.navigationBarColor=page(activity)
  @Suppress("DEPRECATION")
  activity.window.statusBarColor=page(activity)
 }
 fun surface(context:Context)=if(dark(context))Color.rgb(24,30,33)else Color.rgb(250,251,252)
 fun page(context:Context)=if(dark(context))Color.rgb(15,21,24)else Color.rgb(241,244,247)
 fun ink(context:Context)=if(dark(context))Color.rgb(242,245,248)else Color.rgb(24,32,41)
 fun muted(context:Context)=if(dark(context))Color.rgb(166,178,187)else Color.rgb(89,105,118)
 fun accent(context:Context)=if(dark(context))Color.rgb(132,211,165)else Color.rgb(43,93,73)
 fun primary(context:Context)=Color.rgb(43,93,73)
 fun soft(context:Context)=if(dark(context))Color.rgb(34,55,45)else Color.rgb(237,244,237)
 fun line(context:Context)=if(dark(context))Color.rgb(60,70,79)else Color.rgb(217,224,231)
}
