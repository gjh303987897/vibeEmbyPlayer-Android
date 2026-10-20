# Picture-in-picture playback

Android PiP is available from the player header button and from
**Player settings → Picture-in-picture**. It uses the existing
`MainActivity`, IJK surface and framework media session, with no floating-overlay
permission, extra playback Activity, new media backend or native-library changes.
Emby/Jellyfin, WebDAV/TSSL, IPTV, links and local video share the integration.
The reviewed Qt player runtime/controller has no equivalent Android system-window
feature; this is an Android addition.

## Entry and user settings

Manual and automatic entry require a registered playback screen, a prepared
video with known video dimensions, active playback, system PiP support and the
app's system PiP permission. Audio-only, paused, stopped, failed or sleep-expired
playback cannot enter. Private videos remain in the full player and cannot enter
PiP; the existing private-content lock and `FLAG_SECURE` behavior are retained.

**Enter when leaving the app** is enabled by default and saved in DataStore.
Turning it off leaves the explicit entry button available. Android 8–11 uses
`onUserLeaveHint`; Android 12+ publishes `setAutoEnterEnabled` proactively for
system Home/gesture transitions. Returning from system app settings refreshes
the permission check. The settings link uses the public
`ACTION_APPLICATION_DETAILS_SETTINGS` action with the app's package URI, where
the device exposes its PiP access control; no hidden Settings action is used.

Player settings, audio/subtitle dialogs and speed/volume menus suppress automatic
entry while visible. Launching the subtitle document picker or system app settings
also blocks automatic entry until returning, so choosing a document does not
unexpectedly shrink the player. Manual entry from the PiP settings page remains
possible while that settings panel is open. Unsupported devices show an explanation and
do not expose the header action; denied access is explained in the settings page.

## Window and lifecycle

The manifest declares PiP/resizing support and retains the existing
orientation/screen-layout configuration handling, avoiding Activity recreation
for normal PiP transitions. `PictureInPictureController` belongs to the Activity;
the playback session and sleep timer remain owned by `PlayerManager`.

While entering/in PiP, the screens hide their normal chrome, lock button,
performance readouts, settings and gesture feedback. The gesture layer stays
composed but disabled, retaining the existing brightness lifetime semantics.
Video temporarily uses Fit and 1× zoom; returning restores the saved picture mode,
current zoom, lock/fullscreen state and tracks. Text captions use a compact
12-sp-or-smaller layout, at most three lines and a small bottom clearance, without
changing saved subtitle appearance. Bitmap captions keep their video coordinates.

PiP aspect ratio includes sample aspect ratio and clamps to the platform's
inclusive 1:2.39–2.39:1 interval. Fraction rounding is bounded too, including
ratios just inside the lower boundary. Ultrawide/tall video is letterboxed rather
than supplying an invalid window ratio. Native view layout reports the visible
video bounds for `sourceRectHint` when entering, resizing and expanding.

Android 15's early transition callback hides chrome during entry animation. The
callback itself exists on Android 12+, but `isTransitioningToPip` is only accessed
on API 35+. Older versions use normal mode callbacks. Failed/cancelled entry has
a bounded fallback to restore chrome instead of leaving controls hidden.

PiP pauses the Activity without pausing the player. The existing active
`MediaSession` supplies system play/pause controls and continues to handle audio
focus. Returning to the player does not request a new media URL or restart the
session. If Android actually stops the PiP Activity (closing its window or making
it no longer visible), playback is paused and audio focus released; normal
foreground/background behavior outside a PiP session stays unchanged. Stashing
the window at a screen edge is handled by the system and does not itself count
as closing the player.

Sleep deadlines continue and pause through the shared manager. Source, caption
and decoder recovery use the existing playback lifecycle. If navigation removes
the playback destination during entry/PiP, other app pages are masked and the
PiP Activity is closed, preventing a library or settings page appearing in the
video window. Private playback becoming active inside PiP also closes the window.

## Validation and remaining coverage

Local verification on 2026-10-20 passed `:app:assembleDebug`,
`:app:assembleDebugAndroidTest`, `:app:testDebugUnitTest` and `:app:lint`.
All 117 JVM tests passed with no failures, errors or skipped tests. Lint reports
zero errors and the same three existing warnings (`CustomX509TrustManager`,
`InsecureBaseConfiguration`, `ViewConstructor`), with no new PiP warnings.

`PictureInPicturePolicyTest` covers eligible playback, unsupported/denied/private
states, audio/paused/failed/sleep-expired states, modal/automatic-entry suppression,
portrait/landscape/SAR ratios, extreme dimensions, rounding at platform limits
and chrome visibility during mode transitions.

`PictureInPicturePlaybackTest` is a synthetic-media device regression for manual
entry, continuing progress in PiP, expansion without resetting zoom/timer state
and pausing on close. It is compiled locally, not executed for this change. No
phone settings or installed applications are changed by local verification.

Device validation is still required for real Surface pixels/continued audio,
system play/pause, Home entry on API 26–30 versus 31+, Android 15 gesture
cancellation, OEM permission denial, fullscreen/portrait return, subtitle picker
suppression, subtitles, sleep expiry, close/lock-screen behavior and privacy-lock
navigation. JVM policy tests and APK compilation do not establish these behaviors.

## Official references

- [Android PiP guide](https://developer.android.com/develop/ui/views/picture-in-picture)
- [Compose PiP setup and safe aspect ratios](https://developer.android.com/develop/ui/compose/system/pip-setup)
- [Appropriate PiP entry conditions](https://developer.android.com/develop/ui/compose/system/pip-enter)
- [Activity PiP callbacks](https://developer.android.com/reference/android/app/Activity#onPictureInPictureUiStateChanged(android.app.PictureInPictureUiState))
- [Public application-details settings action](https://developer.android.com/reference/android/provider/Settings#ACTION_APPLICATION_DETAILS_SETTINGS)
