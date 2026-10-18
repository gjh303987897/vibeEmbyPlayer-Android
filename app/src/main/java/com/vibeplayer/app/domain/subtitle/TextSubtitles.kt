package com.vibeplayer.app.domain.subtitle

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

data class TextSubtitleCue(val startMs: Long, val endMs: Long, val text: String)

enum class TextSubtitleFormat { SRT, WEBVTT, ASS, SSA }

enum class SubtitleImportError { TOO_LARGE, UNSUPPORTED_FORMAT, UNSUPPORTED_ENCODING, INVALID_CONTENT, UNREADABLE, NO_PLAYBACK, SESSION_CHANGED }

class SubtitleImportException(val reason: SubtitleImportError) : Exception(reason.name)

data class TextSubtitleDocument(
    val label: String,
    val format: TextSubtitleFormat,
    val encoding: String,
    val cues: List<TextSubtitleCue>
)

/** Bounded text-only parsing shared by SAF imports and server-provided sidecars. */
object TextSubtitles {
    const val MAX_BYTES = 4 * 1024 * 1024
    const val MAX_CUES = 50_000
    private const val MAX_CUE_TEXT = 32 * 1024

    fun readBounded(input: InputStream, check: () -> Unit = {}): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            check()
            val count = input.read(buffer)
            if (count < 0) break
            if (count > MAX_BYTES - output.size()) fail(SubtitleImportError.TOO_LARGE)
            output.write(buffer, 0, count)
        }
        check()
        return output.toByteArray()
    }

    fun parse(bytes: ByteArray, label: String, mimeType: String? = null, check: () -> Unit = {}): TextSubtitleDocument {
        if (bytes.size > MAX_BYTES) fail(SubtitleImportError.TOO_LARGE)
        if (bytes.isEmpty()) fail(SubtitleImportError.INVALID_CONTENT)
        check()
        val (decoded, encoding) = decode(bytes)
        if (decoded.any { it == '\u0000' || (it < ' ' && it !in "\t\r\n") }) fail(SubtitleImportError.INVALID_CONTENT)
        val text = decoded.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        val extension = label.substringAfterLast('.', "").lowercase()
        val format = when (extension) {
            "srt" -> TextSubtitleFormat.SRT
            "vtt" -> TextSubtitleFormat.WEBVTT
            "ass" -> TextSubtitleFormat.ASS
            "ssa" -> TextSubtitleFormat.SSA
            else -> when (mimeType?.substringBefore(';')?.lowercase()) {
                "application/x-subrip", "application/srt", "text/srt" -> TextSubtitleFormat.SRT
                "text/vtt" -> TextSubtitleFormat.WEBVTT
                "text/x-ssa", "text/x-ass", "application/x-ass", "application/x-ssa" -> TextSubtitleFormat.ASS
                else -> when {
                    text.trimStart().startsWith("WEBVTT") -> TextSubtitleFormat.WEBVTT
                    text.contains("[Events]", ignoreCase = true) -> TextSubtitleFormat.ASS
                    extension.isEmpty() || extension == "txt" -> TextSubtitleFormat.SRT
                    else -> fail(SubtitleImportError.UNSUPPORTED_FORMAT)
                }
            }
        }
        val cues = when (format) {
            TextSubtitleFormat.SRT, TextSubtitleFormat.WEBVTT -> parseTimedBlocks(text, format, check)
            TextSubtitleFormat.ASS, TextSubtitleFormat.SSA -> parseAss(text, check)
        }.sortedWith(compareBy(TextSubtitleCue::startMs, TextSubtitleCue::endMs))
        if (cues.isEmpty()) fail(SubtitleImportError.INVALID_CONTENT)
        return TextSubtitleDocument(label.take(200), format, encoding, cues)
    }

    private fun decode(bytes: ByteArray): Pair<String, String> {
        fun matches(vararg values: Int) = values.indices.all { index -> bytes.getOrNull(index)?.toInt()?.and(255) == values[index] }
        fun read(charset: String, offset: Int = 0): Pair<String, String> {
            val decoder = Charset.forName(charset).newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            return decoder.decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset)).toString() to charset
        }
        try {
            if (matches(0xff, 0xfe, 0, 0) || matches(0, 0, 0xfe, 0xff)) fail(SubtitleImportError.UNSUPPORTED_ENCODING)
            if (matches(0xef, 0xbb, 0xbf)) return read("UTF-8", 3)
            if (matches(0xff, 0xfe)) return read("UTF-16LE", 2)
            if (matches(0xfe, 0xff)) return read("UTF-16BE", 2)
            val sample = bytes.take(256)
            if (sample.size >= 8) {
                val pairs = sample.size / 2
                val evenZeros = (0 until pairs).count { sample[it * 2] == 0.toByte() }
                val oddZeros = (0 until pairs).count { sample[it * 2 + 1] == 0.toByte() }
                if (oddZeros > pairs / 3 && evenZeros < pairs / 8) return read("UTF-16LE")
                if (evenZeros > pairs / 3 && oddZeros < pairs / 8) return read("UTF-16BE")
            }
            return try { read("UTF-8") } catch (_: CharacterCodingException) { read("GB18030") }
        } catch (_: CharacterCodingException) {
            fail(SubtitleImportError.UNSUPPORTED_ENCODING)
        }
    }

    private fun parseTimedBlocks(text: String, format: TextSubtitleFormat, check: () -> Unit): List<TextSubtitleCue> {
        val cues = ArrayList<TextSubtitleCue>()
        for (block in text.split(Regex("\n[\t ]*\n"))) {
            check()
            val lines = block.trim().lines()
            val first = lines.firstOrNull().orEmpty()
            if (format == TextSubtitleFormat.WEBVTT && (first == "STYLE" || first == "REGION" || first == "NOTE" || first.startsWith("NOTE "))) continue
            val timingIndex = lines.indexOfFirst { "-->" in it }
            if (timingIndex < 0) continue
            val timing = lines[timingIndex].split("-->", limit = 2)
            val start = timestamp(timing[0].trim()) ?: fail(SubtitleImportError.INVALID_CONTENT)
            val end = timestamp(timing[1].trim().substringBefore(' ').substringBefore('\t')) ?: fail(SubtitleImportError.INVALID_CONTENT)
            val raw = lines.drop(timingIndex + 1).joinToString("\n")
            if (raw.length > MAX_CUE_TEXT * 4) fail(SubtitleImportError.TOO_LARGE)
            val content = stripMarkup(raw)
            addCue(cues, start, end, content)
        }
        return cues
    }

    private fun parseAss(text: String, check: () -> Unit): List<TextSubtitleCue> {
        val cues = ArrayList<TextSubtitleCue>()
        var events = false
        var fields = listOf("layer", "start", "end", "style", "name", "marginl", "marginr", "marginv", "effect", "text")
        for (line in text.lineSequence()) {
            check()
            val value = line.trim()
            if (value.startsWith('[') && value.endsWith(']')) {
                events = value.equals("[Events]", true)
                continue
            }
            if (!events) continue
            if (value.startsWith("Format:", true)) {
                fields = value.substringAfter(':').split(',').map { it.trim().lowercase() }
                if (fields.size > 32 || !fields.containsAll(listOf("start", "end", "text")) || fields.last() != "text") fail(SubtitleImportError.INVALID_CONTENT)
                continue
            }
            if (!value.startsWith("Dialogue:", true)) continue
            val parts = value.substringAfter(':').split(',', limit = fields.size)
            if (parts.size != fields.size) fail(SubtitleImportError.INVALID_CONTENT)
            val start = timestamp(parts[fields.indexOf("start")].trim()) ?: fail(SubtitleImportError.INVALID_CONTENT)
            val end = timestamp(parts[fields.indexOf("end")].trim()) ?: fail(SubtitleImportError.INVALID_CONTENT)
            val raw = parts[fields.indexOf("text")]
            if (raw.length > MAX_CUE_TEXT * 4) fail(SubtitleImportError.TOO_LARGE)
            val content = stripAss(raw)
            addCue(cues, start, end, content)
        }
        return cues
    }

    private fun addCue(cues: MutableList<TextSubtitleCue>, start: Long, end: Long, content: String) {
        if (end <= start) fail(SubtitleImportError.INVALID_CONTENT)
        if (content.length > MAX_CUE_TEXT || cues.size >= MAX_CUES) fail(SubtitleImportError.TOO_LARGE)
        if (content.isNotBlank()) cues.add(TextSubtitleCue(start, end, content))
    }

    private val timePattern = Regex("^(?:(\\d{1,4}):)?(\\d{1,2}):(\\d{2})[.,](\\d{1,3})$")
    private fun timestamp(value: String): Long? {
        val match = timePattern.matchEntire(value) ?: return null
        val hours = match.groupValues[1].toLongOrNull() ?: 0L
        val minutes = match.groupValues[2].toLong()
        val seconds = match.groupValues[3].toLong()
        if (minutes >= 60 || seconds >= 60) return null
        val millis = match.groupValues[4].padEnd(3, '0').toLong()
        return ((hours * 60 + minutes) * 60 + seconds) * 1000 + millis
    }

    private fun stripMarkup(value: String): String {
        val result = StringBuilder()
        var cursor = 0
        while (cursor < value.length) {
            val start = value.indexOf('<', cursor)
            if (start < 0) { result.append(value, cursor, value.length); break }
            result.append(value, cursor, start)
            val end = value.indexOf('>', start + 1)
            if (end < 0) { result.append(value, start, value.length); break }
            cursor = end + 1
        }
        return result.toString().replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&").trim()
    }

    private fun stripAss(value: String): String {
        val result = StringBuilder()
        var drawing = false
        var cursor = 0
        while (cursor < value.length) {
            val start = value.indexOf('{', cursor)
            if (start < 0) break
            if (!drawing) result.append(value, cursor, start)
            val end = value.indexOf('}', start + 1)
            if (end < 0) { cursor = start; break }
            Regex("\\\\p(\\d+)").findAll(value.substring(start + 1, end)).forEach { drawing = it.groupValues[1] != "0" }
            cursor = end + 1
        }
        if (!drawing) result.append(value, cursor, value.length)
        return result.toString().replace("\\N", "\n").replace("\\n", "\n").replace("\\h", " ").trim()
    }

    private fun fail(reason: SubtitleImportError): Nothing = throw SubtitleImportException(reason)
}

/** End-exclusive cues, including overlapping dialogue; old cues are skipped with a prefix-end index. */
class TextSubtitleTimeline(cues: List<TextSubtitleCue>) {
    private val cues = cues.sortedWith(compareBy(TextSubtitleCue::startMs, TextSubtitleCue::endMs))
    private val maximumEnd = LongArray(this.cues.size).also { ends ->
        var end = 0L
        this.cues.forEachIndexed { index, cue -> end = maxOf(end, cue.endMs); ends[index] = end }
    }

    fun textAt(positionMs: Long): String? {
        var low = 0
        var high = cues.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (cues[middle].startMs <= positionMs) low = middle + 1 else high = middle
        }
        var index = low - 1
        val active = ArrayList<String>()
        var characters = 0
        while (index >= 0 && maximumEnd[index] > positionMs && active.size < 16) {
            val cue = cues[index--]
            if (cue.endMs > positionMs && characters + cue.text.length <= 32 * 1024) {
                active.add(cue.text)
                characters += cue.text.length
            }
        }
        return active.takeIf { it.isNotEmpty() }?.asReversed()?.joinToString("\n")
    }
}
