package com.autolyrics.util

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.autolyrics.model.LyricLine

class AudioSyncHelper(
    private val context: Context,
    private val onResult: (offsetMs: Long) -> Unit,
    private val onStatus: (message: String) -> Unit,
    private val onError: (message: String) -> Unit
) {
    private var speechRecognizer: SpeechRecognizer? = null
    private var lyrics: List<LyricLine> = emptyList()
    private var startTimeMs: Long = 0
    private var currentPositionAtStart: Long = 0
    private var active = false

    fun start(lines: List<LyricLine>, currentRawPositionMs: Long) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onError("Speech recognition not available on this device")
            return
        }

        lyrics = lines.filter { it.text.isNotBlank() && it.timeMs > 0 }
        if (lyrics.isEmpty()) {
            onError("No synced lyrics available for matching")
            return
        }

        currentPositionAtStart = currentRawPositionMs
        startTimeMs = System.currentTimeMillis()
        active = true

        onStatus("Listening...")

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    onStatus("Listening... speak or play music")
                }

                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}

                override fun onError(error: Int) {
                    if (!active) return
                    val msg = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH -> "Couldn't match speech. Try again closer to a speaker."
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected. Try again closer to a speaker."
                        SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission required"
                        else -> "Recognition error ($error)"
                    }
                    active = false
                    onError(msg)
                }

                override fun onResults(results: Bundle?) {
                    if (!active) return
                    val recognitionTime = System.currentTimeMillis()
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    processResults(matches, recognitionTime)
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    if (!active) return
                    val recognitionTime = System.currentTimeMillis()
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    processPartialResults(matches, recognitionTime)
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 5000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 4000L)
            }
            startListening(intent)
        }
    }

    fun stop() {
        active = false
        speechRecognizer?.stopListening()
        speechRecognizer?.destroy()
        speechRecognizer = null
    }

    private fun processPartialResults(matches: List<String>?, recognitionTime: Long) {
        if (matches.isNullOrEmpty()) return
        val bestMatch = findBestMatch(matches)
        if (bestMatch != null) {
            val elapsedSinceStart = recognitionTime - startTimeMs
            val estimatedPositionAtRecognition = currentPositionAtStart + elapsedSinceStart
            val offset = estimatedPositionAtRecognition - bestMatch.timeMs
            active = false
            stop()
            onResult(offset)
        }
    }

    private fun processResults(matches: List<String>?, recognitionTime: Long) {
        if (matches.isNullOrEmpty()) {
            onError("Couldn't recognize any speech. Try again closer to a speaker.")
            return
        }

        val bestMatch = findBestMatch(matches)
        if (bestMatch != null) {
            val elapsedSinceStart = recognitionTime - startTimeMs
            val estimatedPositionAtRecognition = currentPositionAtStart + elapsedSinceStart
            val offset = estimatedPositionAtRecognition - bestMatch.timeMs
            onResult(offset)
        } else {
            onError("Couldn't match speech to lyrics. Try \"Tap to Sync\" instead.")
        }
    }

    private fun findBestMatch(recognizedTexts: List<String>): LyricLine? {
        for (recognized in recognizedTexts) {
            val recognizedWords = normalizeText(recognized).split("\\s+".toRegex())
            if (recognizedWords.size < 2) continue

            var bestScore = 0
            var bestLine: LyricLine? = null

            for (line in lyrics) {
                val lineWords = normalizeText(line.text).split("\\s+".toRegex())
                val score = consecutiveMatchScore(recognizedWords, lineWords)
                if (score > bestScore) {
                    bestScore = score
                    bestLine = line
                }
            }

            if (bestScore >= 3 || (bestScore >= 2 && recognizedWords.size <= 3)) {
                return bestLine
            }
        }
        return null
    }

    private fun consecutiveMatchScore(recognized: List<String>, lyricWords: List<String>): Int {
        if (recognized.isEmpty() || lyricWords.isEmpty()) return 0
        var bestRun = 0
        for (startR in recognized.indices) {
            for (startL in lyricWords.indices) {
                var run = 0
                var r = startR
                var l = startL
                while (r < recognized.size && l < lyricWords.size) {
                    if (fuzzyMatch(recognized[r], lyricWords[l])) {
                        run++
                        r++
                        l++
                    } else {
                        break
                    }
                }
                if (run > bestRun) bestRun = run
            }
        }
        return bestRun
    }

    private fun fuzzyMatch(a: String, b: String): Boolean {
        if (a == b) return true
        if (a.length < 3 || b.length < 3) return a == b
        val maxDist = if (a.length > 5 || b.length > 5) 2 else 1
        return levenshtein(a, b) <= maxDist
    }

    private fun levenshtein(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[i][j] = minOf(dp[i - 1][j] + 1, dp[i][j - 1] + 1, dp[i - 1][j - 1] + cost)
            }
        }
        return dp[a.length][b.length]
    }

    private fun normalizeText(text: String): String {
        return text.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), "")
            .trim()
    }
}
