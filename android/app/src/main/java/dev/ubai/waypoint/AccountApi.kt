package dev.ubai.waypoint

import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.util.concurrent.TimeUnit

internal class AccountFailure(val status:Int,message:String):Exception(message)

/** Unlike socket read timeouts, callTimeout bounds the entire exchange. */
internal object AccountApi {
 private val client=OkHttpClient.Builder().dns(AccountDns)
  .connectTimeout(10,TimeUnit.SECONDS).readTimeout(15,TimeUnit.SECONDS)
  .writeTimeout(10,TimeUnit.SECONDS).callTimeout(25,TimeUnit.SECONDS)
  .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
 fun call(config:JSONObject,path:String,data:JSONObject?=null):Call {
  val builder=Request.Builder().url(config.getString("url")+path)
  if(config.optString("access_token").isNotEmpty())builder.header("Authorization","Bearer "+config.getString("access_token"))
  if(config.optString("cfId").isNotEmpty()){
   builder.header("CF-Access-Client-Id",config.getString("cfId"))
   builder.header("CF-Access-Client-Secret",config.optString("cfSecret"))
  }
  if(data!=null)builder.post(data.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
  return client.newCall(builder.build())
 }
 fun result(response:Response,path:String,data:JSONObject?=null):JSONObject {
  val status=response.code
  val payload=response.body
  val json=if(payload?.contentType()?.subtype?.contains("json")==true)runCatching{JSONObject(payload.string())}.getOrNull() else null
  if(!response.isSuccessful){
   val reason=when {
    status==429 -> "Too many sign-in attempts. Wait "+(response.header("Retry-After")?.toLongOrNull()?.let{ "${(it+59)/60} minutes" }?:"15 minutes")+" before trying again."
    status==401 && path=="/api/mobile/sign-in" -> if(data?.optString("code").isNullOrEmpty())"The dashboard password was not accepted." else "The password or verification code was not accepted. Use a fresh authenticator code or a recovery code."
    status in listOf(301,302,303,307,308) -> "Your access proxy redirected sign-in. Check its optional service credentials."
    else -> json?.optString("error") ?: "Server returned HTTP $status. Try again when connected."
   }
   throw AccountFailure(status,reason)
  }
  return json?:throw Exception("Expected a Waypoint server. Check the server address and access proxy settings.")
 }
}
