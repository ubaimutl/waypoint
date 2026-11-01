# Waypoint Android

Native Kotlin device locator. Configure any compatible Waypoint HTTPS server in the **Connection** card; no personal server address or credential is compiled in.

Build with JDK 17+, SDK 36 and compatible Gradle 9.6+:

```sh
gradle -p android assembleDebug
```

With a matching server built from this checkout, choose **Sign in to your server**, enter the server address and dashboard password (plus a two-factor code if enabled), then use the **Devices** map to locate/ring other devices. Native bottom navigation also provides **This phone** settings and **Account**. See [the Android map preview](../docs/mobile-map-preview.md). Account access can be revoked separately from tracking in the dashboard. See [the account preview](../docs/account-preview.md).

Manual token configuration remains available: install, configure server origin and device token, grant location/notification permissions, choose built-in mode (no extra app), or install a UnifiedPush distributor and register it. Background location must be set to **Allow all the time**. Optional SMS permission enables signed SMS wake-ups. Periodic request checking is off by default.

Internet push follows the FMD-style wake/fetch/report approach using the official UnifiedPush connector. The connector decrypts Web Push notifications; the app accepts only the expected wake format, then obtains commands from its configured server. Push messages do not directly carry executable commands.

Connection settings and subscription secrets use Android Keystore encryption. APKs contain no user configuration. Android backup is disabled. Cloudflare Access headers are optional settings for users who choose that proxy.

Attempts stop after 65 seconds. The built-in mode runs a foreground service with a persistent listening notification and an authenticated WebSocket to your server. UnifiedPush mode instead lets the separate distributor maintain its connection; Waypoint only runs its bounded location service on requests. Switching modes stops the previous listener. Both modes may need unrestricted background operation. Manufacturer battery restrictions still apply. Do not force-stop the app if you expect wake-ups.

Debug APKs are for development. Use a dedicated release signing key for distribution and retain it for upgrades. No key is included in this repository.

On-device tests are in `app/src/androidTest`. They use only the phone’s already configured server and do not embed credentials or print coordinates. No emulator is required when a USB device is connected.

Built-in mode requires a matching server with heartbeat acknowledgements. Version 1.1.0 (code 27) verifies an origin acknowledgement every two minutes (45 seconds in the published v1.0.0 release), with a 20-second acknowledgement deadline while the process is executing. Default network changes replace the socket. The notification shows the last verification time rather than relying on a successful old handshake. Android sleep can delay timers; this does not guarantee immediate delivery in deep Doze.

To refresh the README app screenshots on a development device, build and install the instrumentation APK, then run:

```sh
gradle -p android assembleDebugAndroidTest
adb -s DEVICE install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s DEVICE shell am instrument -w -e mode screenshots dev.ubai.waypoint.test/dev.ubai.waypoint.DeviceCheck
```

This renders the actual native map UI with sample devices and public coordinates, sends no recovery commands, and restores the saved appearance preferences. Four PNGs are saved in `/sdcard/Android/data/dev.ubai.waypoint/cache/readme-app*.png`; pull them with ADB and inspect before publishing. Other test modes may use the device's configured server.
