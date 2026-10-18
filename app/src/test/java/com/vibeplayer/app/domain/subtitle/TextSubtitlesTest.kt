package com.vibeplayer.app.domain.subtitle

import java.io.ByteArrayInputStream
import java.nio.charset.Charset
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class TextSubtitlesTest {
    private val srt = "1\r\n00:00:01,000 --> 00:00:03,000\r\n<b>Hello</b> &amp; world\r\n\r\n2\r\n00:00:02,000 --> 00:00:04,000\r\nSecond"

    @Test fun srtOverlapsSeekAndEndExclusive() {
        val parsed = TextSubtitles.parse(srt.toByteArray(), "movie.srt")
        val timeline = TextSubtitleTimeline(parsed.cues)
        assertNull(timeline.textAt(999))
        assertEquals("Hello & world", timeline.textAt(1_000))
        assertEquals("Hello & world\nSecond", timeline.textAt(2_000))
        assertEquals("Second", timeline.textAt(3_000))
        assertNull(timeline.textAt(4_000))
        assertEquals("Hello & world", timeline.textAt(1_500))
    }

    @Test fun supportsBomUtf16AndLegacyChinese() {
        val source = srt.replace("Hello", "\u4f60\u597d")
        listOf("UTF-8", "UTF-16LE", "UTF-16BE", "GB18030").forEach { encoding ->
            val bom = when (encoding) {
                "UTF-8" -> byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte())
                "UTF-16LE" -> byteArrayOf(0xff.toByte(), 0xfe.toByte())
                "UTF-16BE" -> byteArrayOf(0xfe.toByte(), 0xff.toByte())
                else -> byteArrayOf()
            }
            val parsed = TextSubtitles.parse(bom + source.toByteArray(Charset.forName(encoding)), "captions.srt")
            assertTrue("encoding $encoding", parsed.cues.first().text.contains("\u4f60\u597d"))
        }
    }

    @Test fun vttSettingsAndNotesDoNotBecomeDialogue() {
        val parsed = TextSubtitles.parse("""
            WEBVTT

            NOTE commentary
            ignore me

            cue-id
            00:01.100 --> 00:02.200 align:start position:10%
            <c.green>Text</c>
        """.trimIndent().toByteArray(), "captions.vtt")
        assertEquals(listOf(TextSubtitleCue(1100, 2200, "Text")), parsed.cues)
    }

    @Test fun assUsesEventFieldOrderAndStripsDrawingAndStyle() {
        val parsed = TextSubtitles.parse("""
            [Script Info]
            Title: Test
            [Events]
            Format: Layer, End, Start, Text
            Dialogue: 0,0:00:03.00,0:00:01.00,{\b1}Hello\Nworld, with comma
            Dialogue: 1,0:00:04.00,0:00:02.00,{\p1}m 0 0 l 1 1{\p0}Visible
        """.trimIndent().toByteArray(), "captions.ass")
        assertEquals("Hello\nworld, with comma", parsed.cues[0].text)
        assertEquals("Visible", parsed.cues[1].text)
        assertEquals(1000, parsed.cues[0].startMs)
    }

    @Test fun boundedReadRejectsOversizedAndParsingRejectsInvalidInput() {
        assertReason(SubtitleImportError.TOO_LARGE) {
            TextSubtitles.readBounded(ByteArrayInputStream(ByteArray(TextSubtitles.MAX_BYTES + 1)))
        }
        assertReason(SubtitleImportError.INVALID_CONTENT) { TextSubtitles.parse("garbage".toByteArray(), "x.srt") }
        assertReason(SubtitleImportError.INVALID_CONTENT) {
            TextSubtitles.parse("00:00:03,000 --> 00:00:01,000\nText".toByteArray(), "x.srt")
        }
        assertReason(SubtitleImportError.UNSUPPORTED_FORMAT) { TextSubtitles.parse(srt.toByteArray(), "x.pdf") }
        assertReason(SubtitleImportError.INVALID_CONTENT) { TextSubtitles.parse(byteArrayOf(0, 1, 2, 3), "x.srt") }
    }

    @Test(expected = CancellationException::class) fun cancellationIsNotConvertedToParseFailure() {
        TextSubtitles.readBounded(ByteArrayInputStream(srt.toByteArray())) { throw CancellationException() }
    }

    @Test(timeout = 2_000) fun unterminatedMarkupCannotCauseUnboundedTagSearches() {
        val raw = "<".repeat(30_000)
        val document = TextSubtitles.parse("1\n00:00:01,000 --> 00:00:03,000\n$raw".toByteArray(), "raw.srt")
        assertEquals(raw, document.cues.single().text)
        val assText = "{".repeat(30_000)
        val ass = "[Events]\nDialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,$assText"
        assertEquals(assText, TextSubtitles.parse(ass.toByteArray(), "raw.ass").cues.single().text)
    }

    private fun assertReason(reason: SubtitleImportError, action: () -> Unit) {
        try { action(); fail("Expected $reason") } catch (error: SubtitleImportException) { assertEquals(reason, error.reason) }
    }
}
