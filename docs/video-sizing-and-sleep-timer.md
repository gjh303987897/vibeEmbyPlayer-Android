# Video sizing and sleep timer

All player sources (Emby/Jellyfin, WebDAV/TSSL, local files, links and IPTV) share
these settings in the **Player settings** menu. No source protocol or native
decoder library changes are needed. The reviewed Qt `PlayerRuntime.md` and
`PlayerController` retain aspect-fit video in a fixed player surface, without
equivalent sizing/sleep controls; these are Android additions.

## Picture size

**Video size and zoom** opens a dedicated page:

- **Fit** is the default and shows the whole video with letterboxing as needed.
- **Crop to fill** scales uniformly until the viewport is covered, cropping the
  extra picture at the edges.
- **Original size** displays video pixels, corrected for sample aspect ratio,
  instead of expanding a small video to the screen. Integer surface dimensions
  can introduce approximately one pixel of rounding.

The selected mode is saved globally in DataStore, with Fit as the fallback for
an absent/invalid value. A slider or a two-finger pinch changes centred zoom from
0.5× to 4× relative to that mode. Selecting a mode or pressing Reset zoom returns
zoom to 1×. Zoom is session-only: opening another video resets it, while rotation,
Surface recovery and decoder reload retain it.

`IjkPlayerView` measures the same aspect-fit SurfaceView as before and applies a
view transform. Zoom does not grow decoder buffers or replace/reconnect the
Surface. Bitmap subtitle drawing uses the same transformed video rectangle;
text subtitles and controls retain their own size and layout. The parent view
clips content to the player viewport. Pinching does not pan or rotate the video.

Two pointers take over the existing gesture loop, cancel a held speed boost and
suppress single-finger seek commits, volume/brightness actions and tap handling
until both fingers are lifted. Locking disables pinch along with other gestures.
The usual one-finger interactions remain unchanged.

## Sleep timer

The separate **Sleep timer** page offers Off, 15/30/60 minutes, a Custom duration
page (1–1440 whole minutes), and **Stop after this video**. Only one timer mode
can be active. New choices replace the prior timer, and Off cancels it without
resuming playback. Settings show the live remaining hours/minutes/seconds.

The countdown uses `SystemClock.elapsedRealtime()`, not playback position,
playback speed, wall time or the number of timer callbacks. Pausing does not stop
the countdown. If execution is delayed, the next tick compares the actual elapsed
deadline instead of extending the timer. The app-scoped player owns the timer,
so it does not depend on the settings panel being visible. It uses the existing
playback process/service; there is no exact-alarm permission, scheduled playback
or automatic start.

At expiry, `PlayerManager.pause()` clears playback intent and audio focus, keeping
the current position for a manual resume. Expiry also clears the buffering
indicator and releases the player's keep-screen-on request even if a pending
network read has not finished. Later preparation/seek callbacks must not restore
the previous play intent. Pressing Play acknowledges expiry and resumes normally.

Stop after this video waits for IJK's natural completion callback, not a
near-end position estimate, and clears play intent before any resume. It requires
a prepared video with a known duration; live/unknown-duration streams can use
the countdown options. A natural completion also cancels a remaining countdown.
Timer setup is disabled until playback is prepared.

Timers and zoom survive a decoder/Surface reload or an IPTV route change for the
same video/channel. A native playback error retains the countdown, so automatic
IPTV recovery cannot discard the deadline; expiry also blocks a late route
restart from resuming playback. Opening another source, stopping/leaving playback
or releasing the player cancels the timer. Timers are not persisted across process death. Android may
delay app execution under background restrictions; this is not an alarm-clock
service and does not independently wake a killed playback process.

## Validation

Device follow-up on 2026-10-20: HUAWEI TAS-AL00 / API 31 passes
`PlayerControlsTest` (including pinch gestures) and `PlayerSettingsSelectionTest`
with the new popup in portrait/landscape. The latter checks sizing, zoom/reset,
timer presets, custom-duration selection, after-video mode and cancellation.
These checks do not cover real-time timer expiry or the separate manager reload
test below. The complete shared-controls run is recorded in
[Player controls and settings](player-controls-and-settings.md#validation).

Local validation on 2026-10-20: `assembleDebug`, `assembleDebugAndroidTest`,
all 110 JVM tests and `lint` pass. Lint reports zero errors and the same three
existing warnings (TLS policy/trust manager and the custom-view constructor).
The final check includes the same-channel IPTV session-preservation change.
No APKs were installed and no device tests were executed for this change.

`VideoPresentationTest` covers portrait/wide fit, both-axis crop coverage,
original pixel size, sample aspect ratio, zoom without buffer growth, missing
metadata and invalid zoom bounds. `SleepTimerTest` covers elapsed deadlines,
delayed ticks, cancellation/rearming, natural completion, after-video mode,
one-shot expiry, custom limits and overflow boundaries.

Device regressions were initially compiled without execution (see the follow-up above):

- `PlayerControlsTest.pinchZoomDoesNotSeekChangeVolumeTogglePlaybackOrDimTheWindow`
  dispatches actual two-pointer touchscreen events through the Activity view tree.
- `PlayerSettingsSelectionTest` exercises mode selection, zoom/reset, preset and
  custom timers, after-video selection, cancellation and back navigation in both
  orientations, alongside the existing subtitle/speed checks.
- `PlayerManagerPlaybackTest.sameVideoReloadKeepsSleepDeadlineAndZoomAndNewVideoClearsThem`
  prepares actual video to check paused route-style reload preservation and new-source reset.

Real SurfaceView cropping/pixel output on hardware and software decoders, bitmap
subtitle alignment, screen-off expiry, and natural-end timing still need device
validation. JVM geometry/state-machine tests do not establish those behaviors.

## References

- [Android SurfaceView](https://developer.android.com/reference/android/view/SurfaceView)
- [Compose multi-touch input](https://developer.android.com/develop/ui/compose/touch-input/pointer-input/multi-touch)
- [Android elapsed real time](https://developer.android.com/reference/android/os/SystemClock#elapsedRealtime())
- [Keep the screen on](https://developer.android.com/develop/background-work/background-tasks/awake/screen-on)
