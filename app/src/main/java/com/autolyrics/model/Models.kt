package com.autolyrics.model

data class TrackInfo(
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long
)

data class LyricWord(
    val timeMs: Long,
    val text: String
)

data class LyricLine(
    val timeMs: Long,
    val text: String,
    val words: List<LyricWord> = emptyList()
)

enum class LyricsStatus {
    NO_MEDIA,
    LOADING,
    FOUND,
    PLAIN_ONLY,
    NOT_FOUND,
    ERROR
}

data class LyricsState(
    val track: TrackInfo? = null,
    val lines: List<LyricLine> = emptyList(),
    val currentIndex: Int = -1,
    val currentWordIndex: Int = -1,
    val isPlaying: Boolean = false,
    val status: LyricsStatus = LyricsStatus.NO_MEDIA
)
