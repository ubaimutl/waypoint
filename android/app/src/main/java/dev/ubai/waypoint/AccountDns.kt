package dev.ubai.waypoint

import android.net.DnsResolver
import android.net.InetAddresses
import android.os.CancellationSignal
import okhttp3.Dns
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Uses Android's default network resolver; never falls back to a public DNS provider. */
internal object AccountDns:Dns {
 private val callbacks=Executors.newSingleThreadExecutor{Thread(it,"Waypoint account DNS").apply{isDaemon=true}}
 override fun lookup(hostname:String):List<InetAddress> {
  if(InetAddresses.isNumericAddress(hostname))return listOf(InetAddresses.parseNumericAddress(hostname))
  val cancellation=CancellationSignal()
  val answer=CompletableFuture<List<InetAddress>>()
  try{
   DnsResolver.getInstance().query(null,hostname,DnsResolver.FLAG_EMPTY,callbacks,cancellation,object:DnsResolver.Callback<List<InetAddress>>{
    override fun onAnswer(addresses:List<InetAddress>,rcode:Int){
     if(rcode==0 && addresses.isNotEmpty())answer.complete(addresses)
     else answer.completeExceptionally(UnknownHostException("Your network could not resolve the server address."))
    }
    override fun onError(error:DnsResolver.DnsException){answer.completeExceptionally(error)}
   })
   return answer.get(8,TimeUnit.SECONDS)
  }catch(error:InterruptedException){
   Thread.currentThread().interrupt()
   throw UnknownHostException("Server address lookup cancelled.").apply{initCause(error)}
  }catch(error:TimeoutException){
   throw UnknownHostException("Server address lookup timed out. Check your network, VPN or private DNS settings.").apply{initCause(error)}
  }catch(error:ExecutionException){
   throw UnknownHostException("Your network could not resolve the server address. Check your network, VPN or private DNS settings.").apply{initCause(error.cause?:error)}
  }catch(error:UnknownHostException){throw error}
  catch(error:Exception){throw UnknownHostException("Server address lookup failed. Check your network connection.").apply{initCause(error)}}
  finally{cancellation.cancel()}
 }
}
