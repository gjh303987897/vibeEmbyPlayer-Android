# Directory sorting and playback queue device validation

Device: Huawei TAS-AL00, Android 12 / API 31, ARM64. Tests ran in the independent
`com.vibeplayer.app.regression` app and its instrumentation APK. No production
application data, configured credentials or user videos were used.

Final instrumentation result: **OK (3 tests)**, 18.508 seconds.
Raw result: `build/directory-sort-device/final-device-results.log` (ignored build
artifact); selected app/test logs and screenshots are in the same directory.

After the device fix, `assembleDebug`, `assembleDebugAndroidTest`, all **143 JVM
tests** (36 suites, zero failures/errors) and `lint` passed in the normal package
configuration. Local build log: `build/directory-sort-device/final-local-check.log`.

## Coverage

- `DirectorySortDeviceTest`: the production WebDAV and local browse screens,
  six sorting modes each, folders first, actual visible row order, separately
  persisted preferences, recreation in fresh ViewModels, zero extra directory
  requests from sorting, and queue snapshots matching the visible file order.
  The fixtures are a loopback DAV server and a read-only metadata provider in
  the test APK. Screenshots of both menu themes were visually checked.
- `PlaybackQueueSettingsTest`: 120-item scrolling/selection, current highlight,
  first/last boundaries, real saved autoplay preference, previous/next actions,
  and centred play controls in portrait and landscape.
- `PlayerManagerPlaybackTest#localQueueUsesNativeEofAndHonoursAutoplayAndSleepTimer`:
  actual generated H.264 video decoding through the application singleton and
  real Surface; paused seek near EOF; automatic continuation on native EOF;
  countdown retention; stop-after-video priority; autoplay disabled; rapid manual
  selections; cancellation when stopped during source preparation. PixelCopy
  verifies visible video colours after continuation.

## Issue fixed during validation

IJK reported completion with its native clock at 29,850 ms for a 30,016 ms
container. Playback correctly stopped with autoplay disabled, but the progress
bar could remain short of the end. `PlayerManager.updateDerived` now presents
the duration only after the real completion callback. A seek/new playback clears
that completion flag as before. Final device evidence: position and duration
both 30,016 ms, playing false, queue index still 0. The same regression checks
that seeking near EOF while paused never triggers automatic advancement.

## Limits and cleanup

The run covers generated local playback, synthetic DAV listings and production
UI/settings integration on this physical device. It does not certify arbitrary
remote server configurations, SAF picker/grant behavior, Emby/Jellyfin season
queries, IPTV switching, TSSL transitions or queue transitions inside PiP.

Fixture service configurations and generated video files are removed by test
cleanup, and existing sorting/autoplay preferences are restored. USB stay-awake
was temporarily enabled during testing and restored to its original value (0).
The exact temporary Windows ADB-interface GUID registration was restored from
this run's verified backup; no driver was installed. The original app
is never overwritten. Verification APKs remain under the ignored build folder;
normal package build output is restored before delivery.

References: [Android instrumented tests](https://developer.android.com/training/testing/instrumented-tests),
[ContentProvider](https://developer.android.com/reference/android/content/ContentProvider).
