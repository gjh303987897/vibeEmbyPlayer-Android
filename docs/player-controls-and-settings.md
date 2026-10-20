# Player controls and settings

Layout choices and FPS/network diagnostics are described in
[Appearance and playback diagnostics](appearance-playback-diagnostics.md).
All sources share the bottom controls and support
[loading phone subtitles](manual-subtitles.md), even without embedded tracks.

All five source screens (Emby/Jellyfin, WebDAV, IPTV, links and local media) use the same
gesture handling and settings panel. Settings and fullscreen share a bottom-right
action dock, with fullscreen at the far right; PiP and audio-track actions stay in
the header. Settings opens a rounded, translucent dark popup above that dock,
without a full-screen dimming scrim. Its height is bounded to the available window
and long pages scroll, including in landscape. Clicking outside or system Back
closes the popup.

The popup keeps a local dark palette in both light and dark app themes. Its
custom translucent Surface explicitly provides white content, preventing titles,
plain text and icons from inheriting a light host's black foreground. Secondary
text uses opaque `#D1D1D1`; the `#1C1C1C` panel is 96% opaque. Over a pure-white
video frame, the resulting background is approximately `#252525`, giving normal
primary/secondary text contrast of approximately 15:1 / 10:1. Disabled controls
retain Material 3's distinct disabled appearance. The palette's semantic surface
stays opaque for nested components; only the panel's ListItems use transparent
containers, so their background is not painted twice.

Subtitles and Playback speed
open separate selection pages with a back action; their icon/label/value root rows show the current
subtitle and speed. Subtitles shares the quick dialog's choices: off, available embedded,
external or manually imported tracks, and loading a subtitle from the phone even when
the source has no tracks. Selecting a track or speed applies immediately through
`PlayerSettingsViewModel` and `PlayerManager`, and the bottom controls stay in sync.
The Subtitles settings page also opens separate [appearance and timing pages](subtitle-enhancements.md)
for saved text size/position/background and the current video's signed subtitle offset.
The root menu also opens separate [video sizing and sleep timer pages](video-sizing-and-sleep-timer.md).
Sizing supports fit, crop, original pixels and session pinch zoom. Sleep offers
15/30/60-minute presets, a custom-duration subpage and natural-end stopping.
[Picture-in-picture](picture-in-picture.md) adds a header action and its own
settings page, including an automatic-on-leave preference. In PiP,
the normal chrome/gestures are hidden and system media controls handle playback.
Playback speed uses the same 0.5x, 1x, 1.25x, 1.5x and 2x choices in both entries.
Long-press speed remains a separate setting and selection page; changing normal
playback speed does not change its saved value. Each subpage has its own scroll state,
so scrolling the main settings cannot hide the first choices on entry.
Decoder, performance/diagnostics and layout preferences remain available as
separate pages from the same root menu. No playback capabilities are added by the
visual change, including no YouTube annotations or adaptive-quality selection.
IPTV adds a [route selection page](iptv-multi-source.md) in the root menu, with separate
Routes and Quality tabs for manual source selection and the persisted automatic quality preference.
The button and bottom playback controls share navigation/cutout safe-area padding. The seek
area uses the larger of the left/right safe insets on both sides, so a one-sided landscape
cutout cannot move the primary action away from the physical screen centre. Insets are read
at layout time and remain responsive to system-bar visibility changes. The seek
bar spans the safe width with a 4-dp red elapsed track, a translucent remaining
track and a round thumb (12 dp, 16 dp while dragging). The underlying Material 3
Slider retains its 48-dp touch area, accessibility and drag/commit behavior. A
combined elapsed/total label appears below the bar at the left and previews the
pending seek position while dragging. Live media without a duration stays disabled;
no estimated buffered range is drawn. The former 1000-dp panel width cap is removed
so wide-screen progress reaches the safe edges too.
The transport row keeps play/pause physically centred. The quick actions and
portrait volume slider reserve 104 dp at the right for the two dock actions;
quick actions use a separate row on narrow screens. Title, screen lock, gestures,
PiP integration, video output and playback commands retain their existing behavior.

