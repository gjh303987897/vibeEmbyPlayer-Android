# Phone subtitle import

The subtitle button is available for Emby/Jellyfin, WebDAV, IPTV, local media and
link playback, including videos with no embedded tracks. Select **Load from
phone** to open Android's Storage Access Framework document picker. No broad
storage permission is requested. Files are read into the current playback
session; no URI grant, subtitle file or credentials are persisted or uploaded.

Supported formats: SRT, WebVTT, ASS and SSA. UTF-8 (with or without BOM), UTF-16
and GB18030 are supported. ASS/SSA is rendered as plain text; styles, animations,
fonts and drawings are not rendered. The picker explains this limit.

Reading and parsing happen on the IO dispatcher with a 4 MiB input limit,
50,000 cue limit and bounded cue text. Cues are end-exclusive and overlapping
dialogue is combined. Seeking in either direction updates the visible text.
Import errors stay in the subtitle dialog and do not change playback errors.
Text subtitles clear the measured control-panel height when controls are visible,
so either layout and orientation leaves imported dialogue unobscured.

An import captures the playback session before the picker opens. A result for a
previous video cannot attach to a new video. Imports can be cancelled. Up to four
imported tracks remain selectable during a session; importing another evicts the
oldest. Decoder restarts preserve the imports and selected track. Leaving playback
clears them. The Off option hides both native and imported subtitles.

Returning from the system picker can recreate the video Surface while playback
is paused. IJK is prepared again against the current Surface to release the old
codec output connection. A bounded muted preroll then waits for an actual output buffer before pausing again, keeping
the same playback session, imported tracks and position. The frame probe uses
[PixelCopy](https://developer.android.com/reference/android/view/PixelCopy)
only during this recovery; it does not capture or store playback screenshots.

Qt reference: `AppViewModel::loadLocalSubtitle`, `PlayerController::loadSubtitle`
and `VIBEDOCS/PlayerRuntime.md`. Android implementation uses
[ACTION_OPEN_DOCUMENT](https://developer.android.com/training/data-storage/shared/documents-files).

Validation: `TextSubtitlesTest` covers encodings, WebVTT settings, ASS dialogue,
drawing suppression, overlaps, seek/end boundaries, cancellation and invalid or
oversized input. `PlayerManagerPlaybackTest` covers session and decoder restore
on a device. Real SRT, WebVTT, ASS and UTF-16 document selection, subtitle Off,
reselection, seeking, cancellation and invalid/oversized documents were checked
on Huawei TAS-AL00 / Android 12. See
[feature-device-validation.md](feature-device-validation.md) for results and
remaining coverage limits.
