# VibePlayer (Android)

[English](README.md) | [简体中文](README.zh-CN.md)

VibePlayer is a native Android media center: Emby, Jellyfin, WebDAV, IPTV, local folders and plain
media links all browse and play through one player stack. It is written in Kotlin with Jetpack
Compose and IJKPlayer — no Qt, no Electron, no WebView, no libmpv.

This project is the Android rewrite of
[vibePlayerQT](vibeEmbyPlayerQT/README.md), the Qt Quick desktop client that lives in this
repository as a nested reference checkout. The desktop sources and `vibeEmbyPlayerQT/VIBEDOCS/`
document the behaviour this app reproduces, so protocol and format decisions are never guessed.

The app is still under active development: `0.1.0`, `versionCode 1`, no store release yet.

## Highlights

- Native Kotlin + Jetpack Compose UI on Material 3, with Material You dynamic colour and a static
  fallback palette.
- IJKPlayer as the single playback core, owned by one `PlayerManager`; every source
  (online, local, WebDAV, IPTV, link, encrypted HLS) goes through it.
- Emby and Jellyfin: login, multiple servers and accounts, libraries, search, continue watching,
  details with seasons and episodes, resume plus server-side progress reporting.
- Client-side audio decoding: IJK's FFmpeg audio path decodes supported streams **on the phone**,
  while MediaCodec handles supported video. Emby/Jellyfin URLs remain `static=true`; the server
  transcodes nothing. The player displays the selected audio decoder status.
  See `docs/audio-software-decoding.md` for native build requirements and verification limits.
- Subtitle selection for every source, including embedded text, authenticated external SRT/WebVTT,
  and PGS/DVD/DVB bitmap captions positioned within the video image.
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
- Dark / light / system theme, per-app English and Chinese UI, and in-app language override.
- PIN-protected private service cards, history and usage; private playback is stopped on relock.
  Changing a configured PIN requires the current PIN. See [security audit fixes](docs/security-audit-20261023.md)
  for Link history migration, HTTP consent and credential redirect boundaries.

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
| Privacy mode | Private services, history and usage are hidden while locked; Link playback carries the service identity, and changing PIN requires the old PIN. See `docs/security-audit-20261023.md`. |
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
  player/           PlayerManager (IJK), playback sessions, authenticated HTTP proxy
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
 - IJKPlayer 0.8.8 (FFmpeg 3.4, MediaCodec, native SurfaceView)
- Hilt 2.52 for injection, Navigation Compose 2.8.5
- Room 2.6.1, DataStore 1.1.1, Coil 2.7.0
- OkHttp 4.12.0 with kotlinx.serialization (JSON and CBOR) — no Retrofit
- Coroutines and Flow throughout; no blocking call on the main thread

## Getting started

### Requirements

- JDK 17 (a full JDK, not a JRE-only or JBR-only runtime)
- Android SDK with platform 35 and build-tools 34.0.0 plus 35.x
- The Gradle wrapper — no local Gradle install needed

For the verified Windows installation and removal commands, see [Windows Android build environment](docs/windows-android-build-environment.md).

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

IJKPlayer handles direct playback and software decoding through its bundled FFmpeg native libraries. The four ABI AARs are vendored under `third_party/ijkplayer/`; no server-side audio transcoding is used. External subtitles use authenticated OkHttp; embedded text and bitmap subtitles follow the native playback clock. Android audio focus and headset disconnect handling are shared by all sources.

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

- `docs/manual-subtitles.md` — load phone subtitles for every playback source
- `docs/history-retention.md` — configurable history, link history and usage retention
- `docs/appearance-playback-diagnostics.md` — layouts, service-card sizing, FPS and network speed
- `docs/tssl-v4-m3u8sp.md` — TSSL v4 and `.m3u8sp` container layout, playback and verification flow
- `docs/self-signed-certificates.md` — trusting self-signed media servers
- `docs/player-fullscreen-audio-tracks.md` — fullscreen behaviour and audio track selection
- `docs/audio-software-decoding.md` — on-device FFmpeg decoding of AC-3 / E-AC-3 / DTS / TrueHD
- `THIRD_PARTY_LICENSES.md` — IJKPlayer and its bundled FFmpeg (LGPL)
- `docs/user-messages.md` — user-facing error wording
- `fix20260927.md` — open issues found by the 2026-09-27 code review (with file:line evidence) and
  the privacy-mode design it produced: the QT semantics followed, the agreed model (hidden by
  default, PIN reveals, auto-relock on leave) and the PR breakdown
- `fix.md` — bug log: symptom, root cause, fix and the measurements that prove it
- `AGENTS.md` — architecture rules and conventions for working on this codebase
- `vibeEmbyPlayerQT/VIBEDOCS/` — the desktop reference the protocols and formats come from

## Security notes

- Emby/Jellyfin access tokens, WebDAV passwords and opt-in saved credentials are sealed with
  AES-256-GCM under a non-exportable Android Keystore key (`KeystoreSecretStore`). Nothing is stored
  in clear text.
- `EncryptedSharedPreferences` is retained only to migrate installs written by older versions; new
  writes go to the Keystore-backed store, which degrades per entry instead of failing as a whole.
- Logs must never contain passwords, tokens, cookies or full token-bearing playback URLs. Playback
Media and artwork URLs do not carry access tokens; authentication is sent in request headers.
Keeping the HTTP logger disabled in every build further reduces accidental exposure.
- Trusting self-signed certificates is an explicit per-server opt-in, warned about in the UI;
  certificate chain trust is relaxed while hostname verification remains enabled.
- Do not commit real server credentials, tokens, keystores or token-bearing logs.

## Roadmap

- More playback error mapping and richer external subtitle handling.
- Larger device matrix for encrypted HLS and WebDAV servers.
- Continuous playback improvements for large libraries and long seek chains.
- Expand automated coverage around protocol parsing and the TSSL container.
- Re-evaluate SMB support on a proven library if demand appears.
