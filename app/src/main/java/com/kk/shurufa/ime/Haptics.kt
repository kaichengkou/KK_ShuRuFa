package com.kk.shurufa.ime

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.kk.shurufa.Prefs

/** Every moment the keyboard can vibrate for; each one has its own effect, strength and length. */
enum class HapticEvent(val id: String, val title: String, val defType: String, val defAmp: Int, val defMs: Int) {
    LETTER("letter", "字母和字符键", "custom", 60, 14),
    FUNCTION("function", "功能键", "custom", 70, 16),
    SPACE("space", "空格", "custom", 70, 16),
    BACKSPACE("backspace", "删除", "custom", 80, 18),
    ENTER("enter", "回车", "click", 90, 20),
    POPUP("popup", "长按弹出选项", "heavy", 120, 24),
    OPTION("option", "在选项间滑动", "tick", 40, 8),
    CURSOR("cursor", "拖动光标", "tick", 35, 6),
    GESTURE("gesture", "手势生效（切换语言、清空、撤销）", "double", 120, 20);

    companion object {
        val TYPES = listOf("off" to "关", "tick" to "轻触", "click" to "点击", "heavy" to "重击", "double" to "双击", "custom" to "自定义")
    }
}

class Haptics(context: Context, private val prefs: Prefs) {
    private val vib: Vibrator = if (Build.VERSION.SDK_INT >= 31)
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
    else @Suppress("DEPRECATION") context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

    fun type(e: HapticEvent) = prefs.sp.getString("hap_${e.id}_type", e.defType) ?: e.defType
    fun amp(e: HapticEvent) = prefs.sp.getInt("hap_${e.id}_amp", e.defAmp)
    fun ms(e: HapticEvent) = prefs.sp.getInt("hap_${e.id}_ms", e.defMs)

    fun play(e: HapticEvent) {
        if (!prefs.vibrate) return
        val effect = when (type(e)) {
            "off" -> return
            "custom" -> custom(e)
            else -> if (Build.VERSION.SDK_INT >= 29) VibrationEffect.createPredefined(
                when (type(e)) {
                    "tick" -> VibrationEffect.EFFECT_TICK
                    "heavy" -> VibrationEffect.EFFECT_HEAVY_CLICK
                    "double" -> VibrationEffect.EFFECT_DOUBLE_CLICK
                    else -> VibrationEffect.EFFECT_CLICK
                }
            ) else custom(e)
        }
        runCatching { vib.vibrate(effect) }
    }

    private fun custom(e: HapticEvent): VibrationEffect =
        if (vib.hasAmplitudeControl()) VibrationEffect.createOneShot(ms(e).toLong(), amp(e).coerceIn(1, 255))
        else VibrationEffect.createOneShot(ms(e).toLong(), VibrationEffect.DEFAULT_AMPLITUDE)
}
