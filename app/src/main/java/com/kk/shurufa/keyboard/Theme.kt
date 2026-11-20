package com.kk.shurufa.keyboard

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import com.kk.shurufa.Prefs

/** Keyboard colors, modeled on the One UI keyboard the user currently uses. */
data class KbTheme(
    val dark: Boolean,
    val background: Int,
    val bar: Int,
    val key: Int,
    val keyFn: Int,
    val keyPressed: Int,
    val ink: Int,
    val hint: Int,
    val accent: Int,
    val onAccent: Int,
    val tool: Int,
    val border: Int,
    val popup: Int,
) {
    companion object {
        fun resolve(context: Context, prefs: Prefs): KbTheme {
            val night = when (prefs.theme) {
                "dark" -> true
                "light" -> false
                else -> (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            }
            return if (night) KbTheme(
                dark = true,
                background = Color.parseColor("#1C1D20"),
                bar = Color.parseColor("#232427"),
                key = Color.parseColor("#3A3B3F"),
                keyFn = Color.parseColor("#2A2B2E"),
                keyPressed = Color.parseColor("#55565B"),
                ink = Color.parseColor("#EDEEF0"),
                hint = Color.parseColor("#A0A3A9"),
                accent = Color.parseColor("#5BC3CB"),
                onAccent = Color.parseColor("#0A1A1C"),
                tool = Color.parseColor("#2F3034"),
                border = Color.parseColor("#14000000"),
                popup = Color.parseColor("#44454A"),
            ) else KbTheme(
                dark = false,
                background = Color.parseColor("#E3E4E8"),
                bar = Color.parseColor("#F5F5F7"),
                key = Color.parseColor("#FFFFFF"),
                keyFn = Color.parseColor("#C9CBD0"),
                keyPressed = Color.parseColor("#CDD0D5"),
                ink = Color.parseColor("#1B1B1D"),
                hint = Color.parseColor("#86898F"),
                accent = Color.parseColor("#0E6A73"),
                onAccent = Color.parseColor("#FFFFFF"),
                tool = Color.parseColor("#E8E9ED"),
                border = Color.parseColor("#1F000000"),
                popup = Color.parseColor("#FFFFFF"),
            )
        }
    }
}
