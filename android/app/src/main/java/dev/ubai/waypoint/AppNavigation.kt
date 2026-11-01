package dev.ubai.waypoint

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** One dock used on all three native pages, with identical sizing and selection states. */
internal class AppNavigation(
 activity:Activity,
 selected:Int,
 onSelect:(Int)->Unit
):LinearLayout(activity) {
 private fun dp(value:Int)=(value*resources.displayMetrics.density).toInt()
 init {
  orientation=VERTICAL
  background=GradientDrawable().apply{setColor(UiAppearance.surface(activity));cornerRadius=dp(36).toFloat();if(UiAppearance.preferences(activity).getBoolean("glass",false))setStroke(dp(1),UiAppearance.line(activity))}
  elevation=0f;clipToOutline=true
  val tabs=LinearLayout(activity).apply{orientation=HORIZONTAL;setPadding(dp(8),dp(6),dp(8),dp(6))}
  addView(tabs,LayoutParams(-1,dp(64)))
  for((index,item) in listOf("Devices" to R.drawable.ic_devices,"This phone" to R.drawable.ic_phone,"Account" to R.drawable.ic_account).withIndex()){
   val active=index==selected;val color=if(active)UiAppearance.accent(activity)else UiAppearance.muted(activity)
   val tab=LinearLayout(activity).apply{
    orientation=VERTICAL;gravity=Gravity.CENTER;isClickable=true;isFocusable=true;isSelected=active;contentDescription=item.first
    if(active)background=GradientDrawable().apply{setColor(UiAppearance.soft(activity));cornerRadius=dp(30).toFloat()}
    setOnClickListener{onSelect(index)}
   }
   tab.addView(ImageView(activity).apply{setImageDrawable(activity.getDrawable(item.second)!!.mutate().apply{setTint(color)});importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO},LayoutParams(dp(24),dp(24)))
   tab.addView(TextView(activity).apply{text=item.first;textSize=12f;setTextColor(color);if(active)setTypeface(null,Typeface.BOLD);importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO},LayoutParams(-2,-2).apply{topMargin=dp(4)})
   tabs.addView(tab,LayoutParams(0,-1,1f))
  }
 }
}
