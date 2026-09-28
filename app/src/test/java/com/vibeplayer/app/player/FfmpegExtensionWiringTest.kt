package com.vibeplayer.app.player

import android.os.Handler
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.Renderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the contract between `DefaultRenderersFactory` and the vendored Media3 FFmpeg decoder.
 *
 * Media3 does not compile against the extension - it instantiates it **by reflection**
 * (`Class.forName("androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer")` followed by
 * `getConstructor(Handler, AudioRendererEventListener, AudioSink)`). A rename, a package move or
 * a media3 upgrade that changes that signature therefore fails silently: the extension is simply
 * never used and AC-3/DTS/TrueHD go back to being mute. This test is the early warning.
 *
 * It only inspects the classes - it never instantiates the renderer, so no native library is
 * required (that is [AudioDecodeSupport.softDecoderAvailable]'s job at runtime).
 */
class FfmpegExtensionWiringTest {

    private val rendererClassName = "androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer"

    @Test
    fun `the ffmpeg audio renderer is packaged under the name media3 looks up`() {
        val renderer = Class.forName(rendererClassName)
        assertEquals(rendererClassName, renderer.name)
        assertTrue("$rendererClassName must implement Media3's Renderer", Renderer::class.java.isAssignableFrom(renderer))
    }

    @Test
    fun `the renderer keeps the constructor media3 creates it with`() {
        val constructor = Class.forName(rendererClassName)
            .getConstructor(Handler::class.java, AudioRendererEventListener::class.java, AudioSink::class.java)
        assertEquals(
            "androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer",
            constructor.declaringClass.name
        )
    }

    @Test
    fun `the library probe methods the player uses exist`() {
        val library = Class.forName("androidx.media3.decoder.ffmpeg.FfmpegLibrary")
        assertNotNull(library.getMethod("isAvailable"))
        assertNotNull(library.getMethod("supportsFormat", String::class.java))
        assertNotNull(library.getMethod("getVersion"))
    }
}
