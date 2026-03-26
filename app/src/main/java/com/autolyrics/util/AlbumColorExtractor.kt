package com.autolyrics.util

import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import com.autolyrics.model.AlbumColors

object AlbumColorExtractor {

    private const val MIN_CONTRAST_RATIO = 4.5
    private const val MIN_ACCENT_CONTRAST = 3.0
    private val FALLBACK_TEXT = Color.parseColor("#F0F0FF")
    private val DEFAULT_BG = Color.parseColor("#121212")
    private val DEFAULT_ACCENT = Color.parseColor("#FFD54F")

    fun extract(bitmap: Bitmap): AlbumColors {
        val palette = Palette.from(bitmap).maximumColorCount(16).generate()

        val darkVibrant = palette.darkVibrantSwatch
        val vibrant = palette.vibrantSwatch
        val darkMuted = palette.darkMutedSwatch
        val muted = palette.mutedSwatch
        val lightVibrant = palette.lightVibrantSwatch
        val dominant = palette.dominantSwatch

        val bgSwatch = darkVibrant ?: darkMuted ?: muted ?: dominant
        val accentSwatch = vibrant ?: lightVibrant ?: darkVibrant ?: dominant

        val bgColor = bgSwatch?.rgb ?: DEFAULT_BG
        val accentColor = accentSwatch?.rgb ?: DEFAULT_ACCENT

        val dominantClamped = clampBrightness(bgColor, maxBrightness = 0.25f)
        val dominantDark = darken(dominantClamped, 0.4f)

        val rawTextPrimary = bgSwatch?.bodyTextColor
            ?: lightVibrant?.bodyTextColor
            ?: FALLBACK_TEXT
        val textPrimary = ensureContrast(
            ensureMinBrightness(rawTextPrimary, 0.7f),
            dominantClamped,
            MIN_CONTRAST_RATIO
        )
        val textDim = setAlpha(textPrimary, 0.6f)

        val vibrantFinal = ensureContrast(
            ensureMinBrightness(accentColor, 0.45f),
            dominantClamped,
            MIN_ACCENT_CONTRAST
        )

        return AlbumColors(
            dominant = dominantClamped,
            dominantDark = dominantDark,
            vibrant = vibrantFinal,
            textPrimary = textPrimary,
            textDim = textDim
        )
    }

    private fun ensureContrast(foreground: Int, background: Int, minRatio: Double): Int {
        val opaqueForground = Color.rgb(Color.red(foreground), Color.green(foreground), Color.blue(foreground))
        val ratio = ColorUtils.calculateContrast(opaqueForground, background)
        if (ratio >= minRatio) return foreground

        val hsv = FloatArray(3)
        Color.colorToHSV(foreground, hsv)
        for (step in 1..20) {
            hsv[2] = (hsv[2] + 0.05f).coerceAtMost(1.0f)
            val candidate = Color.HSVToColor(Color.alpha(foreground), hsv)
            val opaque = Color.rgb(Color.red(candidate), Color.green(candidate), Color.blue(candidate))
            if (ColorUtils.calculateContrast(opaque, background) >= minRatio) return candidate
        }
        return Color.argb(Color.alpha(foreground), 0xF0, 0xF0, 0xFF)
    }

    private fun clampBrightness(color: Int, maxBrightness: Float): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        if (hsv[2] > maxBrightness) hsv[2] = maxBrightness
        return Color.HSVToColor(hsv)
    }

    private fun ensureMinBrightness(color: Int, minBrightness: Float): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        if (hsv[2] < minBrightness) hsv[2] = minBrightness
        return Color.HSVToColor(hsv)
    }

    private fun darken(color: Int, factor: Float): Int {
        val r = (Color.red(color) * factor).toInt().coerceIn(0, 255)
        val g = (Color.green(color) * factor).toInt().coerceIn(0, 255)
        val b = (Color.blue(color) * factor).toInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }

    private fun setAlpha(color: Int, alpha: Float): Int {
        val a = (alpha * 255).toInt().coerceIn(0, 255)
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
    }
}
