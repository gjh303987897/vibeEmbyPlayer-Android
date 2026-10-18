# History retention

Settings > History retention offers 7, 14, 30, 60, 90, 180 and 365 days, plus a
custom range of 1–3650 days. The default is 30 days, including today, matching
Qt's retention option. Increasing the period cannot recover already deleted data.

`HistoryRetentionManager` coordinates global history, dedicated link history and
daily usage retention. It cleans expired records on startup, on changing the
setting, on returning to the foreground and across a UTC day change while the app
is foreground. The existing Android UTC archive-date convention is retained.

Cleanup is one Room transaction. Global and link rows sharing an ID are deleted
together, with batches of at most 500 IDs to avoid SQLite bind limits. Daily usage
is pruned in the same transaction. Both public and private records follow the same
retention setting.

History, count, pagination, link history and usage observers apply the same date
cutoff in SQL, so expired records do not briefly reappear while cleanup runs.
Resume lookup also excludes expired records. The settings UI reports a cleanup
failure and allows retrying. Room schema version is unchanged: this feature adds
queries, not tables or columns.

Qt reference: `SessionRepository::historyRetentionDays`,
`SessionRepository::setHistoryRetentionDays` and its history/usage pruning queries.
Android references:
[Room asynchronous queries](https://developer.android.com/training/data-storage/room/async-queries),
[DataStore](https://developer.android.com/topic/libraries/architecture/datastore).

Validation: `HistoryRetentionPolicyTest` covers inclusive cutoffs, range clamping,
leap/year boundaries and midnight checks. `HistoryRetentionTest` uses an isolated
Room database and settings file to check immediate cleanup, mirrored rows,
private/public rows, more than 500 records, usage and SQL pagination/count parity.
Both device tests passed on Huawei TAS-AL00 / Android 12. Manual UI checks also
confirmed range validation and persistence after force-stop; see
[feature-device-validation.md](feature-device-validation.md).
