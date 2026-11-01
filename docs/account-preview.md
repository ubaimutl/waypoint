# Account and multi-device access

This checkout adds phone sign-in and controller access to the existing single owner account per server. It does not introduce public registration, multiple owners, or a hosted Waypoint account. Included in v1.1.0; upgrade the server before the Android app. The published v1.0.0 release does not support these endpoints. Earlier preview rollout notes below describe development testing.

## Using it

1. In Android, choose **Sign in to your server**. Enter the HTTPS server address, device name, and existing dashboard password. After the server accepts the password, the app asks for an authenticator or recovery code when two-factor authentication is enabled. Optional access proxy credentials remain supported.
2. The server enrolls this installation and returns separate tracking and account access credentials. Neither password nor authenticator code is saved. Existing registrations on the same server keep their token, history, connection mode, and fallback settings. Retrying enrollment after a lost response reuses that installation’s registration.
3. Grant location, background location, and notifications from the phone’s settings screen. New installations start the existing built-in listener after notifications are allowed; existing installations keep their chosen mode.
4. **Devices** opens an in-app map with markers and a device panel. Select a device to see its latest location and battery report, request a fresh location, or ring/stop it with duration, sound level and vibration controls. Native bottom navigation separates Devices, This phone and Account. See [the Android map preview](mobile-map-preview.md). Android permissions are needed for reporting this phone’s location, not for viewing other devices.
5. The web dashboard shows all devices together. Select one to view its details or manage it.

A phone does not get device management or account security permissions. These remain in the dashboard.

## Access controls

- **Manage device → Revoke account access** requires the dashboard password and, when enabled, a two-factor code. It immediately invalidates that phone’s account credential, including commands whose request body was still arriving. The phone keeps reporting its own location and receiving location/ring requests.
- **Sign out of account** inside Android also revokes only its account credential. The phone stays trackable.
- **Manage device → Remove tracking access** retains the existing full revocation behavior: it invalidates tracking and account credentials, disconnects the listener, cancels queued commands, and retains stored history.
- Signing in again requires the account password and configured second factor. It replaces that phone’s previous account credential. Account access survives server restarts until revoked; changing the server password or enabling/disabling/resetting two-factor authentication invalidates existing account access. Tracking credentials continue to work.

Tracking tokens cannot read another device’s location or ring another device. Account access tokens cannot submit tracking reports, retrieve tracking commands, create devices, rename/revoke devices, generate signed SMS, or change dashboard security settings. The database stores only a hash of account access credentials. Android stores them encrypted with Android Keystore, separately from tracking configuration. Credentials are sent as bearer headers over HTTPS; redirects are not followed.

## API

`POST /api/mobile/sign-in` accepts `{password, code?, name, installation_id?, device_token?}`. The optional installation ID is 32 random hex characters, stable for that app installation. An existing tracking token can associate the login with a previously registered device. Both enrollment recovery paths require successful account authentication first. With MFA enabled, a correct password without a code returns `{requiresTwoFactor:true}` and no credentials. Success returns `{device:{id,name,token},access_token,url}`. Sign-in shares the existing persistent authentication rate limit.

The following endpoints require the account access bearer token:

- `GET /api/mobile/devices`: active devices and their latest saved reports.
- `GET /api/mobile/dashboard?device=<id>`: selected device’s reports, queued requests, connection and ringing status.
- `POST /api/mobile/locate`: `{device_id}`.
- `POST /api/mobile/ring`: `{device_id,action,duration?,volume?,vibrate?}`, using the same validation as dashboard ringing.
- `POST /api/mobile/sign-out`: revokes the authenticated phone’s account access.

All other mobile routes are denied. Device tracking routes and dashboard administration do not accept this credential. Native requests may omit `Origin`; a supplied foreign origin is rejected. Browser routes retain their exact-origin requirement. Proxies must allow native HTTPS access to `/api/mobile/*` as well as the existing tracking endpoints.

The dashboard-only endpoint `POST /api/devices/revoke-access` accepts `{device_id,password,code?}`. Device list metadata includes `account_access.enabled` for displaying the revocation action.

