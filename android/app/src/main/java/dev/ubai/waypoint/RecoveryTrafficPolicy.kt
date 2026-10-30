package dev.ubai.waypoint

// Pure timing rules; no timers, alarms, network requests or Android dependencies.
internal object RecoveryTrafficPolicy {
 const val INTERVAL=120000L
 const val EARLY_COOLDOWN=600000L
 fun canCoalesce(now:Long,lastSent:Long,lastEarly:Long):Boolean =
  lastSent>0 && now-lastSent in (INTERVAL-10000)..(INTERVAL-2000) &&
  (lastEarly==0L || now-lastEarly>=EARLY_COOLDOWN)
 fun canSkipPoll(now:Long,emptyConfirmedAt:Long):Boolean =
  emptyConfirmedAt>0 && now-emptyConfirmedAt in 0..(INTERVAL+20000)
}
