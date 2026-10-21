# WebDAV and local directory sorting

The directory toolbar offers **Sort order** in WebDAV and inside local folders.
It provides name A–Z/Z–A, size smallest/largest first, and modification time
newest/oldest first. The current choice has a check mark and an accessibility
state description. It follows the normal Material theme, including light/dark
surfaces and readable selected-state icons.

WebDAV and local playback save separate choices in the existing DataStore under
`webdav_browse_sort` and `local_browse_sort`. They apply across folders and page/app
re-entry; all WebDAV services share the WebDAV choice. Missing/unknown values
default to name A–Z. Local root cards keep their configured order; sorting applies
to entries inside a folder, without scanning roots to calculate folder sizes.

Folders always stay ahead of files in either direction. Names use a
case-insensitive, locale-independent comparison of the displayed filename, with
the exact name and stable path/URI breaking ties. TSSL original-source names
remain supporting metadata, so asynchronously loading them cannot change name
order. Sizes compare integer bytes without rounding/subtraction overflow; ties
fall back to name A–Z. Missing negative sizes remain last, while the data source's
zero size is retained as zero. Folder sizes are not recursively calculated.

Local modification dates use the SAF provider's existing timestamp (including
directory timestamps). WebDAV dates parse `DAV:getlastmodified` as RFC 1123/HTTP
dates; ISO timestamps from nonstandard providers are also accepted. Timezones are
normalised to instants before comparison. Missing/invalid dates remain last in
both time directions; no extra per-file HEAD/download requests are made.

Ordering runs in the ViewModel on `Dispatchers.Default`, using the already loaded
directory snapshot. A sort choice does not trigger another PROPFIND or SAF query.
Latest-state cancellation prevents a slower sort from replacing a newer choice.
Local folder loads also cancel superseded jobs and reject stale directory results.
The displayed ordered snapshot is what `preparePlayback` uses to create the
session queue; previous/next and autoplay therefore follow the chosen order.
An already playing queue remains that session's snapshot.

Qt reference: `VIBEDOCS/WebDAV.md`, `VIBEDOCS/LocalPlayback.md`,
`WebDavClient::parsePropfind` and `LocalMediaService` directory enumeration use
folder-first name ordering. The Android change adds user-selectable fields and
directions while retaining folder grouping and the existing media filters.

References consulted:

- [Compose menus](https://developer.android.com/develop/ui/compose/components/menu)
- [DataStore](https://developer.android.com/topic/libraries/architecture/datastore)
- [WebDAV getlastmodified](https://www.rfc-editor.org/rfc/rfc4918.html#section-15.7)

`MediaDirectorySortTest` covers six modes for both item models, folder grouping,
case-insensitive names, deterministic ties, large/unknown/zero sizes, actual
chronological ordering and offsets, invalid dates, and encrypted metadata
preservation. `BrowseSortPreferencesTest` checks fallback and all 36 independent
WebDAV/local preference combinations without changing unrelated settings.

Local validation on 2026-10-21: `assembleDebug`, `assembleDebugAndroidTest`,
all 143 JVM tests and `lint` passed (zero errors, three existing warnings).

Device validation on 2026-10-22: `DirectorySortDeviceTest` passed on Huawei
TAS-AL00 / Android 12 (API 31), using an independent `.regression` application.
The production local/WebDAV browse screens and ViewModels exercised all six
modes, folder grouping, actual visible row positions, independent DataStore
values, freshly created ViewModels and displayed-order queue staging. Counting
the local ContentResolver queries and WebDAV PROPFIND requests confirmed no
refetch while sorting. Light/dark menu screenshots were inspected for readable
labels and selected check marks.

These fixtures use synthetic, read-only SAF-shaped metadata in a test-APK-only
ContentProvider and a loopback MockWebServer DAV response. They do not establish
compatibility with every OEM document picker or a user's configured DAV server.
Huawei blocks cold background starts of the fixture provider; the test warms
only that provider via instrumentation shell before production queries. Because
Kotlin stdlib is shared with the target app and excluded from the standalone
test APK, this framework/JDK-only provider uses AndroidTest bytecode compiled
without generated Kotlin parameter assertions. Production compilation is unchanged.

See [device validation](device-validation-2026-10-22.md) for results and scope.
