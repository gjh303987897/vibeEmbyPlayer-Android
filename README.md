# VibePlayer (Android)

[English](README.md) | [简体中文](README.zh-CN.md)

VibePlayer is a native Android media center: Emby, Jellyfin, WebDAV, IPTV, local folders and plain
media links all browse and play through one player stack. It is written in Kotlin with Jetpack
Compose and Media3/ExoPlayer — no Qt, no Electron, no WebView, no libmpv.

This project is the Android rewrite of
[vibePlayerQT](vibeEmbyPlayerQT/README.md), the Qt Quick desktop client that lives in this
repository as a nested reference checkout. The desktop sources and `vibeEmbyPlayerQT/VIBEDOCS/`
document the behaviour this app reproduces, so protocol and format decisions are never guessed.

The app is still under active development: `0.1.0`, `versionCode 1`, no store release yet.

## Highlights

- Native Kotlin + Jetpack Compose UI on Material 3, with Material You dynamic colour and a static
  fallback palette.
- Media3 / ExoPlayer as the single playback core, owned by one `PlayerManager`; every source
  (online, local, WebDAV, IPTV, link, encrypted HLS) goes through it.
- Emby and Jellyfin: login, multiple servers and accounts, libraries, search, continue watching,
  details with seasons and episodes, resume plus server-side progress reporting.
- Automatic audio fallback: when the device cannot decode a title's audio (AC-3, E-AC-3, DTS,
  TrueHD …), the app asks the server to re-encode audio only and stream-copy the video, instead of
  silently playing the picture with no sound.
- WebDAV: PROPFIND browsing, MKCOL, upload, download via a foreground transfer service, and direct
  playback.
- Encrypted HLS packages: plays `.m3u8s` directories and `.m3u8sp` single-file containers produced
  by the desktop client, through a local loopback proxy that decrypts and authenticates each segment.
- TSSL management on-device: package an HLS folder, import and verify packages, back up and restore
  to WebDAV.
- IPTV via M3U/M3U8 import with groups, search and favourites.
- Local folders through the Storage Access Framework, plus HTTP/HTTPS direct and HLS link playback.
- Unified playback history across every source, with watch-time and traffic statistics.
- Foreground services for playback and transfers, notifications, media session and lock-screen
  controls.
- Dark / light / system theme, per-app English and Chinese UI, in-app language override, and an
  optional PIN-protected privacy mode.

## Project status

| Area | Status |
| --- | --- |
| Emby | Login, servers, libraries, search, continue watching, details, seasons/episodes, playback URL creation, resume and progress reporting implemented. |
| Jellyfin | Same surface as Emby, using the `MediaBrowser` auth scheme. |
| WebDAV | PROPFIND browsing, MKCOL, upload, background download with transfer list, video playback and encrypted package metadata implemented. |
| Encrypted HLS | `.m3u8s` and `.m3u8sp` playback implemented through a loopback decryption proxy with per-segment SHA-256 and AES-256-GCM verification. |
| TSSL | v2/v3/v4 read and write, on-device packaging, import validation, WebDAV backup and restore implemented. |
| IPTV | M3U/M3U8 import through SAF, groups, search, favourites and playback implemented. |
| Local media | SAF folder picker, folder browsing and playback implemented; permissions are re-checked per access. |
| Link playback | HTTP/HTTPS direct media and HLS playback, history and statistics implemented. |
| Scheduled playback | Not implemented — the desktop keep-alive scheduling feature is intentionally out of scope on mobile. |
| Auto-update | Not implemented — mobile distribution goes through app stores. |
| SMB | Not implemented and currently out of scope. |

## Architecture

Layered MVVM + Repository with unidirectional data flow. Compose observes `StateFlow`; the UI layer
never touches the network, the database or the player directly.

```text
Compose UI
  -> ViewModel (StateFlow)
    -> UseCase / Service
      -> Repository
        -> DataSource (network / Room / DataStore / player)
```

```text
app/src/main/java/com/vibeplayer/app/
  data/local/       Room database, DAOs, DataStore, Keystore-backed secret store
  data/remote/      OkHttp transport, Emby and Jellyfin clients, WebDAV and IPTV clients
  data/repository/  single entry point per source, shared by several ViewModels
  domain/           link, local and TSSL use cases
  player/           PlayerManager (Media3), playback sessions, HTTP header plumbing
  player/hls/       encrypted HLS: loopback proxy, TAR/CBOR index, manifest metadata
  domain/tssl/      TSSL document model and HLS packager
  service/          PlaybackService and TransferService foreground services
  security/         Keystore secret store and privacy/PIN manager
  ui/               home, library, details, player, webdav, iptv, local, link,
                    transfer, history, tssl, services, settings, theme, navigation
  di/               Hilt modules
```

