# Architecture and API

`server/` contains the dashboard, Node.js HTTP API, SQLite storage, and encrypted push sender. `android/` contains a native Kotlin app. The Android app has no Google services dependency. The UnifiedPush connector handles distributor registration, encrypted messages, and delivery callbacks.

## Authentication

The dashboard uses a single scrypt-hashed password, secure HttpOnly/SameSite cookies, server-side sessions, and origin checks on writes. Failed logins are rate-limited. The phone uses a separate bearer device token. Each instance has one owner and up to 20 active devices. Device bearer tokens are independently revocable. Dashboard MFA is separate from device credentials.

## Device routes

All device routes require `Authorization: Bearer <DEVICE_TOKEN>`.

- `GET /api/device/socket`: authenticated WebSocket upgrade for built-in mode; receives ready/wake messages without commands or credentials. Pending requests are replayed on reconnect. One active socket per device.
- `POST /api/device/transport`: `{mode:"builtin"|"unifiedpush"|"off"}` selects the wake transport.
- `POST /api/device/identity`: `{manufacturer,model}` updates that authenticated device’s hardware labels.
- `GET /api/device/commands`: returns `{command:null}` or a pending `{id,created_at,type:"locate"}`.
- `POST /api/device/report`: `{lat,lng,accuracy,battery,charging,recorded_at,command_id?}`. Accuracy is meters; battery is integer percentage. Command results must be fresh and refer to a pending command. Unknown/completed/cancelled/expired commands return 409.
- `GET /api/device/push-info`: returns the server’s VAPID public key.
- `POST /api/device/push-subscription`: `{endpoint,keys:{p256dh,auth}}`, using the UnifiedPush endpoint and encryption keys. Protected against private-address SSRF by default.
- `POST /api/device/push-unregister`: removes the saved subscription.

## Dashboard routes

Authenticated browser writes require an exact matching `Origin` header. Location, cancellation, SMS and retry POST bodies accept `device_id`; dashboard GET accepts `?device=<id>`. Omitting it selects the first active device for compatibility. Device API scope is always determined by its bearer token, never a client-supplied device ID.

- `GET /api/devices`: safe names, hardware labels, status and registration metadata (no credential hashes or ciphertext).
- `POST /api/devices/add`: name plus password and MFA confirmation; returns a new device token once.
- `POST /api/devices/rename`: device ID and new name.
- `POST /api/devices/revoke`: device ID plus password/MFA; immediate revocation.
- `GET /api/security`: MFA state, remaining recovery-code count, session count/expiry.
- `POST /api/security/setup`, `/confirm`, `/disable`: authenticated TOTP enrollment/management; requires a verified code to activate.
- `POST /api/security/revoke-sessions`: password/MFA confirmation; signs out other browsers.
- `POST /api/locate`: queues one request, sends a wake-up through the selected built-in or UnifiedPush transport, and returns command plus push state. An existing pending request is reused. Commands expire after 30 minutes.
- `POST /api/retry-push`: retries the pending wake-up; sends are limited to one per 10 seconds.
- `POST /api/cancel`: cancels pending requests.
- `POST /api/wake-message`: creates a short-lived, signed SMS for the pending request.
- `GET /api/dashboard`: returns recent reports, commands, and safe push status without the endpoint capability URL or encryption secrets.

## Push payload and trust

RFC8291 / aes128gcm payload: `{"type":"wake","version":1}`. VAPID signs the push request. No command parameters, location, or device credentials are sent to the push provider. On receipt, the phone obtains the command over authenticated HTTPS. Arbitrary push contents cannot trigger remote shell, camera, wipe, or other commands because these are not implemented.

Subscription and VAPID data persist in SQLite. The server may read uploaded locations. This architecture does not provide FMD’s complete feature set or end-to-end location encryption.

MFA login accepts password and a TOTP/recovery code. A correct password without a second factor returns `requiresTwoFactor:true` and no session cookie. Recovery codes are consumed once. Authenticator time steps are tracked to reject replay, including concurrent verification. SQLite scopes reports, commands and subscriptions by device ID. Existing single-device data migrates to the bootstrap device without changing its token.

The built-in client sends `{type:"heartbeat",version:1,nonce}` over its authenticated socket. The origin echoes `{type:"heartbeat_ack",version:1,nonce}` only for a valid nonce on the current device socket. The phone marks the listener connected only after a matching acknowledgement, and reconnects when its acknowledgement deadline expires. This distinguishes origin reachability from a TCP/WebSocket connection to a proxy. Heartbeats never request GPS.
