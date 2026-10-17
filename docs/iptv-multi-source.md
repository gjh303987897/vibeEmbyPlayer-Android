# IPTV multi-source channels

The Android IPTV library groups multiple source rows into one visible channel. Its identity
rules follow the Qt `IptvParser` / `IptvStreamSelector` and `VIBEDOCS/IptvStreamSelection.md`:

- Within one service/playlist, a nonempty `tvg-id` is the identity.
- Without `tvg-id`, trimmed, whitespace-normalized, case-insensitive name **and group** form
  the identity. Different groups, different nonempty IDs and different services remain distinct.
- HD/4K suffixes are not removed or guessed. Ordered duplicate URLs are offered only once.

The first source supplies the representative ID/name and replay URL; all source IDs remain
available as favourite aliases. A previously favourited second source still marks the merged
channel as a favourite. Removing that favourite removes every alias for this channel.
The list displays the number of distinct source URLs, independently of HLS renditions.

## Existing data

Room version 3 retains existing rows and IDs, adds `tvg_id` and scopes the primary key to
`(playlist_id, id)`. Identical route IDs in different services no longer replace each other.
There is no destructive migration or automatic reimport. Since older database versions did
not store `tvg-id`, the repository restores missing IDs from the managed playlist copy once
per service/process. If that copy is unavailable, name/group merging still works.
New managed copies are stored in separate service directories.

### Import and Back navigation

Selecting a playlist now creates a single pending request scoped to its service, saved in
`SavedStateHandle`. Its result is consumed before file/database work; a null result from
Back cancels the request, and replayed results without a new launch are ignored. A later
explicit reimport remains allowed. Both import buttons are disabled while selecting/importing,
with synchronous ViewModel checks to cover multiple events before recomposition.

Returning from the player reuses the existing channel subscription instead of restarting it.
Each Room emission replaces the visible channel snapshot. Repository imports are serialized;
each writes one transaction that replaces that service's channels, preserving stable IDs.
Cancellation is propagated rather than reported as a completed/failed import after leaving.
Success/error messages are consumed before waiting for their snackbar, so navigation cannot
replay a completed import's notification.

`IptvImportRequestTest` covers repeated result delivery after returning, cancelling with Back
then receiving an old result, rapid taps, pending-request restoration, and deliberate reimport.
These are local request/state tests; actual system Back gestures have not been device-tested.

The existing URL/name navigation and history routes remain supported. Opening an old source URL
resolves its containing merged channel, so the user can select its other sources as well.
Changing routes during playback keeps one logical-channel history occurrence. Progress and
native byte deltas are flushed before resetting the player; watched time uses playing elapsed
time instead of subtracting positions that restart at zero on a new live route.

## Quality selection

The default preference is **highest picture quality first**. Users can choose highest/lowest
from the IPTV library toolbar or from **Player settings → Playback → Select route → Quality**.
This is persisted in DataStore. A change affects the next channel opening or an explicit tap
on **Automatic**, and does not interrupt the current stream by itself.

Only the selected channel is probed; importing/browsing a large playlist does not probe every
channel. HTTP probes share a three-request semaphore, have a per-call three-second deadline,
and the channel resolution stage has an eight-second budget. Each response is limited to
512 KiB even if a server ignores Range. HTTP 416 is retried without Range. Useful partial bytes
from a slow live response are retained. Cancelling the screen cancels outstanding OkHttp calls.
TLS uses the same explicit per-service policy as playback. URLs/credentials are not logged or
shown in the route menu.

HLS master `RESOLUTION` and `AVERAGE-BANDWIDTH`/`BANDWIDTH` supply rendition metadata. Media
playlists and direct streams can supply bounded media samples; Android `MediaExtractor` only
reads temporary local file descriptors, never an unbounded remote URL. An fMP4 initialization
sample can be combined with a media sample (at most 1 MiB). Encrypted and byte-range playlists
remain usable with unknown quality instead of being rejected for failed sample extraction.
Non-HTTP protocols remain selectable with unknown quality.

Responding sources rank ahead of unprobed sources, which rank ahead of detected failures.
Within each availability class, known resolution comes first and is compared by pixel count,
then known bitrate. The preference controls ascending/descending order. Unknown metadata is
never interpreted as zero quality; unresolved ties retain playlist order. Sources whose
metadata is unsupported/truncated remain available for manual selection. This is a metadata
comparison, not a subjective visual quality or network-speed estimate.

The in-memory cache is bounded to 512 entries, keyed by URL and TLS policy: six hours for
responding sources with known resolution, one minute otherwise. **Check routes again** bypasses
that cache and updates the menu without reconnecting the playing route.

## Playback and manual routes

**Select route** is a secondary player settings page with **Routes** and **Quality** tabs.
The routes tab uses a bounded lazy list, displays dimensions/bitrate or unknown quality,
marks the active source, and allows **Automatic** or an explicit route. Each HLS rendition
is selectable even when it belongs to the same source URL.

The selected rendition is enforced in `PlaybackHttpProxy` by filtering the master to one video
variant. The master, external audio/subtitle groups and session/key tags are retained. If live
signed URLs rotate, matching resolution/bitrate preserves the selection. If a rendition no
longer exists and cannot be matched, the original master remains playable; the user can refresh
the route metadata. Selecting a variant changes the actual HTTP playlist, not just the label.

Manual switching reconnects live playback, preserving pause intent, speed, volume and decoder
preference. It requests a fresh Surface before preparing, as decoder changes already do.
Native playback errors in automatic mode try each resolved route at most once; normal EOF,
pause, and manual mode never trigger automatic switching. An explicit Automatic/manual tap
can start a new attempt. Late probe results cannot restart a player after leaving/locking it.
Route metadata refresh retains an active obsolete rendition in the menu, but excludes it
from the fresh automatic candidates.

## Local validation

Run `:app:assembleDebug :app:lint :app:testDebugUnitTest`. Tests cover identity/favourite/URL
compatibility, parser metadata, highest/lowest ranking and unknown metadata, HLS audio/subtitle
preservation, rotating signed URLs, actual high/low playlist requests through the proxy,
HTTP Range rejection, bounded/partial sampling, cancellation, and cache refresh.

The migration SQL was also executed locally against both exported version 1 and version 2
SQLite schemas and compared with the version 3 Room export. All existing channel IDs and
other table data were preserved, indexes/columns matched, and a duplicate ID could coexist
in another playlist. This is a local SQL check, not an Android Room runtime migration test.
This change has not been tested on a physical device; device testing was deferred by request.

Validated locally on 2026-10-17: `assembleDebug`, `lint` (zero errors, three existing warnings),
all 59 JVM tests (including five import/Back request regressions), and both SQL migration checks
pass. A separate local SQLite check using Room's generated insertion/deletion SQL repeats a
playlist replacement 50 times: channel count stays at two, another service's channel remains,
and an incomplete replacement rolls back. This checks SQL persistence, not the Android system UI.

## References

- [HLS RFC 8216](https://www.rfc-editor.org/info/rfc8216/)
- [Android MediaExtractor](https://developer.android.com/reference/android/media/MediaExtractor)
- [Android MediaFormat](https://developer.android.com/reference/android/media/MediaFormat)
- [Room migrations](https://developer.android.com/training/data-storage/room/migrating-db-versions)
- [Compose tabs](https://developer.android.com/develop/ui/compose/components/tabs)
- [Activity result APIs and restoring additional state](https://developer.android.com/training/basics/intents/result)
- [Compose effect lifecycle](https://developer.android.com/develop/ui/compose/side-effects)
