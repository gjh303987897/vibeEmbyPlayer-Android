# Playback queue, previous/next and autoplay

The shared player offers **Settings > Playback queue**. It lists the current
session in order, highlights the selected video, scrolls to it on opening and
allows direct selection. Previous/next actions flank the existing centred
transport controls when the queue contains more than one item. Actions at the
first/last boundary are disabled; the queue never wraps. Framework MediaSession
skip callbacks and available skip actions use the same queue.

## Sources and lifetime

- Emby/Jellyfin episodes use the existing authenticated season-episode query,
  ordered by numeric episode index. This queue stays within the current season.
  A movie remains a single-item queue.
- Opening a WebDAV video snapshots the current directory's video rows in their
  displayed order, including encrypted HLS/TSSL manifests. Folders and audio
  rows are excluded. Existing authenticated playback and TSSL preparation are
  reused for each selection; credentials are never copied into queue labels.
- Opening local media from a folder snapshots its displayed file order. SAF
  document grants and the existing local encrypted-HLS preparation still apply.
- IPTV uses the service's merged logical channel list for manual switching;
  route/quality selection continues inside the selected channel. A live stream
  reaching EOF does not automatically switch channels.
- A direct link or a file opened without folder context starts with one item.
  Queue snapshots are in memory, scoped to the active player owner, and cleared
  when leaving playback. They are not restored across process death. An absent
  or failed season listing leaves the selected item playable as a single item.

[Directory sorting](directory-sorting.md) controls the WebDAV/local displayed
snapshot used when opening a video, including name, size and modification-time
ordering. The active session's queue keeps that order until a new session starts.

## End and transition rules

`PlayerManager` handles IJK's actual completion callback. Screens no longer infer
EOF from being within 500 ms of the reported duration. Seeking near the end,
pausing, stopping, decoder reloads and HTTP/decoder errors do not advance the
queue. A native completion is delivered once per playback attempt. Errors remain
visible so the user can choose another item manually.
After actual native EOF, the displayed position is clamped to the reported
duration: IJK's final packet clock on the device could stop 166 ms short of the
container duration. This does not infer completion from a near-end seek.

Autoplay is enabled by default and independently persisted in DataStore under
`player_auto_play_next`. Turning it off leaves manual queue navigation available.
When enabled, a normal completion selects the next item after finalising the
current item's playback report/history. The final item remains on screen.
Each selection keeps the source's existing resume-position policy.
Pressing play after EOF reloads that item through its existing source resolver.

An active countdown keeps its original elapsed-time deadline across manual and
automatic transitions. **Stop after current video** overrides autoplay. A timer
that expires during preparation keeps the next item paused. Explicitly starting
a new playback session or leaving playback retains the existing timer-reset
behavior.

Source ViewModels cancel superseded preparation jobs, snapshot the outgoing
item's identity/progress/bytes before resetting the player, and serialize final
history writes before recording the next occurrence. Old queue owners cannot
overwrite or detach a newer source. Per-item subtitles and TSSL proxy ownership
are reset/released on transitions; regular playback speed and decoder settings
continue to use the shared manager.
System stop clears the queue and cancels source preparation as well as native
preparation, so a slow request cannot resume playback after stop. Locking a
private service clears its queue, including while an encrypted item is preparing.

## Reference behavior

Qt's `VIBEDOCS/WebDAV.md` (audio queue rules),
`VIBEDOCS/PlayerRuntime.md` (explicit EOF versus manual replacement), and
`AppViewModel::advanceWebDavAudioPlayback` / `skipWebDavAudioTrack` were read.
This Android change applies ordered queues to video without adding Qt's audio
repeat modes or its failure-skipping policy.

Official references consulted:

- [Compose lazy lists](https://developer.android.com/develop/ui/compose/lists)
- [Framework MediaSession callbacks](https://developer.android.com/reference/android/media/session/MediaSession.Callback)
- [Storage Access Framework](https://developer.android.com/guide/topics/providers/document-provider)
- [Emby TV show endpoints](https://dev.emby.media/reference/RestAPI/TvShowsService.html)
- [Jellyfin TV show controller](https://github.com/jellyfin/jellyfin/blob/master/Jellyfin.Api/Controllers/TvShowsController.cs)

## Validation

`PlaybackQueueTest` covers source snapshot isolation, duplicate IDs, list order,
current selection preservation after asynchronous updates, manual boundaries,
completion-before-selection ordering, autoplay off/end-of-list behavior, live
channel restrictions, replay and stale-owner cleanup. `SleepTimerTest` covers
retaining a countdown deadline through queued videos and stop-after-video
priority. Native EOF/autoplay and production UI tests are maintained separately
from local JVM/build validation; compiling an instrumentation APK is not a device
test result.

Local validation on 2026-10-21: `assembleDebug`, `testDebugUnitTest` (all 135 JVM
tests), `lint` (zero errors, three existing warnings), and
`assembleDebugAndroidTest` passed. `PlayerManagerPlaybackTest` includes a generated
local-video queue regression for paused seeks near EOF, native EOF autoplay,
countdown retention, stop-after-video priority, autoplay off, rapid selection and
stop during source preparation. `PlaybackQueueSettingsTest` compiles checks for
a 120-item lazy queue, current-item highlighting, boundary buttons, saved autoplay
and centred transport controls in portrait/landscape.

On 2026-10-22 both new queue instrumentation cases passed on Huawei TAS-AL00 /
Android 12 (API 31). Actual native EOF advanced the generated local queue; a
paused seek near EOF did not advance; the countdown survived transition and
stop-after-video took priority. Autoplay off left the completed item paused at
full duration. Rapid selections resolved the latest item; stop cancelled pending
source loading and detached the queue. Actual Surface colour pixels were checked
after the automatic transition. The UI test passed all 120-item selection,
highlight, persistence, boundary and centred-button checks in both orientations.
The separate directory test also passed queue staging from all WebDAV/local
sort modes. Actual authenticated server season/route selection, WebDAV/TSSL
playback transitions and queue transitions in PiP remain outside this test run.
See [device validation](device-validation-2026-10-22.md).
