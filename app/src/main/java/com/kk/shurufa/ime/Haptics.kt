package com.kk.shurufa.ime

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.kk.shurufa.Prefs
import java.util.concurrent.ConcurrentHashMap

/** Every moment the keyboard can vibrate for; each one has its own effect, strength and length. */
enum class HapticEvent(val id: String, val title: String, val defType: String, val defAmp: Int, val defMs: Int) {
    // The keys use the phone's own tuned click (a haptic primitive) at a strength [defAmp]/255:
    // crisp where a short buzz is soft and dull, and never a full-strength jolt on motors without
    // amplitude control. Backspace a little firmer than letters, a held one a light tick per step.
    LETTER("letter", "字母和字符键", "prim_click", 140, 14),
    FUNCTION("function", "功能键", "prim_click", 155, 16),
    SPACE("space", "空格", "prim_click", 140, 16),
    BACKSPACE("backspace", "删除", "prim_click", 180, 18),
    REPEAT("repeat", "长按连续删除（每删一个字）", "prim_tick", 100, 7),
    ENTER("enter", "回车", "click", 90, 20),
    POPUP("popup", "长按弹出选项", "prim_click", 215, 24),
    OPTION("option", "在选项间滑动", "tick", 40, 8),
    CURSOR("cursor", "拖动光标", "tick", 35, 6),
    GESTURE("gesture", "手势生效（切换语言、清空、撤销）", "double", 120, 20);

    companion object {
        val TYPES = listOf("off" to "关", "prim_click" to "清脆", "prim_tick" to "轻点", "tick" to "轻触", "click" to "点击", "heavy" to "重击", "double" to "双击", "custom" to "自定义")
    }
}

/**
 * Vibration and key sounds. Both are binder calls that can take milliseconds on some phones, so
 * they run on a thread of their own and a slide that ticks on every step never waits for them.
 */
class Haptics(context: Context, private val prefs: Prefs) {
    private val vib: Vibrator = if (Build.VERSION.SDK_INT >= 31)
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
    else @Suppress("DEPRECATION") context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun type(e: HapticEvent) = prefs.sp.getString("hap_${e.id}_type", e.defType) ?: e.defType
    fun amp(e: HapticEvent) = prefs.sp.getInt("hap_${e.id}_amp", e.defAmp)
    fun ms(e: HapticEvent) = prefs.sp.getInt("hap_${e.id}_ms", e.defMs)

    private val thread = HandlerThread("kk-feedback").apply { start() }
    private val worker = Handler(thread.looper)
    private val effects = ConcurrentHashMap<HapticEvent, Any>()
    private val none = Any()

    /** Settings changed: build the effects again on next use. */
    fun invalidate() = effects.clear()

    fun play(e: HapticEvent) {
        if (!prefs.vibrate) return
        worker.post {
            val effect = effects.getOrPut(e) { build(e) ?: none }
            if (effect is VibrationEffect) runCatching { vib.vibrate(effect) }
        }
    }

    /** The key sound for a key with function [fn] (null for characters), when 按键音 is on. */
    fun click(fn: String?) {
        if (!prefs.sound) return
        val fx = when (fn) {
            "backspace" -> AudioManager.FX_KEYPRESS_DELETE
            "space" -> AudioManager.FX_KEYPRESS_SPACEBAR
            "enter" -> AudioManager.FX_KEYPRESS_RETURN
            else -> AudioManager.FX_KEYPRESS_STANDARD
        }
        val vol = prefs.soundVolume
        worker.post { audio.playSoundEffect(fx, vol) }
    }

    /** The keyboard is going away: its thread with it. */
    fun release() = thread.quitSafely()

    private fun build(e: HapticEvent): VibrationEffect? = when (type(e)) {
        "off" -> null
        "custom" -> custom(e)
        "prim_click", "prim_tick" -> primitive(e, if (type(e) == "prim_tick") 2 else 1)
        else -> if (Build.VERSION.SDK_INT >= 29) VibrationEffect.createPredefined(
            when (type(e)) {
                "tick" -> VibrationEffect.EFFECT_TICK
                "heavy" -> VibrationEffect.EFFECT_HEAVY_CLICK
                "double" -> VibrationEffect.EFFECT_DOUBLE_CLICK
                else -> VibrationEffect.EFFECT_CLICK
            }
        ) else custom(e)
    }

    /**
     * A haptic primitive (Android 11+, where the phone supports it) at the event's strength;
     * otherwise the predefined tick or click, and on old phones the custom pulse.
     */
    private fun primitive(e: HapticEvent, kind: Int): VibrationEffect {
        val prim = if (kind == 2) VibrationEffect.Composition.PRIMITIVE_TICK else VibrationEffect.Composition.PRIMITIVE_CLICK
        if (Build.VERSION.SDK_INT >= 30 && vib.areAllPrimitivesSupported(prim)) {
            return VibrationEffect.startComposition().addPrimitive(prim, (amp(e) / 255f).coerceIn(0.05f, 1f)).compose()
        }
        if (Build.VERSION.SDK_INT >= 29) return VibrationEffect.createPredefined(if (kind == 2) VibrationEffect.EFFECT_TICK else VibrationEffect.EFFECT_CLICK)
        return custom(e)
    }

    private fun custom(e: HapticEvent): VibrationEffect =
        if (vib.hasAmplitudeControl()) VibrationEffect.createOneShot(ms(e).toLong(), amp(e).coerceIn(1, 255))
        else VibrationEffect.createOneShot(ms(e).toLong(), VibrationEffect.DEFAULT_AMPLITUDE)
}
