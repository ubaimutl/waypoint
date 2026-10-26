# Contributing to Waypoint

Contributions of all sizes are welcome. Waypoint is under active development and testing; help with code, documentation, translations, accessibility, and real-device testing is useful.

## Report a bug or share a test result

Check existing issues first. Include:

- Android version, manufacturer/model, and Waypoint app and server versions.
- Connection mode: built-in, UnifiedPush (with distributor name), SMS, or scheduled checks.
- Steps to reproduce, expected behavior, and what happened.
- Relevant conditions: Wi-Fi or mobile data, screen on/off, background permissions, and whether the issue follows a reboot or network change.
- For accuracy problems, the reported accuracy and whether the test was indoors or outdoors. Exact coordinates are unnecessary.
- For battery reports, the observation duration, charging periods, location-request count, and relevant Android battery estimates. Distinguish whole-phone drain from Android's per-app estimates.

Remove passwords, device tokens, access-proxy credentials, recovery codes, QR codes, push endpoint URLs, phone numbers, device serial numbers, and precise locations from screenshots and logs. Do not upload databases, configuration exports, or credential files.

Report suspected security vulnerabilities privately using the process in [SECURITY.md](SECURITY.md), rather than a public issue.

## Work on the project

Use your own local server and test device. The [README](README.md) explains setup and available connection modes. The local demo uses synthetic data and must remain private to your development machine.

For server work:

```sh
cd server
npm ci
npm test
npm run dev
```

For Android work, configure the JDK and Android SDK described in the README, then run from the repository root:

```sh
gradle -p android assembleDebug assembleDebugAndroidTest
```

The Android test APK includes opt-in checks that can contact the configured server and upload a real location. Read [verification notes](docs/verification.md) before running them. Build success alone does not verify background behavior or battery consumption.

## Send a pull request

- Keep changes focused. Discuss larger features or architecture changes in an issue first.
- Describe the problem, resulting behavior, and checks performed. Include Android/network conditions for device-specific fixes.
- Add regression coverage for meaningful behavior changes. Run the server suite when changing server behavior and build the Android app when changing Android code.
- Use CSS Flexbox by default for dashboard layouts and preserve accessible labels and keyboard operation.
- Keep deployment provider-neutral and credentials separate from source. Preserve device-token isolation and dashboard authentication.
- Use synthetic data for documentation screenshots. Never include real device reports or live credentials.

By contributing, you agree that your contribution is provided under the repository's [MIT license](LICENSE).
