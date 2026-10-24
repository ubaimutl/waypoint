# Waypoint server

See the project [README](../README.md) for deployment and Android setup. This directory is the Docker build context. Node.js 24+ is required.

```sh
npm ci
npm test
npm run dev
```

Demo login: `waypoint-demo`. Production requires `APP_ORIGIN` (HTTPS), `ADMIN_PASSWORD_HASH`, and `DEVICE_TOKEN` (32+ random characters), with secure cookies enabled. `DEVICE_NAME` defaults to `My phone`. Root `scripts/setup.mjs` generates private configuration. Use `npm run hash-password` with a password supplied through stdin if choosing your own password.

Mount `/app/data` persistently. It stores locations, commands, sessions, the Web Push registration, and automatically generated VAPID keys. Use one instance with a local SQLite volume. Do not share SQLite over network storage or run multiple writers in different containers.

Login is globally rate-limited for this single-owner instance. Eight failed attempts lock login for up to 15 minutes. Changing the password does not revoke sessions: stop the app, delete rows from the SQLite `sessions` table, then restart.

`PUSH_ALLOWED_HOSTS` is an optional comma-separated provider allowlist. `PUSH_PRIVATE_HOSTS` allows specific self-hosted HTTPS push providers to resolve to private addresses. With neither set, any public HTTPS provider can be used. The server does not assume Cloudflare or a particular push provider. See [architecture](../docs/architecture.md).
