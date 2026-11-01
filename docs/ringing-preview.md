# Connection improvements and remote ringing

Included in v1.1.0 (Android version code 27). Release preparation is local; no registry image or GitHub release has been published. Earlier preview test notes are retained below.

## Connection changes

The repository now includes the experimental server-paced 120-second application heartbeat, matching-nonce origin verification and 20-second reply deadline, automatic recovery, offline retry suspension, cached identity upload, less frequent diagnostic persistence, and bounded opportunistic cellular heartbeat batching. Older clients retain their original protocol. The server has explicit timeout/replacement close reasons; optional `onEvent` callbacks expose lifecycle diagnostics.

A scheduled HTTP check may be skipped only after a recent explicit origin acknowledgement says there are no pending commands. Ring requests are included in this check, so ringing cannot be hidden by location-only queue state. Existing periodic polling preference is preserved. The fresh location selection and stopping location updates before upload remain in place.

The temporary ABBA experiment runner, its exported control receiver, and measurement scripts are **not included** in the normal app. Connection counters remain. These changes do not establish a universal battery-saving percentage.

## Ringing

Select a device in the dashboard, then use **Play a sound**:

- Duration: 15 seconds, 30 seconds, 1 minute, or 2 minutes.
- Sound level: 25%, 50%, 75%, or 100% of the phone's existing alarm-stream volume.
- Optional repeating vibration.
- Stop from the dashboard or the phone's ongoing notification.

The app uses a bundled generated tone; no sound file is downloaded. It requests transient audio focus and stops on focus loss. The phone's volume settings and Do Not Disturb policy are not changed. Muted alarm volume produces a failure response; DND, audio routing and manufacturer restrictions can affect audibility. Playback confirmation means the player started, not that the sound was audible to a person.

Sound uses a media-playback foreground service and a bounded wake lock only while ringing (maximum duration plus five seconds). The dispatcher fetches commands without requesting location. GPS is started only for an actual location request. Recovery services and playback are not exported. There is no microphone access.

## Delivery and state

`POST /api/ring` requires the existing dashboard session and same-origin check, validates the selected active device and advertised app capability, and queues a separate per-device ring request. Existing location requests are not cancelled. Clients advertise `ring_supported` with identity registration; older clients ignore the additional `ring` field returned by `/api/device/commands` and cannot accidentally execute it as a location request.

Queued sounds expire after 60 seconds. The dashboard shows queued until the phone acknowledges playback via its device token at `/api/device/ring-status`. A stop replaces the pending/playing ring, bypasses the built-in WebSocket wake throttle, and can also be delivered by existing push or periodic retrieval. UnifiedPush's existing rate limit still applies. If connectivity fails, remote stop may be delayed; the phone's local timeout remains authoritative.

Duplicate delivery cannot extend playback. Handled IDs are persisted before playing; creation ordering prevents an older request from restarting sound after a newer stop. Older acknowledgements cannot overwrite newer commands. If no final acknowledgement arrives, the server says the sound should have ended but is unconfirmed, rather than asserting a confirmed stop. Ring state persists across server restart and is isolated by device token. Revocation clears queued ringing; an already-playing offline phone still stops locally within its duration.

Signed SMS continues to fetch the pending location request; this preview does not add a separate ring-specific SMS protocol. Background execution policies can block starts, especially with only periodic checks and no active foreground listener; the dashboard must not claim success without acknowledgement.

## Earlier preview validation

- Full Node server suite: 45 tests passed, including per-device isolation, capability gating, CSRF/session/device authentication, expiry, repeated acknowledgement, stale command, stop supersession and legacy WebSocket behavior.
- Dashboard browser checks using mocked responses: duration/volume/vibration payload, selected device, queued/stop states, desktop and 390px mobile layout, no horizontal overflow or JavaScript errors.
- Android debug APK and instrumentation APK built; Android lint checked separately.
- A `ring-policy` instrumentation mode validates bounded and expired payloads without playing audio. It is compiled, **not run on the user's phone**.

At that earlier preview stage, pending checks included: physical sound/vibration, screen-off delivery, muted alarm/DND behavior, local/remote stop, duplicate delivery, automatic timeout and audio-focus interruption. Subsequent user testing confirmed remote ringing worked through Do Not Disturb. This does not establish every audio-routing, manufacturer, or interruption case.

Android guidance: [foreground-service types](https://developer.android.com/develop/background-work/services/fgs/service-types) and [audio focus](https://developer.android.com/media/optimize/audio-focus).

## Version 1.1.0 preparation

All 54 server tests and Android release/debug/instrumentation builds passed. The release APK uses the original official signing certificate. Location requests and ringing were confirmed in user testing.
