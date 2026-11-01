package dev.ubai.waypoint

import android.graphics.*
import android.graphics.drawable.Drawable
import android.view.View
import android.webkit.WebView

/** A shared, low-resolution backdrop, refreshed only after visible map changes settle. */
internal class MapGlass(private val map:WebView,private val dark:Boolean) {
 private var bitmap:Bitmap?=null
 private var closed=false
 private val panels=mutableListOf<View>()
 private val capture=Runnable{capture()}
 fun surface(view:View,radius:Float):Drawable {
  panels.add(view)
  return object:Drawable(){
   private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
   override fun draw(canvas:Canvas){
    val rect=RectF(bounds);val clip=Path().apply{addRoundRect(rect,radius,radius,Path.Direction.CW)}
    val saved=canvas.save();canvas.clipPath(clip)
    val image=bitmap
    if(image!=null){
     val panelAt=IntArray(2);val mapAt=IntArray(2);view.getLocationInWindow(panelAt);map.getLocationInWindow(mapAt)
     val left=(mapAt[0]-panelAt[0]).toFloat();val top=(mapAt[1]-panelAt[1]).toFloat()
     paint.color=Color.WHITE;canvas.drawBitmap(image,null,RectF(left,top,left+map.width,top+map.height),paint)
    }
    paint.color=if(image==null){if(dark)Color.rgb(24,30,33)else Color.rgb(250,251,252)}else if(dark)Color.argb(210,17,24,29)else Color.argb(216,250,252,255)
    canvas.drawRect(rect,paint);canvas.restoreToCount(saved)
    paint.style=Paint.Style.STROKE;paint.strokeWidth=map.resources.displayMetrics.density
    paint.color=if(dark)Color.argb(50,255,255,255)else Color.argb(200,255,255,255)
    rect.inset(paint.strokeWidth/2,paint.strokeWidth/2);canvas.drawRoundRect(rect,radius,radius,paint);paint.style=Paint.Style.FILL
   }
   override fun getOutline(outline:Outline){outline.setRoundRect(bounds,radius)}
   override fun setAlpha(alpha:Int){}
   override fun setColorFilter(filter:ColorFilter?){}
   @Deprecated("Deprecated in Java") override fun getOpacity()=PixelFormat.TRANSLUCENT
  }
 }
 fun refresh(){if(!closed){map.removeCallbacks(capture);map.postDelayed(capture,180)}}
 private fun capture(){
  if(closed || !map.isShown || !map.hasWindowFocus() || map.windowVisibility!=View.VISIBLE || map.width==0 || map.height==0)return
  // Downsampling limits both the capture cost and blur work. No frame loop or timer.
  val width=(map.width/10).coerceAtLeast(1);val height=(map.height/10).coerceAtLeast(1)
  val next=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
  val canvas=Canvas(next);canvas.scale(width.toFloat()/map.width,height.toFloat()/map.height)
  try{map.draw(canvas)}catch(_:RuntimeException){next.recycle();return}
  val pixels=IntArray(width*height);next.getPixels(pixels,0,width,0,0,width,height)
  val temp=IntArray(pixels.size)
  // Separable box blur on the tiny snapshot; controls and labels are never blurred.
  repeat(2){
   for(y in 0 until height)for(x in 0 until width){var r=0;var g=0;var b=0
    for(offset in -2..2){val c=pixels[y*width+(x+offset).coerceIn(0,width-1)];r+=Color.red(c);g+=Color.green(c);b+=Color.blue(c)}
    temp[y*width+x]=Color.rgb(r/5,g/5,b/5)
   }
   for(y in 0 until height)for(x in 0 until width){var r=0;var g=0;var b=0
    for(offset in -2..2){val c=temp[(y+offset).coerceIn(0,height-1)*width+x];r+=Color.red(c);g+=Color.green(c);b+=Color.blue(c)}
    pixels[y*width+x]=Color.rgb(r/5,g/5,b/5)
   }
  }
  next.setPixels(pixels,0,width,0,0,width,height)
  bitmap=next;panels.forEach{it.invalidate()}
 }
 fun release(){closed=true;map.removeCallbacks(capture);bitmap=null;panels.clear()}
}
