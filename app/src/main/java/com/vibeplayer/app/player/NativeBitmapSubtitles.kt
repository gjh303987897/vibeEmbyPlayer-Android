package com.vibeplayer.app.player

import android.graphics.Bitmap
import androidx.annotation.Keep
import tv.danmaku.ijk.media.player.IjkMediaPlayer

@Keep
class BitmapSubtitleRegion(val x: Int, val y: Int, val bitmap: Bitmap)

@Keep
class BitmapSubtitleFrame(
    val revision: Long,
    val canvasWidth: Int,
    val canvasHeight: Int,
    val regions: Array<BitmapSubtitleRegion>
)

/** IJK loads the native library; merely referencing these models never loads native code. */
@Keep
object NativeBitmapSubtitles {
    external fun read(player: IjkMediaPlayer, revision: Long): BitmapSubtitleFrame?
    external fun setDelay(player: IjkMediaPlayer, delayMs: Long)
}
