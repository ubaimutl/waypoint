# Security

Use HTTPS, a strong dashboard password, a unique random device token, and persistent storage with restricted host access. Keep dependencies and the host updated. Do not expose development/demo mode.

Do not commit `.env`, credentials, device provisioning files, push capability URLs, signing keys, database files, or real location reports. Git ignores these by default; review staged files before publishing.

The push sender requires authenticated device registration, HTTPS, valid P-256/auth keys, and public-address DNS checks, repeated at connection time. Private push providers require an explicit exact-host exception. Avoid exceptions for hosts you do not control. Redirects are not followed.

Possession of a device token authorizes only that device’s API actions. Use Manage device → Revoke device to terminate its socket, cancel pending requests, remove its subscription, and reject further authentication. It stays revoked across restarts. Device names and reported hardware models are labels, not cryptographic hardware identity.

The dashboard supports optional TOTP 2FA with replay protection and one-use random recovery codes. No dashboard session is issued until both factors succeed when enabled. Enrollment requires password confirmation and proof of a current authenticator code. Activation and disabling revoke previous sessions. Sensitive credential actions require password plus a current authenticator or recovery code. Recovery code hashes and TOTP secrets are encrypted with AES-256-GCM; `secrets.key` is generated in the persistent volume with mode 0600. New device tokens are indexed by hash for authentication and encrypted separately for signed SMS generation. Protect and back up the key with the database. These protections do not protect against a compromised host with access to both.

Changing `ADMIN_PASSWORD_HASH` revokes browser sessions at startup. Security → Sign out other browsers revokes other sessions immediately. Sessions have an absolute 12-hour expiration. A server administrator can reset lost MFA using the documented maintenance command; this also revokes all browser sessions. TOTP is opt-in and passkeys are not implemented.

The Android app stores credentials encrypted with a Keystore key and disables Android backup. A compromised/rooted device or an authorized debugger can still compromise a development build. Sign production releases with your own key and disable debugging.

The server stores location history in its local SQLite database without application-layer encryption. Protect disk storage and backups. Anyone with host-level access can read reports and credentials.

Use a private security advisory on the repository to report vulnerabilities rather than putting credentials or private location data in an issue.

The built-in WebSocket requires the device bearer token in an HTTP header, never in a query URL. It carries only a wake signal; commands are fetched separately through the authenticated API. Browser-origin upgrades must match the configured application origin. Configure your reverse proxy for WebSocket upgrades and HTTPS.
