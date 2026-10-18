# Appearance and playback diagnostics

Settings > Layouts and card size separates Home, Player and Cards into tabs.

- Emby and Jellyfin home layouts are independent. Trendy uses the suggested hero,
  landscape continue-watching carousel and library rail. Traditional uses a portrait
  continue-watching rail and the library grid. Both use the same repository data
  and navigation, including authenticated artwork and resume actions.
- Player layout applies to every source. Trendy uses an inset rounded panel;
  traditional uses a shorter compact strip. A shared control component preserves
  centered transport controls and access to seeking, speed, volume and subtitles.
  The settings button stays at bottom right with reserved space around controls.
- Service-card size is 50–150% in 5% steps, default 100%. All built-in and saved
  service cards use the same sizing rule. Android bounds the width to the screen
  and a readable minimum, scales layout padding and icons, and retains native
  text scaling and 48 dp button targets. Reordering is unchanged.

Player settings > Performance adds a **Playback diagnostics** switch, separate
from the CPU/GPU switch. The panel remains at the top right when controls are
hidden or locked; enabling it does not add a spacer above the header.
The title and top actions align to the top of the row, so a taller diagnostics
readout cannot move them down. Text subtitles avoid the measured control-panel
height; larger font scales use a separate auxiliary control row.

FPS is IJK's live `getVideoOutputFramesPerSecond()` sample, not nominal source
frame rate. It shows zero while paused and unavailable before preparation or
for audio-only media. NET is media-response bytes received per elapsed second,
sampled once a second using an independent cumulative counter. The counter does
not drain the existing history/usage counters. It resets on item or decoder reload.
Local and local encrypted-HLS playback show NET as unavailable, not disk/loopback
speed. WebDAV encrypted-HLS uses its upstream byte source; those readings update
when each resource or range finishes, so they may be bursty.

Preferences use the existing DataStore. Defaults keep the trendy layouts and
100% card sizing. Diagnostic sampling only runs while enabled with an active
request; stopping playback cancels it. CPU/GPU limitations are unchanged.

Qt references: `VIBEDOCS/SettingsAppearance.md` and
`VIBEDOCS/PlayerRuntime.md`. The Android UI uses Material 3 controls and
[Compose layout constraints](https://developer.android.com/develop/ui/compose/layouts/constraints-modifiers).
IJK metric signatures were checked against the vendored Java source.

Validation: `PlaybackDiagnosticsTest` checks elapsed-time rates, local/unavailable
values, counter resets and invalid FPS. `PlaybackHttpProxyTest` checks received
media-byte accounting alongside existing HTTP/range/HLS tests.
`PlayerChromeLayoutTest` checks that a taller diagnostics panel leaves the title
and top actions at the same vertical position.

Local validation on 2026-10-18: `assembleDebug`, `lint`, `testDebugUnitTest` and
`assembleDebugAndroidTest` passed. All 92 JVM tests passed; lint reported no errors
and three existing warnings. Huawei TAS-AL00 / Android 12 device tests and manual
layout, card-scale, settings persistence and document-picker checks are recorded
in [feature-device-validation.md](feature-device-validation.md), including the
unverified actual Emby/Jellyfin server-home content.