All sources use the same top bar. Performance readouts occupy a trailing slot beside the
header actions (stacked in portrait); enabling them adds no top padding. Title and actions align
to the top so even a taller FPS/NET readout cannot move them down. The title/subtitle remain at
the top safe inset, and the audio decode
notice occupies its own row underneath. With controls hidden or locked, the readout remains
an independent upper-right overlay. Long title/subtitle text is ellipsized rather than wrapping
and shifting controls.

## Volume and screen lock

Vertical gestures on the right and the volume slider control Android `STREAM_MUSIC` through
`PlaybackVolumeController`. The displayed fraction comes from the actual stream index, including
quantization and fixed-volume devices. `MODIFY_AUDIO_SETTINGS` is a normal manifest permission.
Native IJK volume stays at unity except for audio-focus ducking, avoiding two independent volume
attenuators. Physical volume keys target the music stream while a player screen is present.

Locking disables the existing gesture modifier instead of removing its composable. Removing it
used to dispose `PlayerBrightnessController`, restoring system brightness and causing the visible
brightness jump. The window override now remains across lock/unlock and is restored on navigation
away. Cancelling a long press clears the temporary speed and gesture indicator.

While locked, the unlock icon disappears after three seconds without touch input.
Touching the locked video reveals it and restarts the idle interval after all fingers
are released. The full-screen blocking surface remains active while the icon is hidden;
the revealing gesture cannot also unlock or change playback, brightness, volume or
position. A subsequent press on the visible icon unlocks normally. The shared component
applies this behavior to every source without changing the unlocked controls' visibility.
The full-screen touch observer is attached only while locked. Keeping a pointer-input
node attached while unlocked, even with an immediately returning handler, intercepts
hit testing and prevents the underlying volume/brightness gestures from receiving input.

## Preferences and decoding

`SettingsDataStore` persists hold speeds (1.25x, 1.5x, 2x, 2.5x, 3x, 4x), the performance overlay
toggle and the video decode preference. Defaults are 2x, hidden and hardware, respectively.
The first source preparation waits for persisted preferences, so a saved software preference also
applies on a cold launch. Settings failures are shown in the panel.

Hardware selects IJK `mediacodec-all-videos=1`; software selects `0` on a freshly reset player.
The other codec-specific MediaCodec options default to zero in the pinned IJK sources. Hardware
uses the platform video decoder, which is a dedicated media engine rather than necessarily the
general-purpose GPU. Unsupported hardware formats retain IJK's software fallback. The panel shows
the actual native decoder independently of the preference.

Changing the mode reloads the current source through `PlayerManager`, retaining credentials and
TLS policy, external subtitle sources, selected audio/subtitle tracks, speed and pause state.
On-demand media resumes its position; live media reconnects at the live edge. Download accounting
continues for the same viewing session. All decoding remains on the client.

Each mode change requests a fresh `SurfaceView` and waits for its holder before preparing.
IJK's default RV32 software output locks the native window for CPU drawing, which keeps that
BufferQueue connected to the CPU until its Surface is destroyed. Reusing it for MediaCodec
failed on the test phone (`nativeWindowConnect: -22`) and silently fell back to software.
The Surface generation survives source-state resets; obsolete holder-destroy callbacks cannot
detach the replacement. Pending play/pause actions also update the reload's restoration intent.