## Battery and compatibility

The device list refreshes at most every 15 seconds while its Activity is resumed, and on explicit user actions. It stops scheduling refreshes when backgrounded. Reading a list or saved report never requests GPS. This preview adds no background polling, heartbeat, wake lock, or periodic job. An already running user request may finish after leaving the screen.

The existing listener, battery experiment, SMS fallback and optional 15-minute checks are retained. Older devices continue using their existing tracking tokens. Roll out the server first; older servers do not implement phone sign-in. Existing reports and device registrations require no manual migration.

The schema additions are `mobile_access` and `mobile_enrollments`; the existing tables and tracking tokens are preserved. A rollback to the previous server can ignore these tables, but the phone’s account screen needs this server version to function.

## Verification

- Server integration checks cover native origin rules, account authentication and MFA, existing registration reuse, retried enrollment, credential separation, cross-device location/ring requests, dashboard-only management, account-only revocation, requests arriving during revocation, sign-out, restart persistence and password changes.
- Android debug APK, test APK and lint build pass. Phone UI interaction and end-to-end enrollment on a physical device remain to be tested after installation.
- Headless Chromium checks cover the web all-device overview, device switching, account-access revocation and desktop/mobile overflow and JavaScript errors.

## Current test rollout — 2026-10-31

The user authorized installation and deployment for testing. Dokploy successfully deployed the account preview on the existing server; container health returned HTTP 200, the all-device dashboard is present, and the account API rejects anonymous access with HTTP 401. Android build 16 (`1.2.0-preview`) was installed over build 15 without clearing app data. The built-in connection remains connected. Initial phone sign-in was rejected; the server has MFA enabled. Phone and server clocks agree, and the last accepted authenticator step predates the attempts. Build 17 (`1.2.0-preview2`) was subsequently installed with password-first login: the authenticator field appears only after the password is accepted. Sign-in and subsequent location/ring/revocation tests remain pending.

Rollback assets: the previous APK is `/tmp/waypoint-account-rollout/before-build16.apk` on the workstation. On the VPS, the previous server source is `/home/ubai/waypoint-battery-experiment/before-account-preview-20261031T040139Z/source`, the image is tagged `waypoint-dashboard:before-account-preview`, and the consistent database backup remains inside the existing Docker volume as `/app/data/before-account-preview.sqlite`. The encryption key remains in its original volume. No changes were committed or pushed for this preview.


### Login responsiveness follow-up

Build 19 (`1.2.0-preview4`) was installed after the phone reported an indefinitely pending password check and keyboard obstruction. A debugger stack captured the instrumented account HTTP request blocked in `Linux.android_getaddrinfo` through OkHttp's system DNS implementation. The native socket/call timeouts did not release that resolver call. Account requests now use [Android’s asynchronous DNS API](https://developer.android.com/reference/android/net/DnsResolver) on the default network with an eight-second lookup deadline and cancellation, alongside HTTP connect/read/write/overall deadlines and a 30-second UI safeguard. No public DNS fallback or network configuration change was introduced. Cancellation and navigation discard late responses; background tracking uses its existing connection.

The sign-in form now applies keyboard insets, scrolls above the keyboard, dismisses it on outside taps and submission, and supports the keyboard Done action. Rate limits receive a distinct message. Code entry remains hidden until the password is accepted.

Physical network checks confirmed a DNS failure returned within the new deadline, a deliberately stalled local TLS handshake terminated, and HTTP 429 produces a wait message. No passwords were submitted during those checks. The first keyboard test could not open the keyboard; a separate check confirmed that the phone was locked. Physical keyboard dismissal/scroll verification and successful account sign-in are therefore pending unlock. Temporary test APK was removed after testing; the main app was reopened.


### Map interface follow-up

The user confirmed that Wi-Fi internet trouble caused the lookup failures and successfully signed in after connectivity recovered. Build 22 was subsequently installed with the embedded map home screen and native bottom navigation. Physical map, navigation and keyboard checks passed; see [the Android map preview](mobile-map-preview.md).
