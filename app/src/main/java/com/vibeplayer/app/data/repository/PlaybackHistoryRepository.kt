package com.vibeplayer.app.data.repository

import com.vibeplayer.app.data.local.db.dao.DailyUsageStatDao
import com.vibeplayer.app.data.local.db.dao.LinkPlaybackHistoryDao
import com.vibeplayer.app.data.local.db.dao.PlaybackHistoryDao
import com.vibeplayer.app.data.local.db.entity.DailyUsageStatEntity
import com.vibeplayer.app.data.local.db.entity.LinkPlaybackHistoryEntity
import com.vibeplayer.app.data.local.db.entity.PlaybackHistoryEntity
import com.vibeplayer.app.model.PlaybackHistoryEntry
import com.vibeplayer.app.model.PlaybackSource
import com.vibeplayer.app.model.ServerConfig
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Repository for the unified global playback history and daily usage
 * statistics. Mirrors the Qt SessionRepository history behaviour: one row per
 * stable media identity, INSERT OR REPLACE semantics, progress throttling is
 * left to the caller, and completion is marked at EOF or >=97% of duration.
 */
@Singleton
class PlaybackHistoryRepository @Inject constructor(
    private val playbackHistoryDao: PlaybackHistoryDao,
    private val linkPlaybackHistoryDao: LinkPlaybackHistoryDao,
    private val dailyUsageStatDao: DailyUsageStatDao
) {

    /** Number of history rows loaded per page. */
    val PAGE_SIZE: Int = DEFAULT_PAGE_SIZE

    suspend fun resumePositionSeconds(source: PlaybackSource, service: ServerConfig?, replayTarget: String): Long {
        val row = playbackHistoryDao.findByStableIdentity(source.label, service?.id ?: "", replayTarget)
            ?: return 0L
        return if (row.completed) 0L else row.positionSeconds.coerceAtLeast(0L)
    }

    fun observeHistory(
        source: PlaybackSource? = null,
        showPrivate: Boolean = false,
        limit: Int = DEFAULT_PAGE_SIZE,
        offset: Int = 0
    ): Flow<List<PlaybackHistoryEntry>> {
        val flow = if (source == null || source == PlaybackSource.UNKNOWN) {
            playbackHistoryDao.observePaged(showPrivate = showPrivate, limit = limit, offset = offset)
        } else {
            playbackHistoryDao.observeBySource(source.label, showPrivate = showPrivate, limit = limit, offset = offset)
        }
        return flow.map { rows -> rows.map { it.toDomain() } }
    }

    /** Reactive total count of history rows matching [source] (null = all). */
    fun observeCount(source: PlaybackSource? = null, showPrivate: Boolean = false): Flow<Int> =
        if (source == null || source == PlaybackSource.UNKNOWN) playbackHistoryDao.observeCount(showPrivate)
        else playbackHistoryDao.observeCountBySource(source.label, showPrivate)


    fun observeUsage(showPrivate: Boolean = false): Flow<List<DailyUsageStatEntity>> = dailyUsageStatDao.observeAll(showPrivate)

    /**
     * Records a playback occurrence for a stable media identity. If a row for
     * the same (source, service, replayTarget) already exists its stable id,
     * resume position, duration, and completion state are preserved while the
     * latest occurrence metadata and playback time are refreshed.
     */
    suspend fun recordPlayback(
        source: PlaybackSource,
        service: ServerConfig?,
        replayTarget: String,
        title: String,
        subtitle: String = "",
        positionSeconds: Long = 0L,
        durationSeconds: Long = 0L,
        completed: Boolean = false
    ) {
        val identity = source.label to (service?.id ?: "") to replayTarget
        val existing = playbackHistoryDao.findByStableIdentity(
            source = identity.first.first,
            serviceId = identity.first.second,
            replayTarget = identity.second
        )
        val now = System.currentTimeMillis()
        if (existing != null) {
            playbackHistoryDao.markPlaybackOccurrence(
                existing.id,
                todayUtc(),
                now,
                service?.name ?: source.label,
                title.ifBlank { existing.title },
                subtitle.ifBlank { existing.subtitle },
                service?.name ?: source.label,
                service?.privateMode ?: existing.privacyMode
            )
            playbackHistoryDao.updateProgress(
                existing.id,
                existing.positionSeconds,
                existing.durationSeconds,
                existing.completed,
                now
            )
            return
        }
        val playedAtMs = now
        val playedDate = todayUtc()

        val entry = PlaybackHistoryEntity(
            id = existing?.id ?: UUID.randomUUID().toString(),
            source = source.label,
            serviceId = service?.id ?: "",
            serviceName = service?.name ?: source.label,
            replayTarget = replayTarget,
            title = if (title.isNotBlank()) title else (existing?.title ?: ""),
            subtitle = if (subtitle.isNotBlank()) subtitle else (existing?.subtitle ?: ""),
            displayTarget = service?.name ?: (existing?.displayTarget ?: source.label),
            playedDate = playedDate,
            playedAtMs = playedAtMs,
            updatedAtMs = now,
            positionSeconds = positionSeconds,
            durationSeconds = durationSeconds,
            completed = completed,
            privacyMode = service?.privateMode ?: false,
            available = true
        )
        playbackHistoryDao.upsert(entry)
    }

    /** Throttled progress update for an existing playback occurrence. */
    suspend fun updateProgress(
        source: PlaybackSource,
        service: ServerConfig?,
        replayTarget: String,
        positionSeconds: Long,
        durationSeconds: Long
    ) {
        val existing = playbackHistoryDao.findByStableIdentity(source.label, service?.id ?: "", replayTarget) ?: return
        playbackHistoryDao.updateProgress(
            existing.id,
            positionSeconds,
            durationSeconds,
            completed = durationSeconds > 0 && positionSeconds >= durationSeconds * 97 / 100,
            updatedAtMs = System.currentTimeMillis()
        )
    }

    /** Marks a playback occurrence complete (EOF or >=97% duration). */
    suspend fun completePlayback(
        source: PlaybackSource,
        service: ServerConfig?,
        replayTarget: String,
        durationSeconds: Long
    ) {
        val existing = playbackHistoryDao.findByStableIdentity(source.label, service?.id ?: "", replayTarget) ?: return
        playbackHistoryDao.updateProgress(existing.id, durationSeconds, durationSeconds, true, System.currentTimeMillis())
    }

    suspend fun deleteHistory(id: String) {
        playbackHistoryDao.deleteById(id)
        linkPlaybackHistoryDao.deleteById(id)
    }

    fun observeLinkHistory(showPrivate: Boolean = false): Flow<List<LinkPlaybackHistoryEntity>> =
        linkPlaybackHistoryDao.observePaged(showPrivate = showPrivate, limit = 100, offset = 0)

    /**
     * Records a Link-source playback occurrence. Mirrors the same uuid into the
     * dedicated link history and the unified history. Replaying the same
     * normalized URL replaces its previous row (keeps the latest occurrence).
     */
    suspend fun recordLinkPlayback(
        url: String,
        displayName: String,
        service: ServerConfig?,
        positionSeconds: Long = 0L,
        durationSeconds: Long = 0L,
        completed: Boolean = false
    ) {
        val now = System.currentTimeMillis()
        val displayAddress = displayUrl(url)
        val previous = linkPlaybackHistoryDao.findByUrl(url)
        val id = previous?.id ?: UUID.randomUUID().toString()

        if (previous != null) {
            val previousHistory = playbackHistoryDao.findById(id)
            linkPlaybackHistoryDao.markPlaybackOccurrence(
                id = id,
                displayName = displayName,
                displayAddress = displayAddress,
                playedDate = todayUtc(),
                playedAtMs = now,
                privateMode = service?.privateMode ?: previous.privacyMode
            )
            previousHistory?.let {
                playbackHistoryDao.markPlaybackOccurrence(
                    id = id,
                    playedDate = todayUtc(),
                    playedAtMs = now,
                    serviceName = "Link",
                    title = displayName,
                    subtitle = it.subtitle,
                    displayTarget = displayAddress,
                    privateMode = service?.privateMode ?: it.privacyMode
                )
            }
            if (positionSeconds == 0L && durationSeconds == 0L && !completed) {
                previousHistory?.let {
                    playbackHistoryDao.updateProgress(id, it.positionSeconds, it.durationSeconds, it.completed, now)
                }
                return
            }
            playbackHistoryDao.updateProgress(
                id,
                positionSeconds,
                durationSeconds,
                completed || (durationSeconds > 0 && positionSeconds >= durationSeconds * 97 / 100),
                now
            )
            return
        }

        val link = LinkPlaybackHistoryEntity(
            id = id,
            playbackUrl = url,
            displayName = displayName,
            displayAddress = displayAddress,
            playedDate = todayUtc(),
            playedAtMs = now,
            privacyMode = service?.privateMode ?: false
        )
        linkPlaybackHistoryDao.upsert(link)
        playbackHistoryDao.upsert(
            PlaybackHistoryEntity(
                id = id,
                source = PlaybackSource.LINK.label,
                serviceId = "",
                serviceName = "Link",
                replayTarget = url,
                title = displayName,
                subtitle = "",
                displayTarget = displayAddress,
                playedDate = todayUtc(),
                playedAtMs = now,
                updatedAtMs = now,
                positionSeconds = positionSeconds,
                durationSeconds = durationSeconds,
                completed = completed,
                privacyMode = service?.privateMode ?: false,
                available = true
            )
        )
    }

    /** Convenience: records Link usage traffic under the stable built-in source. */
    suspend fun addLinkUsage(service: ServerConfig?, watchSeconds: Long, networkBytesIn: Long) =
        addDailyUsage(service, watchSeconds, networkBytesIn)

    suspend fun addDailyUsage(
        service: ServerConfig?,
        watchSeconds: Long,
        networkBytesIn: Long
    ) {
        val existing = dailyUsageStatDao.findByKey(todayUtc(), service?.id ?: "", service?.privateMode ?: false)
        if (existing == null) {
            dailyUsageStatDao.upsert(
                DailyUsageStatEntity(
                    date = todayUtc(),
                    serviceId = service?.id ?: "",
                    serviceName = service?.name ?: "Local",
                    serviceType = service?.serviceType?.name ?: "",
                    watchSeconds = watchSeconds,
                    networkBytesIn = networkBytesIn,
                    networkBytesOut = 0,
                    keepAliveNetworkBytesIn = 0,
                    keepAliveNetworkBytesOut = 0,
                    privacyMode = service?.privateMode ?: false
                )
            )
        } else {
            dailyUsageStatDao.increment(
                date = todayUtc(),
                serviceId = service?.id ?: "",
                privacyMode = service?.privateMode ?: false,
                watchSeconds = watchSeconds,
                networkBytesIn = networkBytesIn,
                networkBytesOut = 0,
                keepAliveIn = 0,
                keepAliveOut = 0
            )
        }
    }

    private fun todayUtc(): String = LocalDate.now(ZoneOffset.UTC).toString()

    private fun displayUrl(url: String): String = runCatching {
        val uri = android.net.Uri.parse(url)
        val builder = uri.buildUpon().clearQuery().fragment(null)
        builder.build().toString()
    }.getOrDefault(url)

    companion object {
        const val DEFAULT_PAGE_SIZE = 100
    }
}
private fun PlaybackHistoryEntity.toDomain() = PlaybackHistoryEntry(
    id = id,
    source = PlaybackSource.fromLabel(source),
    serviceId = serviceId,
    serviceName = serviceName,
    replayTarget = replayTarget,
    title = title,
    subtitle = subtitle,
    displayTarget = displayTarget,
    playedDate = playedDate,
    playedAtMs = playedAtMs,
    updatedAtMs = updatedAtMs,
    positionSeconds = positionSeconds,
    durationSeconds = durationSeconds,
    completed = completed,
    privacyMode = privacyMode,
    available = available
)
