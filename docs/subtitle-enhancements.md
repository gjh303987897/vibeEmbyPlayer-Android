# Subtitle appearance and timing

Open **Player settings → Playback → Subtitles**. Track selection and phone import
remain on that page. **Subtitle appearance** and **Subtitle timing** open separate
scrollable pages; their back buttons return to Subtitles.

## Text appearance

All five player screens share `PlayerSubtitleOverlay` and the same DataStore
preferences. Appearance is saved on slider release and applies to subsequent videos:

| Setting | Range | Default |
| --- | --- | --- |
| Font size | 12–36 sp | 16 sp |
| Extra upward offset | 0–120 dp | 0 dp |
| Background transparency | 0–100% | 35% |

The preview uses the production text overlay. The upward offset is additional to
the existing safe-area/control clearance, and is capped at 30% of the available
overlay height on short screens. Restore default appearance resets all three
values. Stored values are bounded before use; transparency is stored as opacity.
Embedded text, external text and imported phone subtitles use this appearance.
ASS/SSA remains plain text. PGS/DVD/DVB bitmap subtitles keep their original image
layout and are not restyled by text settings.

## Timing

The current video has one subtitle offset, from −10 to +10 seconds, adjustable in
0.1-second increments through the slider or Earlier/Later buttons. Positive means
later; negative means earlier. **Reset offset** returns immediately to zero. The
offset survives subtitle selection, decoder switches and Surface recovery within
the video, but resets when playback stops or another video starts. It is not saved
to DataStore or history.

Imported and external text query their original cue timeline at
`media position − offset`. Negative query positions stay negative, so a delayed
cue at zero cannot appear prematurely. Native embedded text and bitmap subtitles
shift only the subtitle queue's comparison clock through a mutex-protected JNI
bridge; audio/video clocks and packet timestamps are unchanged.

Changing an embedded track's offset clears stale captions and coalesces adjustments
for 150 ms before seeking to the current media position. This refills cues consumed
under the previous clock and retains playback/pause intent. Network media can
briefly buffer during this refresh; encrypted streams use the existing cancellable
seek path. Live/non-seekable stream behavior still needs device coverage.
Sources with no reported duration skip the refill seek. The native scheduler
re-evaluates the queued current cue after an offset change, including returning
to zero. It cannot recover historical cues already discarded by a live stream;
the new offset applies to the available and subsequent cues.

The Qt reference supplies subtitle track selection and local import but has no
equivalent appearance/offset settings in the reviewed player runtime. These are
Android additions, without changes to media source protocols.

## Local validation

Validated locally on 2026-10-20: `assembleDebug`, `assembleDebugAndroidTest`,
all 99 JVM tests and `lint` pass (zero errors, three existing warnings). The native
queue regression passes, including its failing-baseline control. All four ABI
AARs and their twelve native libraries match the recorded hashes; the final APK
contains those same libraries and passes 16 KB zip/ELF alignment checks. The new
timing JNI symbol is present in every ABI. No device tests were run for this change.

`SubtitlePreferencesTest` checks signed offsets, cue start/end boundaries, reset,
zero-time cues, arithmetic overflow and corrupt stored appearance values.

```powershell
node scripts/test-ijkplayer-subtitle-timing.mjs
```

This host regression extracts the actual patched IJK `subtitle_refresh` function
and compiles it with queue/clock/render stubs. It checks text and bitmap cue
activation/expiration, positive/negative/zero offsets, seek gating, stale queue
serials, changing the offset of an already displayed cue without seeking, and
unchanged A/V clocks. The same test must fail with the old unshifted
clock, guarding against a test that passes without the feature.

`PlayerManagerPlaybackTest.subtitleTimingShiftsEmbeddedAndImportedCuesAndResetsForTheNextVideo`
uses generated media to check embedded timing, visible video pixels, paused
imported text and reset on the next video. Its APK is compiled locally; device
execution is deferred at the user's request. Local queue tests do not establish
Android JNI/lifecycle behavior or real network seek performance.

## References

- [Compose text styling](https://developer.android.com/develop/ui/compose/text/style-text)
- [Material 3 sliders](https://developer.android.com/develop/ui/compose/components/slider)
- [Pinned IJK player sources](https://github.com/bilibili/ijkplayer/tree/cced91e3ae3730f5c63f3605b00d25eafcf5b97b/ijkmedia/ijkplayer)
