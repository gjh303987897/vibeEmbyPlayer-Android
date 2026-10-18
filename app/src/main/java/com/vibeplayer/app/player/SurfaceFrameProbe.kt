package com.vibeplayer.app.player

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.Surface
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** Checks for an actual output buffer after a paused video's Surface was replaced. */
internal suspend fun hasSurfaceFrame(surface: Surface): Boolean = suspendCancellableCoroutine { continuation ->
    val pixel = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    try {
        PixelCopy.request(surface, pixel, { result ->
            // PixelCopy owns the bitmap until this callback, even after cancellation.
            pixel.recycle()
            if (continuation.isActive) continuation.resume(result == PixelCopy.SUCCESS)
        }, Handler(Looper.getMainLooper()))
    } catch (_: IllegalArgumentException) {
        pixel.recycle()
        continuation.resume(false)
    }
}
