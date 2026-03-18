package com.autolyrics.util

import android.graphics.Bitmap
import android.graphics.Color
import androidx.palette.graphics.Palette
import com.autolyrics.model.AlbumColors

object AlbumColorExtractor {

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

        val vibrantFinal = ensureMinBrightness(accentColor, 0.45f)

        val textPrimary = bgSwatch?.bodyTextColor
            ?: lightVibrant?.bodyTextColor
            ?: Color.parseColor("#EEEEFF")
        val textDim = setAlpha(textPrimary, 0.5f)

        return AlbumColors(
            dominant = dominantClamped,
            dominantDark = dominantDark,
            vibrant = vibrantFinal,
            textPrimary = ensureMinBrightness(textPrimary, 0.7f),
            textDim = textDim
        )
    }

    private fun clampBrightness(color: Int, maxBrightness: Float): Float3Color {
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

    private val DEFAULT_BG = Color.parseColor("#121212")
    private val DEFAULT_ACCENT = Color.parseColor("#FFD54F")
}

private typealias Float3Color = Int
