# Releases and upgrades

Waypoint is under active development and testing. A packaged release does not establish multi-device reliability, a battery guarantee, or an independent security audit.

## Install a release

Download the APK and `SHA256SUMS` from the matching GitHub release. Check the download before installing:

```sh
sha256sum --check --ignore-missing SHA256SUMS
adb install waypoint-1.0.0.apk
```

Only public APKs signed with the same release certificate can update each other in place. The release APK is not debuggable. Configure the server and device credentials on first use.

Earlier development builds displayed `1.3.x`; v1.0.0 is the first tagged public release, with Android version code 9. Its runtime behavior includes those development fixes. Future APK releases must increase the version code and retain the release signing key.

Debug APKs and self-signed APKs use different certificates. Android rejects an in-place update from those builds to the official release. Before removing an old installation, retain your server URL and access-proxy settings, and create a replacement device credential if you cannot recover the old one. Uninstalling removes local encrypted configuration. Dashboard reports remain on the server. Revoke the old device credential once the replacement is working. Do not interrupt an ongoing battery/reliability test just to switch signing certificates.

## Upgrade a server

Back up the entire persistent data volume, including `secrets.key`, with a SQLite-aware backup or while the container is stopped. Keep the existing environment values and volume. Change the image version, pull, and recreate:

```sh
docker compose pull
docker compose up -d --no-build
```

The default image is pinned to `ghcr.io/ubaimutl/waypoint:1.0.0`; `WAYPOINT_IMAGE` can select another published version or a digest. On Dokploy, change the application image reference and redeploy with its existing environment and persistent mount. Never replace an existing installation's configuration with newly generated credentials as part of an upgrade. Database downgrades may require restoring a matching backup.

## Build and sign an APK

Official signing keys are not in the repository or APK. To sign your own release, provide these environment variables and build:

```sh
export WAYPOINT_KEYSTORE_PATH=/absolute/path/to/your-keystore.p12
export WAYPOINT_KEY_ALIAS=your-key-alias
# Set WAYPOINT_KEYSTORE_PASSWORD and WAYPOINT_KEY_PASSWORD privately.
gradle -p android --no-daemon assembleRelease
```

With no signing configuration, Gradle produces an unsigned release APK. With a keystore path set, all signing values are required. Never commit passwords or keys, and retain an offline backup for future updates. An unsigned APK must not be published as an installable release.

## Build and publish a server image

The `Publish Docker image` workflow runs on `vMAJOR.MINOR.PATCH` tags. It checks the tag against the Android and server version names, runs the server tests, builds Linux AMD64 and ARM64 images, and smoke-tests both published platforms in production configuration as a non-root user. It publishes the versioned image to GHCR and records its digest in the workflow summary. GitHub's short-lived workflow token supplies registry access; no registry password or Android signing key is stored in the repository.

When publishing a new GHCR package for the first time, its owner must make the package public in GitHub's package settings before anonymous pulls work. Repository visibility alone does not change package visibility. Verify the image can be pulled without registry credentials before announcing it.