The same rule applies when IJK automatically selects AVCodec for an unsupported hardware
format while the saved preference still says hardware. The manager records the actual software
decoder's holder when preparation completes, retains it across repeated `beginLoading()` calls,
and replaces that Surface before the next hardware preparation. Resetting the native player
does not erase the Surface's CPU ownership. A newly created Surface used for lifecycle recovery
is bound only after resetting the old decoder, so the old software renderer cannot claim it.
Preparation also waits if the initial view has not arrived or another request supersedes a
pending Surface replacement. These rules follow Android's
[Surface producer lifetime](https://source.android.com/docs/core/graphics/arch-sh).

`automaticSoftwareFallbackThenHardwareReplacesSurfaceAcrossSourceChanges` uses a generated
High 10 fixture to exercise an automatic software fallback, regular and superseded source
changes, and actual MediaCodec colour-bar output. `videoPreparationWaitsForSurfaceWhenViewArrivesAfterPlaybackRequest`
covers a playback request arriving before the view. Neither case changes the saved decoder preference.
Selecting the already-selected audio track is skipped because pinned IJK otherwise reopens
the stream and can race its existing decoder thread.

## Performance overlay

The overlay stays at the upper right when controls are hidden or locked. Sampling runs on IO
every two seconds and stops when the player UI has no active lifecycle subscriber or the setting
is disabled. CPU is process CPU time divided by elapsed time and the available processor count;
100% represents all CPU cores available to this process, not one core or the entire device.

GPU reads the KGSL driver busy/total-time counter only when the application can read it. Devices
without that interface, permission-restricted drivers and invalid samples display unavailable.
There is no root, ADB, private API, frequency-to-utilization conversion or fabricated zero. GPU
load is device-wide and does not measure dedicated video-decoder utilization. The supported
counter family is intentionally explicit; arbitrary vendor sysfs values are not assumed to have
the same units.

## Validation

Popup contrast follow-up completed on 2026-10-21: `assembleDebug`,
`assembleDebugAndroidTest` and `lint` pass (zero errors, the same three warnings).
On HUAWEI TAS-AL00 / API 31, the isolated package is checked under light and dark
app themes. Screenshots cover the root menu, subtitle selection/appearance/timing,
speed/hold speed, sizing, decoder, performance, layout, PiP, sleep presets and the
custom-duration field. Invalid input (0) shows a readable error; valid input (2)
shows white input text and a black-on-white enabled action. No timer is started.
Performance switches are checked off/on and restored, and the dark root/subtitle
pages are also checked in landscape over generated colour bars. Theme and USB
stay-awake settings are restored afterward. IPTV-specific route content is not
part of this visual device pass; it inherits the same scoped palette.

Menu/progress/fullscreen and idle-lock update verified on 2026-10-20:
`assembleDebug`, `assembleDebugAndroidTest`, all 117 JVM tests and `lint` pass
(zero errors, the three existing warnings). On HUAWEI TAS-AL00 / API 31,
all four tests in `PlayerControlsTest` and `PlayerSettingsSelectionTest` pass.
They cover actual music-stream changes, brightness preservation, initial and repeated
three-second idle hiding, holding a finger down, blocked locked swipes, reveal without
unlocking, a subsequent unlock, pinch gestures, and hold-speed restoration. The
selection test exercises the production popup in portrait/landscape, including
subtitles, native playback speed, sizing/zoom and preset/custom sleep timer navigation.
Accessibility is connected before composition and nodes are refreshed before each
lock-icon check, avoiding stale semantics from the previous visibility state.

The device run found and fixed the unlocked pointer-input interception described above.
Manual checks use generated colour bars over a USB-forwarded local HTTP endpoint:
the real player displays video, the dark settings popup anchors above the bottom-right
dock, the new seek bar accepts a drag to approximately 06:25 in a ten-minute file,
and fullscreen enters/exits immersive mode without restarting playback. On the 2340-pixel
landscape display, play/pause remains centred at approximately x=1170; settings and
fullscreen stay at the lower right. Screen captures confirm both initial and post-hold
idle hiding. The isolated application's ID preserves the original app's data.
Temporary USB stay-awake/forwarding settings are restored after the run.
Large fonts, other cutout geometries and custom-duration keyboard entry remain outside
this device run's coverage.

Build and lint tasks and the JVM suite must pass. `PlaybackPerformanceTest` covers core
normalization, invalid sampling intervals and busy/total counter validation.
`PlayerControlsTest` dispatches touchscreen events through the Activity decor view, checks the system music volume, clicks
the lock/unlock button while checking the window brightness override, and checks hold-speed
restoration. `PlayerManagerPlaybackTest` exercises paused software/hardware switching with
position/audio-track preservation and actual Surface pixels, alongside the existing HTTP HLS,
MP4/MKV and native audio regressions. Device execution is recorded separately from APK builds.

The decoder-switch fixture is a 30-second lossless remux of the existing generated AC-3 video:

```text
ffmpeg -stream_loop 7 -i video_sample_ac3.mp4 -t 30 -map 0 -c copy video_sample_controls.mp4
```

Validated on 2026-10-16: `assembleDebug`, `lint` (zero errors, three existing warnings) and
37 JVM tests pass. HUAWEI TAS-AL00 / API 31 / ARM64 passes all seven instrumentation tests.
Native decoder events confirm MediaCodec (2) -> AVCodec (1) -> MediaCodec (2), with actual
Surface pixels and advancing progress after both reloads. On the configured IPTV source,
the settings panel also switches both ways and reports the actual decoder. Manual checks
cover speed-page navigation, the CPU/GPU overlay while locked and fullscreen controls.
CPU sampling works on this phone; GPU is unavailable because its driver counters are not
readable by an ordinary application. Device test APKs use a separate application ID and
leave the installed release application's data intact.

Layout regression verified on 2026-10-17: final `assembleDebug`, `lint` and all 37 JVM tests
pass. The same HUAWEI phone plays a generated ten-minute AC-3/MP4 sample through a local USB
HTTP forwarding endpoint in the isolated application. Portrait play/pause is centred at
x=540 on the 1080-pixel display; landscape play/pause is centred at x=1170 on the 2340-pixel
display, including the one-sided cutout. The seek bar and timestamps have matching left/right
margins, and the settings action is at the bottom right. Enabling/disabling performance leaves
the landscape title bounds unchanged at `[249,36][640,108]`. Checks also cover the portrait
stacked readout, dismissed audio notice, lock/unlock readout visibility and settings access.

Settings selection entries verified on 2026-10-19: `assembleDebug`,
`assembleDebugAndroidTest`, all 95 JVM tests and `lint` pass (zero errors, three existing
warnings). HUAWEI TAS-AL00 / Android 12 passes `PlayerSettingsSelectionTest` in portrait
and landscape with generated AV1 colour bars, embedded text and a parsed local subtitle.
The test clicks the production settings, checks the native IJK rate at 1.5x and after
returning to 1x through the quick control, preserves pause and the saved hold-speed setting,
and checks that releasing a temporary speed restores the newly selected regular rate.
It verifies native embedded-stream selection and visible caption text, local subtitle
selection, clearing captions, root-row summaries, scrolling to the phone import action,
and subpage back navigation. Screenshots show the selected speed and subtitle in both
orientations. The test uses synthetic media only; it does not browse configured services
or exercise choosing a new document in the system file picker. User subtitle preference,
orientation and the temporary USB stay-awake setting are restored after validation.

## References

- [Android media volume](https://developer.android.com/media/platform/output)
- [Window brightness](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#screenBrightness)
- [Process CPU time](https://developer.android.com/reference/android/os/Process#getElapsedCpuTime())
- [Compose menus and anchored popups](https://developer.android.com/develop/ui/compose/components/menu)
- [Material 3 Slider customization](https://developer.android.com/develop/ui/compose/components/slider)
- [Compose single-selection radio buttons](https://developer.android.com/develop/ui/compose/components/radio-button)
- [Compose interaction testing](https://developer.android.com/develop/ui/compose/testing/apis)
- [Material Surface content-color inheritance](https://github.com/androidx/androidx/blob/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/Surface.kt)
- [Android Surface ownership](https://source.android.com/docs/core/graphics/arch-sh)
- [Compose safe drawing insets](https://developer.android.com/develop/ui/compose/system/insets-ui)
- [Pinned IJK options](https://github.com/bilibili/ijkplayer/blob/k0.8.8/ijkmedia/ijkplayer/ff_ffplay_options.h)
- [KGSL busy/total-time counter](https://android.googlesource.com/kernel/msm/+/86e532604d9465e4b0fcf69a150a11185f606ff1/drivers/gpu/msm/kgsl_pwrctrl.c)
