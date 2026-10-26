package com.vibeplayer.app.player

import androidx.annotation.Keep
import tv.danmaku.ijk.media.player.IjkMediaPlayer

@Keep
object NativePlaybackTiming {
    external fun setDisplayRefreshRate(player: IjkMediaPlayer, refreshRate: Float)
    external fun readFloat(player: IjkMediaPlayer, property: Int): Float
}
