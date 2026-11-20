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
    val keyFnPressed: Int,
    val accentPressed: Int,
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
                background = Color.parseColor("#17191C"),
                bar = Color.parseColor("#17191C"),
                key = Color.parseColor("#33363B"),
                keyFn = Color.parseColor("#25282C"),
                keyPressed = Color.parseColor("#55565B"),
                keyFnPressed = Color.parseColor("#3E3F44"),
                accentPressed = Color.parseColor("#7FD3D9"),
                ink = Color.parseColor("#EDEEF0"),
                hint = Color.parseColor("#8E949C"),
                accent = Color.parseColor("#5BC3CB"),
                onAccent = Color.parseColor("#0A1A1C"),
                tool = Color.parseColor("#2A2D31"),
                border = Color.parseColor("#14000000"),
                popup = Color.parseColor("#44454A"),
            ) else KbTheme(
                dark = false,
                background = Color.parseColor("#E4E7EB"),
                bar = Color.parseColor("#E4E7EB"),
                key = Color.parseColor("#FFFFFF"),
                keyFn = Color.parseColor("#CDD2D9"),
                keyPressed = Color.parseColor("#D5DAE0"),
                keyFnPressed = Color.parseColor("#B9C0C9"),
                accentPressed = Color.parseColor("#0A535A"),
                ink = Color.parseColor("#1A1D21"),
                hint = Color.parseColor("#98A0AA"),
                accent = Color.parseColor("#0E6A73"),
                onAccent = Color.parseColor("#FFFFFF"),
                tool = Color.parseColor("#D3D8DE"),
                border = Color.parseColor("#1F000000"),
                popup = Color.parseColor("#FFFFFF"),
            )
        }
    }
}
