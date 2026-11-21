package com.kk.shurufa.settings

import android.content.SharedPreferences
import android.widget.LinearLayout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.kk.shurufa.Prefs
import com.kk.shurufa.keyboard.CandidateBar
import com.kk.shurufa.keyboard.KbTheme
import com.kk.shurufa.keyboard.Key
import com.kk.shurufa.keyboard.KeyboardListener
import com.kk.shurufa.keyboard.KeyboardView
import com.kk.shurufa.keyboard.LayoutRepo
import com.kk.shurufa.keyboard.Output

/**
 * 实时预览: the real toolbar and letter keys, drawn by the keyboard's own views, pinned above the
 * 外观 settings and redrawn on every change — style, light, accent, sizes, gaps, hints. Keys can be
 * pressed to feel the press; nothing is typed anywhere.
 */
@Composable
internal fun KeyboardPreview(prefs: Prefs) {
    val ctx = LocalContext.current
    val holder = remember { arrayOfNulls<Pair<CandidateBar, KeyboardView>>(1) }
    fun refresh() {
        val (bar, kb) = holder[0] ?: return
        val t = KbTheme.resolve(ctx, prefs)
        bar.theme = t; kb.theme = t
        (bar.parent as? android.view.View)?.setBackgroundColor(t.background)
        kb.setRows(LayoutRepo(ctx).keyboard("zh").rows)
        kb.requestLayout(); bar.requestLayout()
    }
    DisposableEffect(Unit) {
        val l = SharedPreferences.OnSharedPreferenceChangeListener { _, k -> if (k == null || Prefs.rebuildsKeyboard(k) || k == Prefs.SOLAR_TERMS) refresh() }
        prefs.sp.registerOnSharedPreferenceChangeListener(l)
        onDispose { prefs.sp.unregisterOnSharedPreferenceChangeListener(l) }
    }
    AndroidView(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clip(RoundedCornerShape(20.dp)),
        factory = { c ->
            val bar = CandidateBar(c, prefs).apply { showToolbar() }
            val kb = KeyboardView(c, prefs).apply { listener = Quiet }
            holder[0] = bar to kb
            LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                addView(bar, LinearLayout.LayoutParams(-1, -2))
                addView(kb, LinearLayout.LayoutParams(-1, -2))
            }.also { refresh() }
        },
    )
}

/** The preview's keys answer presses and nothing else. */
private object Quiet : KeyboardListener {
    override fun onOutput(key: Key, output: Output) {}
    override fun onFunction(key: Key) {}
    override fun longOptions(key: Key): List<Output> = emptyList()
    override fun onOption(key: Key, option: Output) {}
    override fun onCursorMove(dx: Int, dy: Int) {}
    override fun onCursorMode(active: Boolean) {}
    override fun onSwitchLanguage(direction: Int): String? = null
    override fun onBackspaceRepeat() {}
    override fun onBackspaceGesture(action: String) {}
    override fun onPress(key: Key) {}
    override fun shiftState() = 0
    override fun spaceLabel() = "中文"
}
