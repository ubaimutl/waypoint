# Android device map

Version 1.1.0 (code 27) includes the compact map interface developed and tested in build 26. Earlier build and rollout notes below are historical. The dashboard remains available separately.

The app opens directly to **Devices** when an account is saved. Locations appear inside the app, with markers for all active devices that have a report and an accuracy circle for the selected device. Choose a device from its marker or the expandable native device list. The selected device panel shows its name, last seen time, battery from its latest report, location age and accuracy. Phones without reports remain selectable and clearly show that no location has been received.

**Locate** requests a fresh location through the existing authenticated controller API. **Play sound** provides duration, level and vibration options; it becomes **Stop sound** while ringing or awaiting confirmation. The location service and ring implementation are unchanged. The map is retained across foreground refreshes, so refreshing does not reset the user’s zoom and pan. Recenter and show-all controls are available on the map. After a location request, automatic recentering waits for a changed report rather than centering on the previous location.

Native bottom navigation separates:

- **Devices:** map, device selection and recovery actions.
- **This phone:** existing connection, location permissions, notification permissions, SMS and periodic fallback settings.
- **Account:** server information, dashboard link and account sign-out. Signing out retains tracking enrollment.

Sign-in is still password-first, followed by the authenticator step when required. Sign-in actions now sit in a fixed native footer above the keyboard, with a scrolling form above it. Outside taps dismiss the keyboard after dispatching the tap so that dismissal does not swallow button actions. Dragging to scroll or selecting another input does not dismiss it inadvertently.

## Implementation and privacy

Navigation, device panels, buttons, dialogs, settings and forms use native Android views. A bundled Leaflet renderer inside a restricted WebView displays the map. Account/tracking credentials and proxy headers are never passed into the map renderer. There is no JavaScript-to-Android object bridge, geolocation permission in the renderer, local file/content access, or arbitrary remote script loading. Device selection uses a narrowly handled local navigation event. HTTPS tile images load directly from OpenStreetMap; attribution links open externally.

Leaflet’s BSD license is included in the APK assets. Map attribution stays visible above the device panel. Tiles use the normal WebView HTTP cache and an application-specific User-Agent, without bulk/offline prefetch. See the [OpenStreetMap tile policy](https://operations.osmfoundation.org/policies/tiles/). Map tiles require a working internet connection; saved device positions can still render when tile loading fails, and a map error message is shown.

This preview adds no location acquisition when merely viewing the map and no background polling. The existing 15-second controller refresh runs only while the Devices screen is resumed. This phone’s listener, heartbeat policy and optional fallback behavior remain in place.

## Compact appearance preview — 2026-11-01

The large app header has been removed. The initial map has a small vertical show-all/recenter capsule and a rounded floating bottom dock. The Devices tab opens the device sheet. The sheet has three positions: collapsed (map and dock only), selected-device details, and an expanded device list occupying roughly half the screen. Drag the sheet title to snap between positions, or tap Devices to toggle the list. The decorative grabber lines have been removed from both the sheet and dock. Selecting a device row or map marker opens its location and sound controls. Large text and short screens can scroll the sheet content.

Account → Appearance provides System (default), Light and Dark themes, plus an independent Liquid glass switch (off by default). Themes cover native account/settings views, map controls and device sheets. The dark map uses a color filter on the existing OSM raster tiles; device markers, attribution and text remain separate.

Glass uses an actual blurred map snapshot behind the panels, with a contrast tint and fine border. All panels share one downsampled in-memory snapshot. Captures are debounced after map movement/tile loading settles; there is no continuous blur loop, persistent image storage, extra network request or background work. Surfaces fall back to solid until a snapshot is available. This is a blur-based glass treatment, not Apple's proprietary refraction renderer. The blur treatment was inspected on the physical WebView. Its battery impact has not been measured.

Devices, This phone and Account now share the same native `AppNavigation` dock: 64 dp height, identical margins, icons, labels and selected states. Account's redundant Back to map button was removed, and settings headings/cards/buttons were aligned. The dock has no elevation shadow, eliminating the clipped rectangular shadow on Account. The original green accent is retained in both themes, including map markers and native controls. Status refreshes do not automatically reopen a dismissed device sheet.

The earlier automatic approval review error did not match the user's available usage and cleared on retry. A normal Gradle build then worked; no phone permission or API-key change was needed. A startup theme crash found during physical testing was fixed by applying system-bar flags after the window view exists.

Build 26 passed `assembleDebug`, `assembleDebugAndroidTest` and `lintDebug` (warnings remain), and was installed over the existing app without clearing its data. The final physical UI check passed device-list selection, fully visible location/sound actions, map retention during refresh, navigation across all three pages, and identical dock bounds. One earlier check ran before layout had settled; the geometry check now waits for the UI to become idle before measuring. The final Account screenshot confirms the rectangular shadow and grabber are absent and the green accent is restored. Light and dark appearance were inspected during this session. No location or audible ring commands were sent by these checks.

Rollback APK: `/tmp/waypoint-account-rollout/before-compact-build23.apk` (the previously installed build 22). No commit, push or server deployment was performed for this compact UI update.

## Physical validation — 2026-10-31

Build 22 was installed over the existing app on the user-authorized test phone. Saved account and tracking enrollment were retained.

- A real saved device position rendered on the embedded map; visible tiles and OpenStreetMap attribution loaded.
- Native locate/sound controls were present, and foreground refresh kept the same map instance.
- Devices → Account → Devices and Devices → This phone → Devices navigation passed. Each bottom tab was checked for full visibility; the navigation bar is anchored independently from the device panel.
- The sign-in view was previewed without clearing the saved account or sending credentials. Code entry was hidden initially. With the keyboard open, Continue remained fully visible. Tapping it dismissed the keyboard and still executed the local empty-password validation.
- Debug APK, instrumentation APK and Android lint builds passed. Lint warnings remain.
- Existing location/ring service behavior was not changed; these UI checks sent no location or audible ring requests.

The user confirmed that Wi-Fi had lacked internet access and that sign-in worked after the network recovered. The previously observed DNS hang is now bounded by the account request’s DNS and UI deadlines; the timeout alone did not establish a password or two-factor authentication failure.

The previous build-19 APK is saved at `/tmp/waypoint-account-rollout/before-map-build20.apk` on the workstation. No server deployment was needed for the map UI: it consumes the existing authenticated dashboard response. No commits or pushes were made for this preview.
