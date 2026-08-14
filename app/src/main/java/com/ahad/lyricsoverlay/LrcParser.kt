package com.ahad.lyricsoverlay

data class LrcLine(
    val timestampMs: Long,
    val text: String
)

object LrcParser {

    private val timestampRegex = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val offsetRegex = Regex("""\[offset:([+-]?\d+)]""", RegexOption.IGNORE_CASE)
    private val metadataRegex = Regex("""^\[(ar|ti|al|by|re|ve|length):.*]$""", RegexOption.IGNORE_CASE)

    fun parse(rawLrc: String): List<LrcLine> {
        if (rawLrc.isBlank()) return emptyList()

        val offset = offsetRegex.find(rawLrc)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
        val parsed = mutableListOf<LrcLine>()

        rawLrc.lineSequence().forEach { originalLine ->
            val line = originalLine.trim().removePrefix("\uFEFF")
            if (line.isBlank() || metadataRegex.matches(line) || offsetRegex.matches(line)) {
                return@forEach
            }

            val timestamps = timestampRegex.findAll(line).toList()
            if (timestamps.isEmpty()) return@forEach

            val lyricText = timestampRegex.replace(line, "").trim()
            if (lyricText.isBlank()) return@forEach

            timestamps.forEach { match ->
                val minutes = match.groupValues[1].toLong()
                val seconds = match.groupValues[2].toLong()
                val fractionText = match.groupValues.getOrNull(3).orEmpty()
                val fractionMs = when (fractionText.length) {
                    1 -> fractionText.toLongOrNull()?.times(100L) ?: 0L
                    2 -> fractionText.toLongOrNull()?.times(10L) ?: 0L
                    3 -> fractionText.toLongOrNull() ?: 0L
                    else -> 0L
                }
                val timestamp = (minutes * 60_000L + seconds * 1_000L + fractionMs + offset)
                    .coerceAtLeast(0L)
                parsed += LrcLine(timestamp, lyricText)
            }
        }

        return parsed
            .distinctBy { it.timestampMs to it.text }
            .sortedBy { it.timestampMs }
    }

    /** Creates the plain-lyrics payload required when a timed LRC is published. */
    fun toPlainLyrics(rawLrc: String): String = rawLrc
        .lineSequence()
        .map { it.trim().removePrefix("\uFEFF") }
        .filterNot { line ->
            line.isBlank() || metadataRegex.matches(line) || offsetRegex.matches(line)
        }
        .map { timestampRegex.replace(it, "").trim() }
        .filter { it.isNotBlank() }
        .joinToString("\n")

    fun lineIndexAt(lines: List<LrcLine>, positionMs: Long): Int {
        if (lines.isEmpty() || positionMs < lines.first().timestampMs) return -1

        var low = 0
        var high = lines.lastIndex
        var answer = -1
        while (low <= high) {
            val middle = (low + high) ushr 1
            if (lines[middle].timestampMs <= positionMs) {
                answer = middle
                low = middle + 1
            } else {
                high = middle - 1
            }
        }
        return answer
    }
}
