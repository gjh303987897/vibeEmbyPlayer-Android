package com.vibeplayer.app.data.remote

import com.vibeplayer.app.domain.tssl.TsslBackupArchive
import java.io.File
import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink

/** The callback owns the response through body IO, so cancellation also aborts a stalled transfer. */
internal suspend fun <T> Call.backupResponse(read: (Response) -> T): T {
    val finished = CompletableDeferred<Unit>()
    try {
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { cancel() }
            try {
                enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        try { if (continuation.isActive) continuation.resumeWithException(e) }
                        finally { finished.complete(Unit) }
                    }

                    override fun onResponse(call: Call, response: Response) {
                        try {
                            response.use {
                                if (!continuation.isActive) return
                                val result = read(it)
                                if (continuation.isActive) continuation.resume(result)
                            }
                        } catch (error: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(error)
                        } finally { finished.complete(Unit) }
                    }
                })
            } catch (error: Exception) {
                finished.complete(Unit)
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }
    } finally {
        // File cleanup must wait until the callback releases its streams, including on Windows.
        withContext(NonCancellable) { finished.await() }
    }
}

internal fun File.backupRequestBody(type: MediaType?, context: CoroutineContext): RequestBody {
    val expected = length()
    return object : RequestBody() {
        override fun contentType() = type
        override fun contentLength() = expected
        override fun writeTo(sink: BufferedSink) {
            val count = inputStream().use { input ->
                TsslBackupArchive.copyBounded(input, sink.outputStream(), expected, { context.ensureActive() })
            }
            require(count == expected) { "Backup file changed during upload" }
        }
    }
}

internal fun Response.backupToFile(file: File, maximum: Long, context: CoroutineContext) {
    if (!isSuccessful) throw IOException("Backup download HTTP $code")
    val body = body ?: throw IOException("Empty backup response")
    require(body.contentLength() <= maximum) { "Backup response exceeds size limit" }
    body.byteStream().use { input ->
        file.outputStream().buffered().use { output -> TsslBackupArchive.copyBounded(input, output, maximum, { context.ensureActive() }) }
    }
    require(file.length() > 0) { "Empty backup file" }
}
