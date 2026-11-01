# Map-first dashboard preview — 2026-11-01

The map fills the viewport beneath a small header. A single rounded floating panel switches between the device list and selected device. On phones it sits at the bottom, matching the Android app's layout. The list can collapse to its heading. The detail view shows identity, location age, battery and Locate/Play sound actions; Devices returns to the list.

Explanatory notes, enrollment guidance, privacy information, connection details and coordinates are behind circled exclamation buttons. Sound options and activity use separate dialogs. Account contains System/Light/Dark appearance, optional glass panels, Security and Sign out. Green remains the primary accent. The existing administrative and recovery functions are retained; no tracking or authentication behavior was changed.

All available reported devices appear on the map with accessible names. Choosing a marker or list row selects that device; the map offsets its center so the floating panel does not cover the chosen location. Foreground refresh retains panel state and viewport. A dismissed/collapsed panel does not reopen on refresh.

Theme choice is saved locally per browser and applied before styles load. System follows OS appearance. Glass is off by default and saved separately; enabling it applies actual CSS backdrop blur to floating panels. Storage failures fall back to in-memory choices. No credentials are stored by appearance code. Layouts use Flexbox and positioned overlays.

Validation: all 54 server regression tests passed. Browser checks used isolated sample devices and a local demo database. Device selection, list collapse/expand, information popovers, sound settings, theme persistence, optional blur and sign-in were checked. Widths of 320, 390 and 1366 pixels had no page-level overflow; the phone layout fits the viewport without page scrolling. Syntax, static DOM references and duplicate IDs passed checks. No browser errors were reported. No real device was located or rung.

Preview: http://127.0.0.1:3215 (demo password `waypoint-demo`). Recovery/admin actions are disabled in demo mode. Sample data lives under `/tmp/waypoint-dashboard-compact-demo`. Current screenshots: `/tmp/waypoint-dashboard-map-dark.png`, `/tmp/waypoint-dashboard-map-light.png` and `/tmp/waypoint-dashboard-map-mobile.png`.

Deployed to the existing VPS through Dokploy on 2026-11-01 at the user's request. Only `public/index.html`, `public/app.js`, `public/style.css`, `public/theme.js` and the theme asset route in `server.mjs` were updated. Container health and HTTP checks passed; the four served dashboard assets match the local SHA-256 hashes, and anonymous account API access still returns 401. No Git commit or push was made.

Rollback on the VPS: the previous image is retained as `waypoint-dashboard:before-compact-dashboard-20261101`; previous dashboard source is saved in `/home/ubai/waypoint-dashboard-rollbacks/20261101-dashboard/source.tar.gz`. Restore that source and image tag before redeploying the existing Dokploy compose application. Credentials and the persistent data volume were preserved.
