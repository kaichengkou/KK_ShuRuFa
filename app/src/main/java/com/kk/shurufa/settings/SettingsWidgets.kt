package com.kk.shurufa.settings

import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kk.shurufa.Prefs
import kotlin.math.roundToInt

// What every settings page is built from: the page frame (with 恢复初始设定), groups, switches, sliders, choices and pickers.

/** A page with unsaved work holds 返回: [ask] asks first, [leave] goes back after. */
internal object LeaveGuard {
    var ask: (() -> Unit)? = null
    var leave: () -> Unit = {}
}

/** The settings a page's controls show, gathered as it is drawn, for its 恢复初始设定. */
internal val LocalPageKeys = androidx.compose.runtime.staticCompositionLocalOf<MutableSet<String>?> { null }

/** Pages whose contents are the user's own data, not settings: nothing there to restore. */
private val NO_RESET = setOf("dict", "stats", "files", "dict_edit", "backup", "about")

/** Settings a page changes without the shared controls (and so are not gathered). */
private fun extraKeys(page: String, prefs: Prefs): List<String> = when (page) {
    "haptics" -> prefs.sp.all.keys.filter { it.startsWith("hap_") }
    "look" -> listOf(Prefs.ACCENT)
    "keys" -> prefs.sp.all.keys.filter { it.startsWith("key_ovr_") }
    "toolbar" -> listOf(Prefs.TOOLBAR_ITEMS)
    else -> emptyList()
}

@Composable
internal fun SubPage(title: String, onBack: () -> Unit, page: String = "", prefs: Prefs? = null, header: (@Composable () -> Unit)? = null, content: @Composable () -> Unit) {
    val keys = remember(page) { mutableSetOf<String>() }
    // a new generation of the page after 恢复初始设定: every control reads its default again
    var generation by remember { mutableIntStateOf(0) }
    var confirm by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        // a header stays put while the page scrolls under it (外观's live keyboard)
        header?.invoke()
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                androidx.compose.runtime.CompositionLocalProvider(LocalPageKeys provides keys) {
                    key(generation) { content() }
                }
            }
            if (prefs != null && page.isNotEmpty() && page !in NO_RESET) item {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    TextButton(onClick = { confirm = true }) { Text("恢复这一页的初始设定", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
    if (confirm && prefs != null) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("恢复「$title」的初始设定？") },
        text = { Text("这一页的设置都回到安装时的样子；其他页面不受影响。") },
        confirmButton = {
            TextButton(onClick = {
                val all = keys + extraKeys(page, prefs)
                // removing a setting tells the keyboard's listeners, which rebuild or reapply as for a change
                prefs.sp.edit().apply { all.forEach { remove(it) } }.apply()
                confirm = false; generation++
            }) { Text("恢复") }
        },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("取消") } },
    )
}

@Composable
internal fun Group(title: String? = null, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        if (title != null) Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) { content() }
    }
}

@Composable
internal fun Note(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 4.dp))

@Composable
internal fun Toggle(prefs: Prefs, key: String, title: String, sub: String?, def: Boolean) {
    LocalPageKeys.current?.add(key)
    var v by remember { mutableStateOf(prefs.sp.getBoolean(key, def)) }
    Row(Modifier.fillMaxWidth().clickable { v = !v; prefs.put(key, v) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title)
                ResetMark(v != def) { v = def; prefs.sp.edit().remove(key).apply() }
            }
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = v, onCheckedChange = { v = it; prefs.put(key, it) })
    }
}

/** Tidy steps for a range: whole numbers people would pick (5, 10, 50, 100 ms …). */
private fun intStep(span: Int) = when {
    span <= 30 -> 1; span <= 100 -> 5; span <= 300 -> 10; span <= 1500 -> 50; span <= 5000 -> 100; else -> 500
}
private fun floatStep(span: Float) = when { span <= 2f -> 0.05f; span <= 10f -> 0.1f; else -> 1f }
private fun fmt(v: Float) = "%.2f".format(java.util.Locale.ROOT, v).trimEnd('0').trimEnd('.')

