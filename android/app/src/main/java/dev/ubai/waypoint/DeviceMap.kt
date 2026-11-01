package dev.ubai.waypoint

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.webkit.*
import org.json.JSONObject

/** Bundled map renderer; no device credentials, location permissions or JS bridge are exposed. */
internal class DeviceMap(private val activity:Activity,private val select:(String)->Unit):WebView(activity) {
 var onSettled:(()->Unit)?=null
 private var ready=false
 private var state:JSONObject?=null
 init {
  settings.javaScriptEnabled=true
  settings.allowFileAccess=false;settings.allowContentAccess=false
  settings.javaScriptCanOpenWindowsAutomatically=false;settings.setSupportMultipleWindows(false)
  settings.setGeolocationEnabled(false);settings.mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW
  settings.cacheMode=WebSettings.LOAD_DEFAULT
  settings.userAgentString=settings.userAgentString+" Waypoint/1.2.0 (+https://github.com/ubaimutl/waypoint)"
  webChromeClient=object:WebChromeClient(){
   override fun onConsoleMessage(message:ConsoleMessage):Boolean {
    if(message.message()=="waypoint-map-settled"){onSettled?.invoke();return true}
    return false
   }
  }
  webViewClient=object:WebViewClient(){
   override fun onPageFinished(view:WebView,url:String){ready=true;state?.let{update(it)}}
   override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest):Boolean {
    val uri=request.url
    if(request.isForMainFrame && uri.scheme=="https" && uri.host=="waypoint.invalid" && uri.path=="/select")uri.getQueryParameter("device")?.let(select)
    else if(request.isForMainFrame && uri.scheme=="https" && uri.host=="www.openstreetmap.org")runCatching{activity.startActivity(Intent(Intent.ACTION_VIEW,uri))}
    return true
   }
   override fun shouldInterceptRequest(view:WebView,request:WebResourceRequest):WebResourceResponse? {
    if(request.url.scheme=="https" && request.url.host=="tile.openstreetmap.org")return null
    return WebResourceResponse("text/plain","utf-8",java.io.ByteArrayInputStream(ByteArray(0)))
   }
  }
  val html=activity.assets.open("map/index.html").bufferedReader().use{it.readText()}
   .replace("__LEAFLET_CSS__",activity.assets.open("map/leaflet.css").bufferedReader().use{it.readText()})
   .replace("__LEAFLET_JS__",activity.assets.open("map/leaflet.js").bufferedReader().use{it.readText()})
  loadDataWithBaseURL("https://waypoint.invalid/map/",html,"text/html","utf-8",null)
 }
 fun update(value:JSONObject){state=value;if(ready)evaluateJavascript("window.waypoint.update("+value.toString()+");",null)}
 fun center(){if(ready)evaluateJavascript("window.waypoint.center();",null)}
 fun showAll(){if(ready)evaluateJavascript("window.waypoint.all();",null)}
 fun followNextLocation(){if(ready)evaluateJavascript("window.waypoint.follow();",null)}
 fun bottomInset(height:Int){state?.put("bottom",height);if(ready)evaluateJavascript("window.waypoint.resize($height);",null)}
 fun release(){stopLoading();loadUrl("about:blank");removeAllViews();destroy()}
}
