# Waypoint Android

Native Kotlin device locator. Configure any compatible Waypoint HTTPS server in **Connection settings**; no personal server address or credential is compiled in.

Build with JDK 17+, SDK 36 and compatible Gradle 9.6+:

```sh
gradle -p android assembleDebug
```

Install, configure server origin and device token, grant location/notification permissions, install a UnifiedPush distributor, and press **Register internet push**. Background location must be set to **Allow all the time**. Optional SMS permission enables signed SMS wake-ups. Periodic request checking is off by default.

Internet push follows the FMD-style wake/fetch/report approach using the official UnifiedPush connector. The connector decrypts Web Push notifications; the app accepts only the expected wake format, then obtains commands from its configured server. Push messages do not directly carry executable commands.

Connection settings and subscription secrets use Android Keystore encryption. APKs contain no user configuration. Android backup is disabled. Cloudflare Access headers are optional settings for users who choose that proxy.

Attempts stop after 65 seconds. Background notification is visible during a location attempt; there is no permanently running Waypoint service. The separate distributor maintains the push connection and may need unrestricted background operation. Manufacturer battery restrictions still apply. Do not force-stop the app if you expect wake-ups.

Debug APKs are for development. Use a dedicated release signing key for distribution and retain it for upgrades. No key is included in this repository.

On-device tests are in `app/src/androidTest`. They use only the phone’s already configured server and do not embed credentials or print coordinates. No emulator is required when a USB device is connected.
