package com.kk.shurufa.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kk.shurufa.Prefs
import com.kk.shurufa.ime.HapticEvent
import com.kk.shurufa.ime.Haptics
import com.kk.shurufa.keyboard.KbTheme
import kotlin.math.roundToInt

// 设置 · 外观, 动效, 振动与声音, 手势 and 工具栏.

@Composable
internal fun LookPage(prefs: Prefs) {
    val ctx = LocalContext.current
    Group("主题") {
        Choice(prefs, Prefs.STYLE, "风格", listOf("modern" to "简约", "paper" to "纸墨"), def = "modern")
        Note(if (prefs.style == "paper") "纸墨：宣纸色的底、细微的纸纹，墨色的字，默认宋体、朱砂色。" else "简约：冷灰的底、白色的键，默认黑体。")
        Choice(prefs, Prefs.THEME, "明暗", listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色"))
        var accent by remember { mutableStateOf(prefs.accent) }
        Text("主体色", modifier = Modifier.padding(top = 6.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()).padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            KbTheme.ACCENTS.forEach { (id, v) ->
                val name = v.first
                // the swatches show each color as the keyboard would, in the theme it is in now
                val dark = KbTheme.isDark(ctx, prefs)
                val c = if (id == "system" && android.os.Build.VERSION.SDK_INT < 31) Color.Gray else Color(KbTheme.accentFor(ctx, id, dark))
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { accent = id; prefs.put(Prefs.ACCENT, id) }) {
                    Box(Modifier.size(36.dp).clip(CircleShape).background(c)
                        .then(if (accent == id) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier),
                        contentAlignment = Alignment.Center) {
                        if (accent == id) Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                    Text(name, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        if (accent == "custom") IntSlider(prefs, Prefs.ACCENT_HUE, "色相", 200, 0..359, "°", "拖动挑一种颜色，深浅和其他主体色一致")
    }
    Group("微调") {
        IntSlider(prefs, Prefs.THEME_WARMTH, "色温", 0, -50..50, "", "往左偏冷、往右偏暖，只改底色、按键和文字，不改主体色。纸墨嫌黄就往左拉")
        IntSlider(prefs, Prefs.THEME_SHADE, "底色深浅", 0, -50..50, "", "往左底色变浅，往右变深，按键更分明")
    }
    Group("按键") {
        IntSlider(prefs, Prefs.KEY_HEIGHT, "按键高度", 48, 36..64, "dp")
        FloatSlider(prefs, Prefs.KEY_GAP, "键间距（左右）", 5f, 2f..10f, "dp")
        FloatSlider(prefs, Prefs.ROW_GAP, "行间距（上下）", 8f, 2f..16f, "dp", "比键间距宽一些，每一排键读起来是一条")
        FloatSlider(prefs, Prefs.KEY_RADIUS, "按键圆角", 8f, 0f..18f, "dp")
        IntSlider(prefs, Prefs.BOTTOM_PADDING, "键盘底部留白", 4, 0..24, "dp")
        Toggle(prefs, Prefs.KEY_BORDERS, "实心键的投影", "字母、数字键下方的一道浅影", true)
        Toggle(prefs, Prefs.NUMBER_ROW, "常驻数字行", "字母键盘最上面加一排数字", false)
    }
    Group("光标面板") {
        Choice(prefs, Prefs.CURSOR_LABELS, "右侧按键显示", listOf("icon" to "图标", "both" to "图标加文字", "text" to "文字"), def = "icon")
    }
    Group("文字") {
        FloatSlider(prefs, Prefs.LABEL_SCALE, "按键字号", 1f, 0.8f..1.3f, "×")
        FloatSlider(prefs, Prefs.HINT_SCALE, "角标字号", 1f, 0.7f..1.4f, "×")
        FloatSlider(prefs, Prefs.CAND_SCALE, "候选字号", 1f, 0.8f..1.4f, "×")
        Choice(prefs, Prefs.HINT_MODE, "角标", listOf("all" to "全部", "digits" to "只有数字", "none" to "不显示"), def = "all")
        Toggle(prefs, Prefs.SHOW_ALL_HINTS, "显示四个方向的角标", "下滑、左滑、右滑的字符也画出来（角标选“全部”时）", false)
        FontChoice(prefs)
    }
}

/** 字体: one font for keys, candidates and panels; 自选 takes a .ttf or .otf from the phone. */
@Composable
private fun FontChoice(prefs: Prefs) {
    val ctx = LocalContext.current
    val def = if (prefs.style == "paper") "serif" else "sans"
    LocalPageKeys.current?.add(Prefs.CAND_FONT)
    var v by remember { mutableStateOf(prefs.font) }
    var hasFile by remember { mutableStateOf(com.kk.shurufa.keyboard.Ui.fontFile(ctx).isFile) }
    var error by remember { mutableStateOf<String?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val dest = com.kk.shurufa.keyboard.Ui.fontFile(ctx)
        val tmp = java.io.File(dest.path + ".new")
        val ok = runCatching {
            ctx.contentResolver.openInputStream(uri)!!.use { i -> tmp.outputStream().use { i.copyTo(it) } }
            android.graphics.Typeface.createFromFile(tmp)   // throws on a file that is not a font
            tmp.renameTo(dest)
        }.getOrDefault(false)
        tmp.delete()
        if (ok) { hasFile = true; error = null; v = "custom"; prefs.put(Prefs.CAND_FONT, "custom"); prefs.put(Prefs.FONT_STAMP, System.currentTimeMillis()) }
        else error = "这个文件不是能用的字体（要 .ttf 或 .otf）"
    }
    Column(Modifier.padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("字体")
            ResetMark(v != def) { v = def; prefs.sp.edit().remove(Prefs.CAND_FONT).apply() }
        }
        val options = listOf("sans" to "黑体", "serif" to "宋体", "custom" to "自选")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, (id, name) ->
                SegmentedButton(selected = v == id, shape = SegmentedButtonDefaults.itemShape(i, options.size), onClick = {
                    if (id == "custom" && !hasFile) pick.launch(arrayOf("font/*", "application/x-font-ttf", "application/x-font-otf", "application/octet-stream"))
                    else { v = id; prefs.put(Prefs.CAND_FONT, id) }
                }) { Text(name, maxLines = 1) }
            }
        }
        Text("按键、候选和各个面板用同一种字体。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (v == "custom" || hasFile) TextButton(onClick = { pick.launch(arrayOf("font/*", "application/x-font-ttf", "application/x-font-otf", "application/octet-stream")) }) {
            Text(if (hasFile) "换一个字体文件" else "选择字体文件（.ttf / .otf）")
        }
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
internal fun MotionPage(prefs: Prefs) {
    Group {
        Toggle(prefs, Prefs.ANIMATIONS, "动画", "按键、气泡、弹出选项、面板切换的过渡", true)
        FloatSlider(prefs, Prefs.ANIM_SPEED, "动画时长", 1f, 0.5f..2f, "×", "数值越小越快")
        Toggle(prefs, Prefs.KEY_PREVIEW, "按键气泡", "按下时在按键上方放大显示", true)
    }
}

@Composable
internal fun HapticsPage(prefs: Prefs) {
    val ctx = LocalContext.current
    val haptics = remember { Haptics(ctx, prefs) }
    DisposableEffect(haptics) { onDispose { haptics.release() } }
    Group {
        Toggle(prefs, Prefs.VIBRATE, "振动", "总开关", true)
        Note("每类事件单独选效果。清脆和轻点是手机为自己的马达调好的触感，可以调力度；轻触、点击、重击、双击是系统内置效果；自定义可以调强度和时长。")
    }
    HapticEvent.values().forEach { e ->
        var type by remember { mutableStateOf(haptics.type(e)) }
        var amp by remember { mutableIntStateOf(haptics.amp(e)) }
        var ms by remember { mutableIntStateOf(haptics.ms(e)) }
        Group(e.title) {
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                HapticEvent.TYPES.take(4).forEach { (id, name) ->
                    FilterChip(selected = type == id, onClick = { type = id; prefs.put("hap_${e.id}_type", id); haptics.invalidate(); haptics.play(e) }, label = { Text(name) })
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                HapticEvent.TYPES.drop(4).forEach { (id, name) ->
                    FilterChip(selected = type == id, onClick = { type = id; prefs.put("hap_${e.id}_type", id); haptics.invalidate(); haptics.play(e) }, label = { Text(name) })
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { haptics.play(e) }) { Text("试一下") }
            }
            if (type == "prim_click" || type == "prim_tick") {
                Row { Text("力度", Modifier.weight(1f)); Text("${amp * 100 / 255}%", color = MaterialTheme.colorScheme.primary) }
                ThumbSlider(value = amp.toFloat(), onValueChange = { amp = it.roundToInt() }, onValueChangeFinished = { prefs.put("hap_${e.id}_amp", amp); haptics.invalidate(); haptics.play(e) }, valueRange = 13f..255f)
            }
            if (type == "custom") {
                Row { Text("强度", Modifier.weight(1f)); Text("$amp", color = MaterialTheme.colorScheme.primary) }
                ThumbSlider(value = amp.toFloat(), onValueChange = { amp = it.roundToInt() }, onValueChangeFinished = { prefs.put("hap_${e.id}_amp", amp); haptics.invalidate(); haptics.play(e) }, valueRange = 1f..255f)
                Row { Text("时长", Modifier.weight(1f)); Text("${ms}ms", color = MaterialTheme.colorScheme.primary) }
                ThumbSlider(value = ms.toFloat(), onValueChange = { ms = it.roundToInt() }, onValueChangeFinished = { prefs.put("hap_${e.id}_ms", ms); haptics.invalidate(); haptics.play(e) }, valueRange = 3f..60f)
            }
        }
    }
    Group("声音") {
        Toggle(prefs, Prefs.SOUND, "按键音", null, false)
        FloatSlider(prefs, Prefs.SOUND_VOLUME, "音量", 0.3f, 0.05f..1f, "")
    }
}

@Composable
internal fun GesturesPage(prefs: Prefs) {
    Group("滑动和长按") {
        FloatSlider(prefs, Prefs.FLICK_DP, "滑动判定距离", 22f, 12f..40f, "dp", "手指移动超过这个距离才算滑动")
        IntSlider(prefs, Prefs.LONG_PRESS_MS, "长按时间", 300, 150..700, "ms")
        Toggle(prefs, Prefs.LP_HINTS, "长按包含滑动字符", "角标上的符号排在最前面", true)
        Toggle(prefs, Prefs.LP_CASE, "长按包含大写和小写", null, true)
        Toggle(prefs, Prefs.LP_DIGITS, "第一排长按包含数字", null, true)
        Toggle(prefs, Prefs.DRAG_CANCEL, "拖远了就不上屏", "按下后手指滑出很远，当作误触取消", true)
        IntSlider(prefs, Prefs.HOLD_CANCEL_MS, "按住太久不上屏", 1000, 0..3000, "ms", "没有长按菜单的键和剪贴板、候选：按住超过这么久松开不算点击；0 为关闭")
        Toggle(prefs, Prefs.TOP_ROW_SCRIPTS, "第一排左右滑输入上下角标数字", "左滑 ¹²³，右滑 ₁₂₃", false)
    }
    Group("落点自适应") {
        Toggle(prefs, Prefs.TOUCH_ADAPT, "按我的点按习惯判断按键", "点在两个键中间时，按你平时点这两个键的位置判断。只存在本机", true)
        var reset by remember { mutableStateOf(false) }
        TextButton(enabled = !reset, onClick = {
            prefs.state.edit().remove(com.kk.shurufa.keyboard.TouchModel.KEY).apply(); reset = true
        }) { Text(if (reset) "已清除，下次打开键盘时生效" else "清除学到的落点") }
    }
    Group("空格") {
        Choice(prefs, Prefs.SPACE_SWIPE, "左右滑", listOf("language" to "切换语言", "cursor" to "移动光标", "none" to "无"))
        Choice(prefs, Prefs.SPACE_LONG_PRESS, "长按", listOf("cursor" to "拖动光标", "none" to "无"))
        FloatSlider(prefs, Prefs.CURSOR_STEP_DP, "光标移动一格的距离", 11f, 5f..24f, "dp", "越小越灵敏")
    }
    Group("删除键") {
        Toggle(prefs, Prefs.BACKSPACE_SWIPE, "上滑清空、下滑撤销", "正在打拼音时上滑只清掉拼音", true)
        Toggle(prefs, Prefs.BACKSPACE_RESELECT, "删除后按原拼音推荐", "删掉刚上屏的字后，按原来的拼音重新给出候选", true)
        IntSlider(prefs, Prefs.BACKSPACE_REPEAT, "长按连删的间隔", 55, 25..150, "ms", "匀速连删，数值越小越快")
    }
}

// ---- toolbar -------------------------------------------------------------------------------

@Composable
internal fun ToolbarPage(prefs: Prefs) {
    val all = com.kk.shurufa.keyboard.CandidateBar.ALL_TOOLS
    var order by remember { mutableStateOf(prefs.toolbar) }
    fun save(l: List<String>) { order = l; prefs.put(Prefs.TOOLBAR_ITEMS, l.joinToString(",")) }
    Group("已显示（最多 6 个，快捷面板固定在最右）") {
        order.forEachIndexed { i, id ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(all.firstOrNull { it.first == id }?.second ?: id, Modifier.weight(1f))
                TextButton(enabled = i > 0, onClick = { save(order.toMutableList().apply { add(i - 1, removeAt(i)) }) }) { Text("上移") }
                TextButton(enabled = i < order.size - 1, onClick = { save(order.toMutableList().apply { add(i + 1, removeAt(i)) }) }) { Text("下移") }
                TextButton(enabled = order.size > 1, onClick = { save(order - id) }) { Text("移除") }
            }
        }
    }
    Group("可添加") {
        val rest = all.filter { it.first !in order }
        if (rest.isEmpty()) Note("全部都已添加。")
        rest.forEach { (id, name) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(name, Modifier.weight(1f))
                TextButton(enabled = order.size < 6, onClick = { save(order + id) }) { Text("添加") }
            }
        }
        Note("顺序就是从左到右的位置。打开某个面板时，它的按钮会变成主题色。")
    }
}
