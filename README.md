# Waypoint

Self-hosted Android device locator with a web dashboard. Uses Google-free Android location APIs and UnifiedPush for internet wake-up. SMS and optional scheduled checks provide alternative transports.

## How it works

1. Press **Locate phone** in the dashboard.
2. The server queues a command and sends an encrypted Web Push wake-up.
3. Your chosen UnifiedPush distributor wakes Waypoint.
4. Waypoint authenticates to your server, fetches the pending command, acquires a location, and uploads the result.
5. The location service stops after completion or a bounded attempt.

The push provider receives an encrypted wake-up payload. Device credentials, location, and command details are exchanged directly with your server over HTTPS. The server can read location reports; this project does not implement end-to-end encryption of stored location history.

This transport design follows the approach documented by [FMD](https://fmd-foss.org/docs/fmd-android/push/). Waypoint is an independent implementation and uses its own server protocol.

## Requirements

- Android 12 or newer; location enabled.
- A UnifiedPush distributor such as **ntfy** or **Sunup** for internet wake-up. It must be allowed to run in the background.
- Docker / Docker Compose on a server, with a persistent volume and HTTPS reverse proxy.
- One device and one dashboard account per server instance. Deploy separate instances for separate devices.

**Cloudflare, Dokploy, Google Play services, and a specific phone model are not required.**

## Server setup

From a checkout, use Node.js 24+ to generate configuration:

```sh
node scripts/setup.mjs --origin https://locator.example.com --device "My phone"
docker compose up -d --build
```

The generator writes `.env` and `credentials.txt` with private file permissions. These files are ignored by Git. Open `credentials.txt` locally for the dashboard password and Android device token. Use the **device token**, not the dashboard password, in the Android app.

The default Compose setup binds HTTP to `127.0.0.1:8080`. Configure your existing HTTPS reverse proxy to forward to that address. Set `APP_ORIGIN` to the exact external HTTPS origin, without a trailing slash.

### Optional Caddy HTTPS

Point a domain at the server, add `DOMAIN=locator.example.com` to `.env`, then run:

```sh
docker compose -f compose.yaml -f deploy/compose.caddy.yaml up -d --build
```

Caddy obtains a certificate and proxies requests to the application. Ports 80 and 443 must be available. No Cloudflare account is needed.

### Dokploy or another container platform

Build from `server/Dockerfile`, with **server/** as build context. Set the variables from `.env` in the platform, mount a persistent volume at `/app/data`, and route to container port **3000**. Use one replica. Logs, environment settings, restarts, and deployment remain under your platform’s control.

### Optional Cloudflare Access

You may place a Tunnel and Access policy in front of the dashboard, but they are optional. Native Android requests must also pass your proxy. For Cloudflare Access, add a Service Auth policy and put its service token ID/secret in the Android app’s **optional Cloudflare Access settings**. Other reverse proxies need no Cloudflare headers. Do not use an interactive login page as the only route to `/api/device/*`.

## Android setup

Build from `android/`, or install an APK built from the source:

```sh
gradle -p android assembleDebug
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

1. Open Waypoint → **Connection settings**.
2. Enter your server’s HTTPS origin and device token from `credentials.txt`.
3. Grant precise location, background location (**Allow all the time**), and notification permissions.
4. Install/configure a UnifiedPush distributor, allow background activity for **both** apps in Android/manufacturer battery settings, then choose **Register internet push** in Waypoint.
5. Confirm the dashboard shows push registration. Background the app, press **Locate phone**, and check that a fresh report arrives.

Connection credentials and saved subscription data are encrypted with Android Keystore. They are not embedded in the APK. A new installation has no predefined server, account, or push registration.

Incoming SMS permission is needed only for the SMS fallback. The app does not need SMS history, SMS sending, contacts, camera, microphone, or device administrator access.

### Push provider selection

Waypoint supports the standard UnifiedPush registration and RFC8291 encrypted Web Push, including VAPID. Choose a distributor in the app. Your distributor may use a hosted push service or a self-hosted service; no provider hostname is compiled into Waypoint.

By default, push endpoints must use HTTPS and resolve to public addresses. DNS and connection-time address checks prevent requests to private services. `PUSH_ALLOWED_HOSTS` optionally limits provider hostnames. If your own push server intentionally resolves to a private address, add its exact hostname to `PUSH_PRIVATE_HOSTS`; TLS validation stays enabled. Endpoints are capability URLs and are never displayed in the dashboard.

A provider accepting a push is shown as **Push accepted by provider**, not as device delivery. Actual completion requires a phone report. Expired registrations require re-registration in the app. Failed sends leave the command queued and show fallback options.

## Other transports

- **SMS:** Press Locate, then Copy wake-up SMS. Send the signed message to the phone from another device within 10 minutes. Waypoint validates its HMAC, timestamp, and replay state, then fetches the pending command. No automatic SMS gateway is included.
- **Scheduled checks:** Optionally enable **Check server every 15 minutes** on the phone. It checks for pending commands without requesting GPS unless needed. Android can delay the schedule.
- **Manual:** Use **Check pending request** or **Send test location** in the Android app.

## Limits and privacy

An offline or powered-off phone cannot report over the internet. Android and manufacturer battery controls may delay or block background execution. Push requires a background distributor connection; there is no measured battery guarantee. Indoor positioning may fail. Location requests stop after 65 seconds rather than keeping GPS running indefinitely. Force-stopping the app disables its background receivers until it is reopened.

The dashboard fetches map tiles from OpenStreetMap. The tile provider sees your browser’s IP and viewed map area. Replace the tile source/CSP if you require a privately hosted map. There is no analytics or automatic reverse geocoding.

The server retains the latest 100 reports. Back up the SQLite volume with a SQLite-aware backup or while stopped. Keep VAPID keys and push subscriptions in that volume across redeployments. Updating the device token requires updating the Android connection and re-registering push. Password changes require revoking existing sessions separately; see `server/README.md`.

## Development and checks

```sh
cd server
npm ci
npm test
npm run dev
```

Local demo: http://localhost:3000, password `waypoint-demo`. Never publish demo mode. Production requires HTTPS configuration and secure cookies.

Android: JDK 17+, Android SDK 36, and Gradle 9.6+ compatible with Android Gradle Plugin 9.4.1. The included build uses the official UnifiedPush connector. Development APKs are debug-signed; use your own release signing key for distribution and retain it for updates. No signing key is included.

See [architecture and API](docs/architecture.md), [security](SECURITY.md), and [third-party notices](NOTICE).
