package com.autolyrics.lyrics

import com.autolyrics.model.LyricLine

object LrcParser {

    private val TIMESTAMP = Regex("""\[(\d{2}):(\d{2})\.(\d{2,3})]""")

    fun parse(lrc: String): List<LyricLine> {
        return lrc.lines()
            .flatMap { line -> parseLine(line) }
            .sortedBy { it.timeMs }
    }

    private fun parseLine(line: String): List<LyricLine> {
        val timestamps = mutableListOf<Long>()
        var remaining = line.trim()

        while (remaining.startsWith("[")) {
            val match = TIMESTAMP.find(remaining) ?: break
            if (match.range.first != 0) break

            val min = match.groupValues[1].toLong()
            val sec = match.groupValues[2].toLong()
            val msRaw = match.groupValues[3]
            val ms = if (msRaw.length == 2) msRaw.toLong() * 10 else msRaw.toLong()
            timestamps.add(min * 60_000 + sec * 1_000 + ms)

            remaining = remaining.substring(match.range.last + 1)
        }

        if (timestamps.isEmpty()) return emptyList()

        val text = remaining.trim()
        return timestamps.map { ts ->
            LyricLine(ts, text.ifBlank { "♪" })
        }
    }
}
