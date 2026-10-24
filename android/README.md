# Waypoint Android

Native Kotlin device locator. Configure any compatible Waypoint HTTPS server in the **Connection** card; no personal server address or credential is compiled in.

Build with JDK 17+, SDK 36 and compatible Gradle 9.6+:

```sh
gradle -p android assembleDebug
```

Install, configure server origin and device token, grant location/notification permissions, choose built-in mode (no extra app), or install a UnifiedPush distributor and register it. Background location must be set to **Allow all the time**. Optional SMS permission enables signed SMS wake-ups. Periodic request checking is off by default.

Internet push follows the FMD-style wake/fetch/report approach using the official UnifiedPush connector. The connector decrypts Web Push notifications; the app accepts only the expected wake format, then obtains commands from its configured server. Push messages do not directly carry executable commands.

Connection settings and subscription secrets use Android Keystore encryption. APKs contain no user configuration. Android backup is disabled. Cloudflare Access headers are optional settings for users who choose that proxy.

Attempts stop after 65 seconds. The built-in mode runs a foreground service with a persistent listening notification and an authenticated WebSocket to your server. UnifiedPush mode instead lets the separate distributor maintain its connection; Waypoint only runs its bounded location service on requests. Switching modes stops the previous listener. Both modes may need unrestricted background operation. Manufacturer battery restrictions still apply. Do not force-stop the app if you expect wake-ups.

Debug APKs are for development. Use a dedicated release signing key for distribution and retain it for upgrades. No key is included in this repository.

On-device tests are in `app/src/androidTest`. They use only the phone’s already configured server and do not embed credentials or print coordinates. No emulator is required when a USB device is connected.

Built-in mode in Android 1.3.2 requires server 1.3.2 or newer. It verifies an origin acknowledgement every 45 seconds, with a 20-second acknowledgement deadline while the process is executing. Default network changes replace the socket. The notification shows the last verification time rather than relying on a successful old handshake. Android sleep can delay timers; this does not guarantee immediate delivery in deep Doze.
