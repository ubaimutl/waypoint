package dev.ubai.waypoint

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object Config {
 private const val ALIAS = "waypoint-config"
 private fun key(): SecretKey {
  val store=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
  if(store.containsAlias(ALIAS)) return store.getKey(ALIAS,null) as SecretKey
  return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply { init(KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build()) }.generateKey()
 }
 fun save(context:Context, value:JSONObject) {
  val url=java.net.URI(value.getString("url"))
  require(url.scheme=="https" && !url.host.isNullOrEmpty() && url.userInfo==null && (url.path.isNullOrEmpty() || url.path=="/") && url.query==null && url.fragment==null) { "Use an HTTPS server origin." }
  require(value.getString("token").length>=32) { "The device token is too short." }
  val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key())
  val bytes=cipher.doFinal(value.toString().toByteArray())
  context.getSharedPreferences("secure",0).edit().putString("iv",Base64.encodeToString(cipher.iv,Base64.NO_WRAP)).putString("config",Base64.encodeToString(bytes,Base64.NO_WRAP)).commit()
 }
 fun read(context:Context):JSONObject? {
  val p=context.getSharedPreferences("secure",0)
  val blob=p.getString("config",null)?:return null
  val cipher=Cipher.getInstance("AES/GCM/NoPadding")
  cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,Base64.decode(p.getString("iv",""),Base64.NO_WRAP)))
  return JSONObject(String(cipher.doFinal(Base64.decode(blob,Base64.NO_WRAP))))
 }
 fun status(context:Context,message:String) { context.getSharedPreferences("state",0).edit().putString("status",message).putLong("updated",System.currentTimeMillis()).apply() }
}
