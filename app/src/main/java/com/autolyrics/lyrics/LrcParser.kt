package com.autolyrics.lyrics

import com.autolyrics.model.LyricLine
import com.autolyrics.model.LyricWord

object LrcParser {

    private val LINE_TIMESTAMP = Regex("""\[(\d{2}):(\d{2})\.(\d{2,3})]""")
    private val WORD_TIMESTAMP = Regex("""<(\d{2}):(\d{2})\.(\d{2,3})>""")

    fun parse(lrc: String): List<LyricLine> {
        return lrc.lines()
            .flatMap { line -> parseLine(line) }
            .sortedBy { it.timeMs }
    }

    fun parseKaraoke(lrc: String): List<LyricLine> {
        return lrc.lines()
            .flatMap { line -> parseKaraokeLine(line) }
            .sortedBy { it.timeMs }
    }

    private fun parseLine(line: String): List<LyricLine> {
        val timestamps = mutableListOf<Long>()
        var remaining = line.trim()

        while (remaining.startsWith("[")) {
            val match = LINE_TIMESTAMP.find(remaining) ?: break
            if (match.range.first != 0) break
            timestamps.add(parseTimestamp(match))
            remaining = remaining.substring(match.range.last + 1)
        }

        if (timestamps.isEmpty()) return emptyList()

        val text = remaining.trim()
        return timestamps.map { ts ->
            LyricLine(ts, text.ifBlank { "♪" })
        }
    }

    private fun parseKaraokeLine(line: String): List<LyricLine> {
        val timestamps = mutableListOf<Long>()
        var remaining = line.trim()

        while (remaining.startsWith("[")) {
            val match = LINE_TIMESTAMP.find(remaining) ?: break
            if (match.range.first != 0) break
            timestamps.add(parseTimestamp(match))
            remaining = remaining.substring(match.range.last + 1)
        }

        if (timestamps.isEmpty()) return emptyList()

        val words = parseWords(remaining)
        val fullText = words.joinToString(" ") { it.text }.trim()

        return timestamps.map { ts ->
            if (words.isNotEmpty() && fullText.isNotBlank()) {
                LyricLine(ts, fullText, words)
            } else {
                LyricLine(ts, fullText.ifBlank { "♪" })
            }
        }
    }

    private fun parseWords(text: String): List<LyricWord> {
        val words = mutableListOf<LyricWord>()
        var remaining = text

        while (remaining.isNotEmpty()) {
            val match = WORD_TIMESTAMP.find(remaining)
            if (match == null) {
                val leftover = remaining.trim()
                if (leftover.isNotBlank() && words.isNotEmpty()) {
                    val last = words.removeAt(words.lastIndex)
                    words.add(LyricWord(last.timeMs, (last.text + " " + leftover).trim()))
                }
                break
            }

            val beforeTag = remaining.substring(0, match.range.first).trim()
            if (beforeTag.isNotBlank() && words.isNotEmpty()) {
                val last = words.removeAt(words.lastIndex)
                words.add(LyricWord(last.timeMs, (last.text + " " + beforeTag).trim()))
            }

            val timeMs = parseTimestamp(match)
            remaining = remaining.substring(match.range.last + 1)

            val nextMatch = WORD_TIMESTAMP.find(remaining)
            val wordText = if (nextMatch != null) {
                remaining.substring(0, nextMatch.range.first).trim()
            } else {
                remaining.trim().also { remaining = "" }
            }
            if (nextMatch != null) {
                remaining = remaining.substring(nextMatch.range.first)
            }

            if (wordText.isNotBlank()) {
                words.add(LyricWord(timeMs, wordText))
            } else {
                words.add(LyricWord(timeMs, ""))
            }
        }

        return words.filter { it.text.isNotBlank() }
    }

    private fun parseTimestamp(match: MatchResult): Long {
        val min = match.groupValues[1].toLong()
        val sec = match.groupValues[2].toLong()
        val msRaw = match.groupValues[3]
        val ms = if (msRaw.length == 2) msRaw.toLong() * 10 else msRaw.toLong()
        return min * 60_000 + sec * 1_000 + ms
    }
}