/** The value at the right of a slider: tap it to type an exact number. */
@Composable
private fun ValueLabel(text: String, title: String, unit: String, onEnter: (String) -> Boolean) {
    var editing by remember { mutableStateOf(false) }
    Text(text, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium,
        modifier = Modifier.clickable { editing = true }.padding(start = 8.dp))
    if (editing) {
        var input by remember { mutableStateOf(text.removeSuffix(unit)) }
        var bad by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(title) },
            text = {
                OutlinedTextField(input, { input = it; bad = false }, singleLine = true, isError = bad,
                    suffix = { Text(unit) }, supportingText = { if (bad) Text("请输入范围内的数字") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal))
            },
            confirmButton = { TextButton(onClick = { if (onEnter(input.trim())) editing = false else bad = true }) { Text("确定") } },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("取消") } },
        )
    }
}

@Composable
internal fun IntSlider(prefs: Prefs, key: String, title: String, def: Int, range: IntRange, unit: String, sub: String? = null, onDone: (() -> Unit)? = null) {
    LocalPageKeys.current?.add(key)
    var v by remember { mutableIntStateOf(prefs.sp.getInt(key, def)) }
    val step = intStep(range.last - range.first)
    Column(Modifier.padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title)
            ResetMark(v != def) { v = def; prefs.sp.edit().remove(key).apply(); onDone?.invoke() }
            Spacer(Modifier.weight(1f))
            ValueLabel("$v$unit", title, unit) { t ->
                val n = t.toIntOrNull() ?: return@ValueLabel false
                if (n !in range) return@ValueLabel false
                v = n; prefs.put(key, n); onDone?.invoke(); true
            }
        }
        if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // the value snaps to tidy steps by itself; the track stays clean, without tick marks
        ThumbSlider(value = v.toFloat(), onValueChange = { v = (range.first + ((it - range.first) / step).roundToInt() * step).coerceIn(range) },
            onValueChangeFinished = { prefs.put(key, v); onDone?.invoke() },
            valueRange = range.first.toFloat()..range.last.toFloat())
    }
}

@Composable
internal fun FloatSlider(prefs: Prefs, key: String, title: String, def: Float, range: ClosedFloatingPointRange<Float>, unit: String, sub: String? = null) {
    LocalPageKeys.current?.add(key)
    var v by remember { mutableStateOf(prefs.sp.getFloat(key, def)) }
    val step = floatStep(range.endInclusive - range.start)
    Column(Modifier.padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title)
            ResetMark(v != def) { v = def; prefs.sp.edit().remove(key).apply() }
            Spacer(Modifier.weight(1f))
            ValueLabel("${fmt(v)}$unit", title, unit) { t ->
                val n = t.toFloatOrNull() ?: return@ValueLabel false
                if (n < range.start || n > range.endInclusive) return@ValueLabel false
                v = n; prefs.put(key, n); true
            }
        }
        if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ThumbSlider(value = v, onValueChange = { v = (range.start + ((it - range.start) / step).roundToInt() * step).coerceIn(range.start, range.endInclusive) },
            onValueChangeFinished = { prefs.put(key, v) }, valueRange = range)
    }
}

/**
 * A slider that moves only when its knob is dragged: a tap on the track, or a finger that lands on
 * it while scrolling the page, changes nothing. The knob is easy to catch (a 48 dp wide grip).
 */
