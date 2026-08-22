package com.vibeplayer.app.util

import android.content.Context
import android.os.Process
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Captures the stack trace of any uncaught exception at the process level and
 * writes it to a plain-text file so a crash can be inspected on-device or via
 * `adb pull` without needing to catch it live in logcat.
 *
 * This is NOT a crash-reporting SDK and never swallows the crash: after logging
 * it delegates to the previously installed handler so the system behaviour
 * (dialog / process termination) is unchanged. It exists purely to turn a
 * hard-to-reproduce crash into retrievable evidence.
 */
object CrashLogger {

    private const val FILE_NAME = "vibeplayer_crash.log"
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    /** Idempotent. Installs once at app startup. */
    fun install(context: Context) {
        if (previousHandler != null) return
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        val appContext = context.applicationContext
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            writeCrash(appContext, thread, throwable)
            // Always delegate so the app still terminates / shows the system
            // crash dialog exactly as it would without the logger.
            previousHandler?.uncaughtException(thread, throwable)
                ?: runCatching { Process.killProcess(Process.myPid()) }
        }
    }

    /**
     * Writes the crash to both the internal and external files dirs. The
     * external copy is reachable via USB file transfer
     * (`Android/data/com.vibeplayer.app/files/...`) and `adb pull`, making it
     * recoverable even without a live logcat capture.
     */
    private fun writeCrash(context: Context, thread: Thread, throwable: Throwable) {
        val files = listOf(context.filesDir, context.getExternalFilesDir(null)).filterNotNull()
        if (files.isEmpty()) return
        val sw = StringWriter()
        sw.append("=== Crash @ ")
            .append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date()))
            .append(" ===\nThread: ").append(thread.name).append('\n')
        throwable.printStackTrace(PrintWriter(sw))
        sw.append('\n')
        for (dir in files) {
            runCatching {
                File(dir, FILE_NAME).appendText(sw.toString())
                // Keep the very latest report in a fixed-name file too.
                File(dir, "vibeplayer_crash_latest.log").writeText(sw.toString())
            }
        }
    }
}
