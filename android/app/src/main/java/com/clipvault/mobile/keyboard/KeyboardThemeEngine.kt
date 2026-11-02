package com.clipvault.mobile.keyboard

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.util.LruCache

/**
 * KeyboardThemeEngine — Manages themes, color palettes, and cached 3D key drawables.
 */
object KeyboardThemeEngine {

    enum class KeyboardTheme(val displayName: String) {
        CLASSIC_DARK("Classic"),
        SOFT_IVORY("Ivory"),
        NEON_GLASS("Neon"),
        MIDNIGHT_GOLD("Gold"),
        GRAPHITE("Graphite"),
        OCEAN_BLUE("Ocean"),
        FOREST_GREEN("Forest"),
        ROYAL_PURPLE("Purple"),
        SUNSET_ORANGE("Orange"),
        ROSE_PETAL("Rose"),
    }

    data class ThemeColors(
        val background: Int,
        val key: Int,
        val keyControl: Int,
        val text: Int,
        val accent: Int,
        val muted: Int,
        val stroke: Int,
        val radius: Int,
    )

    private val drawableCache = LruCache<String, StateListDrawable>(120)

    fun themeColors(theme: KeyboardTheme): ThemeColors {
        return when (theme) {
            KeyboardTheme.CLASSIC_DARK -> ThemeColors(
                background = Color.parseColor("#12131C"),
                key = Color.parseColor("#1F2232"),
                keyControl = Color.parseColor("#2A2E42"),
                text = Color.parseColor("#FFFFFF"),
                accent = Color.parseColor("#6366F1"),
                muted = Color.parseColor("#94A3B8"),
                stroke = Color.parseColor("#334155"),
                radius = 10,
            )
            KeyboardTheme.SOFT_IVORY -> ThemeColors(
                background = Color.parseColor("#F5F3EF"),
                key = Color.parseColor("#FFFFFF"),
                keyControl = Color.parseColor("#EAE7E1"),
                text = Color.parseColor("#1C1917"),
                accent = Color.parseColor("#8B5CF6"),
                muted = Color.parseColor("#78716C"),
                stroke = Color.parseColor("#D6D3D1"),
                radius = 10,
            )
            KeyboardTheme.NEON_GLASS -> ThemeColors(
                background = Color.parseColor("#090D16"),
                key = Color.parseColor("#131B2E"),
                keyControl = Color.parseColor("#1C2744"),
                text = Color.parseColor("#38BDF8"),
                accent = Color.parseColor("#06B6D4"),
                muted = Color.parseColor("#0284C7"),
                stroke = Color.parseColor("#0369A1"),
                radius = 12,
            )
            KeyboardTheme.MIDNIGHT_GOLD -> ThemeColors(
                background = Color.parseColor("#14120E"),
                key = Color.parseColor("#241F18"),
                keyControl = Color.parseColor("#332C22"),
                text = Color.parseColor("#FDE047"),
                accent = Color.parseColor("#EAB308"),
                muted = Color.parseColor("#A16207"),
                stroke = Color.parseColor("#713F12"),
                radius = 10,
            )
            KeyboardTheme.GRAPHITE -> ThemeColors(
                background = Color.parseColor("#18181B"),
                key = Color.parseColor("#27272A"),
                keyControl = Color.parseColor("#3F3F46"),
                text = Color.parseColor("#F4F4F5"),
                accent = Color.parseColor("#71717A"),
                muted = Color.parseColor("#A1A1AA"),
                stroke = Color.parseColor("#52525B"),
                radius = 9,
            )
            KeyboardTheme.OCEAN_BLUE -> ThemeColors(
                background = Color.parseColor("#0B132B"),
                key = Color.parseColor("#1C2541"),
                keyControl = Color.parseColor("#3A506B"),
                text = Color.parseColor("#FFFFFF"),
                accent = Color.parseColor("#48CAE4"),
                muted = Color.parseColor("#89C2D9"),
                stroke = Color.parseColor("#014F86"),
                radius = 10,
            )
            KeyboardTheme.FOREST_GREEN -> ThemeColors(
                background = Color.parseColor("#0A1C14"),
                key = Color.parseColor("#143628"),
                keyControl = Color.parseColor("#1E4D3A"),
                text = Color.parseColor("#D8F3DC"),
                accent = Color.parseColor("#52B788"),
                muted = Color.parseColor("#74C69D"),
                stroke = Color.parseColor("#2D6A4F"),
                radius = 10,
            )
            KeyboardTheme.ROYAL_PURPLE -> ThemeColors(
                background = Color.parseColor("#190E2E"),
                key = Color.parseColor("#2C1B4D"),
                keyControl = Color.parseColor("#3F276E"),
                text = Color.parseColor("#F3E8FF"),
                accent = Color.parseColor("#A855F7"),
                muted = Color.parseColor("#C084FC"),
                stroke = Color.parseColor("#6B21A8"),
                radius = 10,
            )
            KeyboardTheme.SUNSET_ORANGE -> ThemeColors(
                background = Color.parseColor("#1F130E"),
                key = Color.parseColor("#362018"),
                keyControl = Color.parseColor("#4E2E22"),
                text = Color.parseColor("#FFEDD5"),
                accent = Color.parseColor("#F97316"),
                muted = Color.parseColor("#FB923C"),
                stroke = Color.parseColor("#9A3412"),
                radius = 10,
            )
            KeyboardTheme.ROSE_PETAL -> ThemeColors(
                background = Color.parseColor("#1C0F14"),
                key = Color.parseColor("#331B24"),
                keyControl = Color.parseColor("#4A2835"),
                text = Color.parseColor("#FFE4E6"),
                accent = Color.parseColor("#F43F5E"),
                muted = Color.parseColor("#FB7185"),
                stroke = Color.parseColor("#9F1239"),
                radius = 10,
            )
        }
    }

    /**
     * Builds and caches 3D beveled state-list drawables for keyboard buttons.
     */
    fun createKeyBackground(baseColor: Int, colors: ThemeColors, dp: (Int) -> Int): StateListDrawable {
        val cacheKey = "$baseColor|${colors.radius}|${colors.stroke}"
        val cached = drawableCache.get(cacheKey)
        if (cached != null) {
            return (cached.constantState?.newDrawable()?.mutate() as? StateListDrawable) ?: cached
        }

        // Pressed state (flat, accent-tinted)
        val pressedBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(colors.radius).toFloat()
            setColor(baseColor)
            setStroke(dp(1), colors.stroke)
            alpha = 200
        }
        val pressedLayer = LayerDrawable(arrayOf(pressedBg)).apply {
            setLayerInset(0, dp(2), dp(2), dp(2), dp(2))
        }

        // Normal state (with 3D bottom bevel)
        val bevelColor = Color.argb(
            255,
            (Color.red(baseColor) * 0.8).toInt(),
            (Color.green(baseColor) * 0.8).toInt(),
            (Color.blue(baseColor) * 0.8).toInt()
        )
        val shadowBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(colors.radius).toFloat()
            setColor(bevelColor)
        }
        val topBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(colors.radius).toFloat()
            setColor(baseColor)
            setStroke(dp(1), colors.stroke)
        }
        val normalLayer = LayerDrawable(arrayOf(shadowBg, topBg)).apply {
            setLayerInset(0, dp(2), dp(2), dp(2), dp(2))
            setLayerInset(1, dp(2), dp(2), dp(2), dp(6))
        }

        val stateList = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), pressedLayer)
            addState(intArrayOf(), normalLayer)
        }

        drawableCache.put(cacheKey, stateList)
        return stateList
    }
}
