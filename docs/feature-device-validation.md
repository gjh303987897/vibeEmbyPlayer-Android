# Subtitle, history and appearance device validation

## Environment and scope

- Date: 2026-10-18.
- Device: Huawei TAS-AL00, Android 12.
- Application: isolated `com.vibeplayer.app.regression` validation package. The
  installed production application's data was preserved.
- Scope: phone subtitle import, history retention, appearance and playback
  diagnostics. Other migration differences are outside this validation.

Windows initially had a working WinUSB driver but no `DeviceInterfaceGUIDs`
registration for its ADB interface. Adding the missing registration restored ADB
connectivity. Device identifiers and account information are omitted here.

## Confirmed automated results

The final device run passed all six selected instrumentation tests in 34.864
seconds:

| Test | Coverage |
| --- | --- |
| `HistoryRetentionTest.shorteningRetentionPrunesMirrorsAndStatsAndQueriesAgree` | Immediate cleanup, more than 500 records, public/private history, mirrored Link records, usage statistics and count/query agreement |
| `HistoryRetentionTest.queryFiltersBeforePagingEvenBeforePhysicalCleanup` | SQL cutoff applied before pagination and counting |
| `PlayerChromeLayoutTest.tallerDiagnosticsDoNotMoveTitleOrTopActions` | Taller diagnostic content preserves the header title and action positions |
| `PlayerManagerPlaybackTest.decoderSwitchRetainsPositionPauseAndTracksAndRendersInSoftware` | Real video output, paused-position and track restoration, imported subtitle restoration through decoder changes, subtitle Off/reselection and session isolation |
| `PlayerManagerPlaybackTest.diagnosticsMeasureRenderedFramesAndThrottledNetworkAndResetAfterStop` | Actual output FPS, local NET unavailable, positive NET over multiple throttled HTTP sampling windows, paused FPS zero and diagnostic reset after stop |
| `PlayerManagerPlaybackTest.pausedSurfaceReplacementRedrawsFrameAndRetainsSubtitleSessionInBothDecoders` | Hardware/software decoding at 0 and 15 seconds, new SurfaceView and same-Activity stop/resume, actual colour-bar output, UI/native paused positions, track/session preservation and immediate picker-result import |

To reproduce, install the matching debug application and Android test APKs for
the isolated validation package, unlock the device, and select the classes or
methods listed above with AndroidJUnitRunner. The tests use bundled video
fixtures and an on-device MockWebServer; no media account or external server is
required. Keep the device unlocked while Surface and Compose tests run.

The diagnostic test transfers the 30-second MP4 fixture with HTTP Range support
and an 8 KiB per 250 ms throttle. It requires positive FPS and NET across at least
2.1 seconds, so a single instantaneous prefetch cannot satisfy the assertion.
Paused playback may still buffer: the test requires FPS to become zero, without
incorrectly requiring NET to become zero. The original diagnostics setting is
restored in its `finally` block.

The updated debug build, lint and all 92 JVM unit tests also passed. These local
checks supplement the automated and manual device checks below.

## Confirmed UI results

History retention:

1. Open Settings > History retention, choose 7 days and save. The setting saves
   successfully.
2. Choose Custom and enter `0`, `3651`, or an empty value. Save is disabled for
   each invalid input.
3. Enter `12`, save, force-stop the validation application and reopen it. The
   12-day setting remains selected.

Appearance:

1. Open the appearance settings and select Traditional for Emby and Trendy for
   Jellyfin.
2. Confirm that the two choices remain independent in the settings UI. Actual
   server-home rendering is not established by this check alone.

Service-card scale:

1. Open appearance settings > Cards, select 50%, 100% and 150%, and return to
   the services page after each change.
2. Compare the same card's device UI bounds:

| Scale | Bounds in screen pixels | Width | Height |
| --- | --- | --- | --- |
| 50% | `[60,345][1020,537]` | 960 px | 192 px |
| 100% | `[48,345][1032,585]` | 984 px | 240 px |
| 150% | `[48,345][1032,669]` | 984 px | 324 px |

Screenshots at 50% and 150% showed the cards rendering normally. Reopening the
settings retained 150%; the setting was restored to 100% after this check.

The percentage scales card padding and icons, while font sizes and minimum touch
targets remain accessible. `ServiceCardSize` also preserves a minimum width of
320 dp when space permits and caps the width at the available space. Accordingly,
50% does not halve the screen-width card, and 150% increases its height without
extending it beyond the viewport.

Touch targets:

The original radio option rows measured approximately 24–32 dp on the device.
Layout choices, history retention preset/custom choices and subtitle choices now
use a minimum row height of 48 dp. Updated device UI XML confirms 144 px option
row heights on this device. Existing padding, font styling and full-row selection
behavior are retained.

Phone subtitles through the real system document picker:

- SRT, WebVTT and ASS files loaded successfully from an isolated test folder in
  Downloads. Chinese text rendered correctly; ASS styling was stripped and `\N`
  became a line break.
- A UTF-16 SRT also rendered Chinese text correctly. Seeking from 0 to 15 seconds
  selected the second SRT cue. Off hid the text, and selecting the imported track
  again restored it.
- An invalid SRT reported no valid timed subtitles. A 4 MiB + 1 byte document
  reported the size limit. Existing imported tracks remained available.
- Cancelling the system picker returned to the subtitle dialog without adding a
  track or replacing the selected track.

Player layout and diagnostics:

- Both Traditional and Trendy layouts were inspected in portrait and landscape.
  Playback controls remained centered, progress bars stayed within the viewport,
  settings remained at the lower right, and subtitles cleared the controls.
- Enabling CPU/GPU and FPS/NET together preserved the title's top position. The
  readout remained visible when locked. Local playback reported about 23.8 FPS,
  zero while paused, and NET unavailable. This device exposes no GPU utilization.
- A paused video lost its displayed frame after returning from the document
  picker or recreating its Surface. The fix prepares IJK against the current
  Surface again while preserving the same session, position and tracks. Muted
  preroll waits for the seek rendering event, a positive output-FPS sample (at
  least two outputs) and an actual Surface buffer before returning to pause.
  Merely receiving the first render event or a black initialization buffer is
  insufficient on this device. Paused seeks retain the requested position while
  IJK's clock catches up. The hardware/software regression passed, including
  native-position assertions. A real SRT picker return and a cancelled picker
  were then checked visually: colour bars and Chinese subtitles remained visible,
  playback stayed paused at 00:20, and the selected track remained available.

## Remaining coverage limits

- SSA, GB18030 and alternate document providers were not separately exercised
  on this device. Their parsing is covered by local unit tests.
- Saved-server cards, long server details, action accessibility and drag
  reordering. The isolated validation application has no server configuration, so
  the confirmed scale screenshots do not establish these saved-card behaviors.
- Actual Emby and Jellyfin home rendering with different layout selections. The
  isolated application's lack of configured servers limits this check to the
  independent selections in settings.

Temporary USB stay-awake was restored to its original value `0`. The temporary
portrait rotation lock was restored to the prior `lock 1` setting. Both values
were read back successfully. The validation application was stopped after testing;
its temporary CPU/GPU and diagnostics switches were disabled, card scale was
restored to 100%, retention to 30 days, and layouts to Trendy. The isolated local
fixture folder remains available for manual retesting. No production application
data was replaced.