## Tech stack

- Kotlin 2.0.21, JVM target 17
- AGP 8.7.3, Gradle 8.10.2, compileSdk / targetSdk 35, **minSdk 26**
- Jetpack Compose (BOM 2024.12.01) with Material 3 and Material You dynamic colour
- Media3 1.5.0 (ExoPlayer, HLS, DASH, session, OkHttp data source)
- Hilt 2.52 for injection, Navigation Compose 2.8.5
- Room 2.6.1, DataStore 1.1.1, Coil 2.7.0
- OkHttp 4.12.0 with kotlinx.serialization (JSON and CBOR) — no Retrofit
- Coroutines and Flow throughout; no blocking call on the main thread

## Getting started

### Requirements

- JDK 17 (a full JDK, not a JRE-only or JBR-only runtime)
- Android SDK with platform 35 and build-tools 35.x
- The Gradle wrapper — no local Gradle install needed

`local.properties` must point at your SDK:

```properties
sdk.dir=C\:\\Users\\you\\AppData\\Local\\Android\\Sdk
```

### Build

```bash
./gradlew assembleDebug      # debug APK, signed with the Android Debug certificate
./gradlew assembleRelease    # requires the release signing environment variables
./gradlew testDebugUnitTest  # unit tests
./gradlew lintDebug          # lint report under app/build/reports/
```

The debug APK lands at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

The debug build forces signature schemes v1, v2 and v3 on purpose. AGP drops the v1 (JAR) block once
`minSdk >= 24`, and several on-device installers — OEM file managers, sideload tools, some
`pm install` paths — read the JAR block first and reject the APK as unsigned.

### Release signing

Release builds are always signed and fail loudly instead of emitting an unsigned artifact. Set these
before running `./gradlew assembleRelease`:

| Variable | Meaning |
| --- | --- |
| `KEYSTORE_FILE` | path to the `.jks` keystore |
| `KEYSTORE_PASSWORD` | keystore password |
| `KEY_ALIAS` | key alias |
| `KEY_PASSWORD` | private-key password |

Never commit a keystore or a password.

## CI

`.github/workflows/android-build.yml` builds on every push and pull request, then:

- uploads `app-debug-apk-android-test-signed`, after verifying the APK really carries the Android
  Debug test certificate;
- builds and uploads `app-release-apk`, refusing to upload anything unsigned;
- runs lint.

## Documentation

- `docs/tssl-v4-m3u8sp.md` — TSSL v4 and `.m3u8sp` container layout, playback and verification flow
- `docs/self-signed-certificates.md` — trusting self-signed media servers
- `docs/player-fullscreen-audio-tracks.md` — fullscreen behaviour and audio track selection
- `docs/user-messages.md` — user-facing error wording
- `fix.md` — bug log: symptom, root cause, fix and the measurements that prove it
- `AGENTS.md` — architecture rules and conventions for working on this codebase
- `vibeEmbyPlayerQT/VIBEDOCS/` — the desktop reference the protocols and formats come from

## Security notes

- Emby/Jellyfin access tokens, WebDAV passwords and opt-in saved credentials are sealed with
  AES-256-GCM under a non-exportable Android Keystore key (`KeystoreSecretStore`). Nothing is stored
  in clear text.
- `EncryptedSharedPreferences` is retained only to migrate installs written by older versions; new
  writes go to the Keystore-backed store, which degrades per entry instead of failing as a whole.
- Logs never contain passwords, tokens, cookies or full token-bearing playback URLs.
- Trusting self-signed certificates is an explicit per-server opt-in, warned about in the UI,
  because it removes hostname and chain validation.
- Do not commit real server credentials, tokens, keystores or token-bearing logs.

## Roadmap

- More playback error mapping and richer external subtitle handling.
- Larger device matrix for encrypted HLS and WebDAV servers.
- Continuous playback improvements for large libraries and long seek chains.
- Expand automated coverage around protocol parsing and the TSSL container.
- Re-evaluate SMB support on a proven library if demand appears.
