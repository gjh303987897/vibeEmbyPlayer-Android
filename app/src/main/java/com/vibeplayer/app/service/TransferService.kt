package com.vibeplayer.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.vibeplayer.app.R
import com.vibeplayer.app.data.local.db.dao.TransferTaskDao
import com.vibeplayer.app.data.local.db.entity.TransferStatus
import com.vibeplayer.app.data.repository.TransferRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Foreground service driving the persisted transfer queue. Runs queued
 * downloads/uploads in the background (with a stable notification and limited
 * concurrency) so transfers continue while the app is not in the foreground.
 */
@AndroidEntryPoint
class TransferService : Service() {

    @Inject lateinit var transferRepository: TransferRepository
    @Inject lateinit var taskDao: TransferTaskDao

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var workerJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildProgressNotification(0, 0, indeterminate = true))
        if (workerJob == null || workerJob?.isActive != true) {
            workerJob = scope.launch { runQueue() }
        }
        return START_STICKY
    }

    /** Polls the persisted queue, runs tasks with limited concurrency, and keeps the foreground notification fresh. */
    private suspend fun runQueue() = coroutineScope {
        val semaphore = Semaphore(MAX_CONCURRENT)
        while (isActive) {
            val queued = taskDao.queued()
            val active = taskDao.active().filter { it.status == RUNNING_STATUS }
            if (queued.isEmpty() && active.isEmpty()) {
                updateNotification(activeCount = 0)
                delay(POLL_IDLE_MS)
                // Nothing left to do; stop the foreground service to avoid
                // wasting battery. A new download/retry restarts it.
                stopSelf()
                break
            }
            updateNotification(activeCount = queued.size)
            queued.forEach { task ->
                launch {
                    semaphore.withPermit {
                        if (taskDao.claimQueued(task.id) == 1) {
                            transferRepository.runTask(task)
                        }
                    }
                }
            }
            delay(POLL_BUSY_MS)
        }
    }

    private fun updateNotification(activeCount: Int) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildProgressNotification(0, activeCount, indeterminate = activeCount == 0))
    }

    private fun buildProgressNotification(progress: Int, activeCount: Int, indeterminate: Boolean): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.transfer_notification_title))
            .setContentText(
                if (activeCount > 0) "$activeCount transfer(s) in progress"
                else getString(R.string.transfer_idle)
            )
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setProgress(100, progress, indeterminate)
            .build()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        workerJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "transfers"
        const val NOTIFICATION_ID = 1001
        private const val MAX_CONCURRENT = 3
        private const val POLL_IDLE_MS = 2000L
        private const val POLL_BUSY_MS = 800L
        private val RUNNING_STATUS = TransferStatus.RUNNING

        /** Starts the foreground transfer service, tolerating background start restrictions. */
        fun start(context: Context) {
            val intent = Intent(context, TransferService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun createChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.transfer_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                )
                val manager =
                    context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.createNotificationChannel(channel)
            }
        }
    }
}
