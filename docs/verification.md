# Verification

Validated during development:

- Server tests: authentication, origin checks, secure sessions, device token separation, command lifecycle, signed SMS creation, report validation, persistence, encrypted push payload, expired endpoints, provider configuration, DNS/IP SSRF checks.
- Docker Compose: base configuration and optional Caddy override parse successfully.
- Setup generator: configurable HTTPS origin/device name, private output permissions, and overwrite protection.
- Android: APK and on-device test APK compile against SDK 36.
- Physical Android 16 device + ntfy: registration with encryption keys and VAPID saved to the real server.
- Internet-only request with Waypoint backgrounded and its cached process killed: push woke the app, a fresh location report arrived, and the queued command completed.

Limitations of these checks:

- Screen-off delivery was delayed on the test device. Both Android battery exemptions and manufacturer-specific background/auto-start settings matter. A successful awake/background test is not a guarantee for a sleeping device.
- Deep Doze, reboot recovery, unstable networks, and multi-day standby battery use have not been comprehensively validated.
- The physical distributor test used ntfy. Other compatible distributors are supported through the standard connector but require their own device validation.
- No measured battery-drain percentage is claimed.

Test Android location reporting using the already configured device:

```sh
gradle -p android assembleDebug assembleDebugAndroidTest
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
adb install -r android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w dev.ubai.waypoint.test/dev.ubai.waypoint.DeviceCheck
```

Registration test (uses an installed distributor):

```sh
adb shell am instrument -w -e mode register-push dev.ubai.waypoint.test/dev.ubai.waypoint.DeviceCheck
```

After registration, return to the home screen, request a location from the dashboard, and verify **completed**, not merely **Push accepted by provider**. Repeat with the display off after allowing both applications to run in the background. Remove the temporary test APK when finished.
