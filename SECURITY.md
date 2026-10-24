# Security

Use HTTPS, a strong dashboard password, a unique random device token, and persistent storage with restricted host access. Keep dependencies and the host updated. Do not expose development/demo mode.

Do not commit `.env`, credentials, device provisioning files, push capability URLs, signing keys, database files, or real location reports. Git ignores these by default; review staged files before publishing.

The push sender requires authenticated device registration, HTTPS, valid P-256/auth keys, and public-address DNS checks, repeated at connection time. Private push providers require an explicit exact-host exception. Avoid exceptions for hosts you do not control. Redirects are not followed.

Possession of the device token authorizes device API actions. Revoke it by changing `DEVICE_TOKEN`, updating the Android connection, and registering push again. To invalidate all dashboard sessions, stop the application and delete rows from the SQLite `sessions` table, then restart it. Changing the password hash alone does not revoke existing sessions.

The Android app stores credentials encrypted with a Keystore key and disables Android backup. A compromised/rooted device or an authorized debugger can still compromise a development build. Sign production releases with your own key and disable debugging.

The server stores location history in its local SQLite database without application-layer encryption. Protect disk storage and backups. Anyone with host-level access can read reports and credentials.

Use a private security advisory on the repository to report vulnerabilities rather than putting credentials or private location data in an issue.

The built-in WebSocket requires the device bearer token in an HTTP header, never in a query URL. It carries only a wake signal; commands are fetched separately through the authenticated API. Browser-origin upgrades must match the configured application origin. Configure your reverse proxy for WebSocket upgrades and HTTPS.
