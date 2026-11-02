package com.clipvault.mobile.keyboard

import android.content.Context
import android.graphics.Color
import org.json.JSONObject

data class ThemeColors(
    val background: Int,
    val surface: Int,
    val key: Int,
    val specialKey: Int,
    val text: Int,
    val muted: Int,
    val accent: Int,
    val stroke: Int,
    val radius: Int,
    val elevation: Float
)

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
    ROSE_PETAL("Rose")
}

/**
 * KeyboardThemeManager — Manages keyboard color palettes, JSON themes loading,
 * and preview styling across all 10 themes.
 */
object KeyboardThemeManager {

    private var cachedThemesJson: JSONObject? = null

    fun getThemesJson(context: Context): JSONObject {
        if (cachedThemesJson == null) {
            val jsonString = runCatching {
                context.assets.open("themes.json").bufferedReader().use { it.readText() }
            }.getOrDefault("{}")
            cachedThemesJson = runCatching { JSONObject(jsonString) }.getOrDefault(JSONObject())
        }
        return cachedThemesJson!!
    }

    fun colorsForTheme(theme: KeyboardTheme, context: Context): ThemeColors {
        val themes = getThemesJson(context)
        val themeKey = theme.name
        if (themes.has(themeKey)) {
            val tObj = themes.getJSONObject(themeKey)
            if (tObj.has("keyboard")) {
                val k = tObj.getJSONObject("keyboard")
                return ThemeColors(
                    background = Color.parseColor(k.getString("background")),
                    surface = Color.parseColor(k.getString("surface")),
                    key = Color.parseColor(k.getString("key")),
                    specialKey = Color.parseColor(k.getString("specialKey")),
                    text = Color.parseColor(k.getString("text")),
                    muted = Color.parseColor(k.getString("muted")),
                    accent = Color.parseColor(k.getString("accent")),
                    stroke = Color.parseColor(k.getString("stroke")),
                    radius = k.getInt("radius"),
                    elevation = k.getDouble("elevation").toFloat()
                )
            }
        }
        // Fallback default dark
        return ThemeColors(
            background = Color.rgb(27, 27, 32),
            surface = Color.rgb(34, 35, 41),
            key = Color.rgb(55, 56, 64),
            specialKey = Color.rgb(74, 83, 107),
            text = Color.rgb(240, 242, 247),
            muted = Color.rgb(170, 174, 186),
            accent = Color.rgb(231, 236, 255),
            stroke = Color.rgb(60, 61, 70),
            radius = 8,
            elevation = 1f
        )
    }

    fun previewColorsForKey(key: String, context: Context): ThemeColors? {
        if (!key.startsWith("theme_")) return null
        val theme = runCatching { KeyboardTheme.valueOf(key.removePrefix("theme_")) }.getOrNull() ?: return null
        return colorsForTheme(theme, context)
    }
}