@Composable
internal fun ThumbSlider(value: Float, onValueChange: (Float) -> Unit, valueRange: ClosedFloatingPointRange<Float>, onValueChangeFinished: (() -> Unit)? = null) {
    val active = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.secondaryContainer
    val cur by androidx.compose.runtime.rememberUpdatedState(value)
    val change by androidx.compose.runtime.rememberUpdatedState(onValueChange)
    val done by androidx.compose.runtime.rememberUpdatedState(onValueChangeFinished)
    var held by remember { mutableStateOf(false) }
    val span = (valueRange.endInclusive - valueRange.start).takeIf { it > 0f } ?: 1f
    androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(40.dp).pointerInput(valueRange) {
        val pad = 12.dp.toPx()
        val grip = 24.dp.toPx()
        fun xOf(v: Float) = pad + ((v - valueRange.start) / span).coerceIn(0f, 1f) * (size.width - 2 * pad)
        var grab = 0f
        detectHorizontalDragGestures(
            onDragStart = { o -> held = kotlin.math.abs(o.x - xOf(cur)) <= grip; grab = o.x - xOf(cur) },
            onDragEnd = { if (held) { held = false; done?.invoke() } },
            onDragCancel = { if (held) { held = false; done?.invoke() } },
        ) { ch, _ ->
            if (!held) return@detectHorizontalDragGestures
            ch.consume()
            val f = ((ch.position.x - grab - pad) / (size.width - 2 * pad)).coerceIn(0f, 1f)
            change(valueRange.start + f * span)
        }
    }) {
        val pad = 12.dp.toPx()
        val y = size.height / 2
        val x = pad + ((value - valueRange.start) / span).coerceIn(0f, 1f) * (size.width - 2 * pad)
        val h = 4.dp.toPx()
        drawLine(track, androidx.compose.ui.geometry.Offset(pad, y), androidx.compose.ui.geometry.Offset(size.width - pad, y), h, androidx.compose.ui.graphics.StrokeCap.Round)
        drawLine(active, androidx.compose.ui.geometry.Offset(pad, y), androidx.compose.ui.geometry.Offset(x, y), h, androidx.compose.ui.graphics.StrokeCap.Round)
        if (held) drawCircle(active.copy(alpha = 0.16f), 20.dp.toPx(), androidx.compose.ui.geometry.Offset(x, y))
        drawCircle(active, (if (held) 11 else 9).dp.toPx(), androidx.compose.ui.geometry.Offset(x, y))
    }
}

/** 复原, after a setting's name: shown only while it differs from where it started. */
@Composable
internal fun ResetMark(changed: Boolean, onReset: () -> Unit) {
    if (!changed) return
    Icon(Icons.Outlined.Restore, "恢复默认", tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp).clip(CircleShape).clickable { onReset() }.padding(6.dp).size(16.dp))
}

@Composable
private fun cleanSlider() = androidx.compose.material3.SliderDefaults.colors(activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent)

@Composable
internal fun Choice(prefs: Prefs, key: String, title: String, options: List<Pair<String, String>>, def: String = options.first().first) {
    LocalPageKeys.current?.add(key)
    var v by remember { mutableStateOf(prefs.sp.getString(key, def) ?: def) }
    Column(Modifier.padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title)
            ResetMark(v != def) { v = def; prefs.sp.edit().remove(key).apply() }
        }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, (id, name) ->
                SegmentedButton(selected = v == id, onClick = { v = id; prefs.put(key, id) },
                    shape = SegmentedButtonDefaults.itemShape(i, options.size)) { Text(name, maxLines = 1) }
            }
        }
    }
}

/**
 * A list to pick from in a dialog, with a search box when it is long. [checked] makes it a
 * multi-select (选中启用); otherwise tapping a row picks it and closes.
 */
@Composable
internal fun PickerDialog(title: String, items: List<Pair<String, String>>, selected: String?, checked: Set<String>? = null,
                         onPick: (String) -> Unit, onToggle: ((String, Boolean) -> Unit)? = null, onClose: () -> Unit) {
    var q by remember { mutableStateOf("") }
    val shown = items.filter { q.isBlank() || it.second.contains(q, ignoreCase = true) || it.first.contains(q, ignoreCase = true) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(title) },
        text = {
            Column {
                if (items.size > 8) OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth().padding(bottom = 6.dp), singleLine = true,
                    placeholder = { Text("搜索模型") })
                if (shown.isEmpty()) Text("没有匹配的项", color = MaterialTheme.colorScheme.onSurfaceVariant)
                androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(shown.size) { i ->
                        val (id, label) = shown[i]
                        Row(Modifier.fillMaxWidth().clickable {
                            if (checked != null) onToggle?.invoke(id, id !in checked) else { onPick(id); onClose() }
                        }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (checked != null) androidx.compose.material3.Checkbox(checked = id in checked, onCheckedChange = { onToggle?.invoke(id, it) })
                            else RadioButton(selected = id == selected, onClick = { onPick(id); onClose() })
                            Text(label, Modifier.padding(start = 4.dp))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(if (checked != null) "完成" else "关闭") } },
    )
}

/** A setting row whose value opens a [PickerDialog]. */
@Composable
internal fun PickRow(title: String, value: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f))
        Text("$value ▾", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
    }
}
