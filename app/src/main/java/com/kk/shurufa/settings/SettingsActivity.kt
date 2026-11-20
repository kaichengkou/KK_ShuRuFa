package com.kk.shurufa.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.kk.shurufa.BuildConfig
import com.kk.shurufa.Prefs
import com.kk.shurufa.keyboard.LayoutRepo
import com.kk.shurufa.rime.RimeEngine
import kotlin.math.roundToInt

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val prefs = Prefs(this)
        RimeEngine.start(this)
        setContent {
            val dark = isSystemInDarkTheme()
            val scheme = if (dark) darkColorScheme(primary = Color(0xFF5BC3CB), onPrimary = Color(0xFF0A1A1C))
            else lightColorScheme(primary = Color(0xFF0E6A73))
            MaterialTheme(colorScheme = scheme) { SettingsScreen(prefs) }
        }
    }
}

private val LANG_NAMES = mapOf("zh" to "中文（雾凇拼音）", "ru" to "Русский", "en" to "English")

@Composable
private fun SettingsScreen(prefs: Prefs) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    // re-check the input method status whenever the screen comes back
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    LazyColumn(
        Modifier.fillMaxSize().systemBarsPadding().imePadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("KK输入法", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("版本 ${BuildConfig.VERSION_NAME}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item { SetupCard(tick) }
        item { RimeCard() }

        item {
            Section("外观") {
                Choice(prefs, Prefs.THEME, "主题", listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色"))
                IntSlider(prefs, Prefs.KEY_HEIGHT, "按键高度", 46, 36..64, "dp")
                FloatSlider(prefs, Prefs.KEY_GAP, "按键间距", 5f, 2f..10f, "dp")
                FloatSlider(prefs, Prefs.KEY_RADIUS, "按键圆角", 7f, 0f..16f, "dp")
                FloatSlider(prefs, Prefs.LABEL_SCALE, "按键字号", 1f, 0.8f..1.3f, "×")
                FloatSlider(prefs, Prefs.HINT_SCALE, "角标字号", 1f, 0.7f..1.4f, "×")
                FloatSlider(prefs, Prefs.CAND_SCALE, "候选字号", 1f, 0.8f..1.4f, "×")
                IntSlider(prefs, Prefs.BOTTOM_PADDING, "键盘底部留白", 4, 0..24, "dp")
                Toggle(prefs, Prefs.SHOW_HINTS, "显示角标", "按键右上角的滑动字符", true)
                Toggle(prefs, Prefs.SHOW_ALL_HINTS, "显示四个方向的角标", "下滑、左滑、右滑的字符也画出来", false)
                Toggle(prefs, Prefs.KEY_BORDERS, "按键底边阴影", null, true)
                Toggle(prefs, Prefs.NUMBER_ROW, "常驻数字行", "字母键盘最上面加一排数字", false)
            }
        }
        item {
            Section("动效") {
                Toggle(prefs, Prefs.ANIMATIONS, "动画", "按键、气泡、弹出选项、面板切换的过渡动画", true)
                FloatSlider(prefs, Prefs.ANIM_SPEED, "动画时长", 1f, 0.5f..2f, "×", "数值越小越快")
                Toggle(prefs, Prefs.KEY_PREVIEW, "按键气泡", "按下时在按键上方放大显示", true)
            }
        }
        item {
            Section("振动与声音") {
                Toggle(prefs, Prefs.VIBRATE, "按键振动", null, true)
                IntSlider(prefs, Prefs.VIBRATE_STRENGTH, "振动强度", 60, 1..255, "")
                IntSlider(prefs, Prefs.VIBRATE_MS, "振动时长", 14, 4..40, "ms")
                Toggle(prefs, Prefs.SOUND, "按键音", null, false)
                FloatSlider(prefs, Prefs.SOUND_VOLUME, "按键音量", 0.3f, 0.05f..1f, "")
            }
        }
        item {
            Section("手势") {
                FloatSlider(prefs, Prefs.FLICK_DP, "滑动判定距离", 22f, 12f..40f, "dp", "手指移动超过这个距离才算滑动")
                IntSlider(prefs, Prefs.LONG_PRESS_MS, "长按时间", 300, 150..700, "ms")
                Choice(prefs, Prefs.SPACE_SWIPE, "空格左右滑", listOf("language" to "切换语言", "cursor" to "移动光标", "none" to "无"))
                Choice(prefs, Prefs.SPACE_LONG_PRESS, "长按空格", listOf("cursor" to "拖动光标", "none" to "无"))
                FloatSlider(prefs, Prefs.CURSOR_STEP_DP, "光标移动一格的距离", 11f, 5f..24f, "dp", "越小越灵敏")
                Toggle(prefs, Prefs.BACKSPACE_SWIPE, "删除键左滑删词", "往左滑选中前面的词，松手删除", true)
                IntSlider(prefs, Prefs.BACKSPACE_REPEAT, "长按删除的速度", 55, 25..120, "ms", "两次删除之间的间隔，越小越快")
            }
        }
        item {
            Section("长按选项") {
                Text("长按按键弹出选项，拖动手指选择，松手上屏。", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                Toggle(prefs, Prefs.LP_HINTS, "包含滑动字符", "角标上的符号排在最前面", true)
                Toggle(prefs, Prefs.LP_CASE, "包含大写和小写", null, true)
                Toggle(prefs, Prefs.LP_DIGITS, "第一排包含数字", null, true)
            }
        }
        item { Section("语言") { LanguageList(prefs)
            Toggle(prefs, Prefs.PER_APP_LANG, "按应用记住语言", "比如 Telegram 记住俄语，微信记住中文", true)
            Toggle(prefs, Prefs.AUTO_CAP, "句首自动大写", "俄语和英语", true)
            Toggle(prefs, Prefs.DOUBLE_SPACE, "双击空格输入句号", "俄语和英语", true)
        } }
        item {
            Section("中文") {
                Toggle(prefs, Prefs.ZH_TRAD, "输出繁体", null, false)
                Toggle(prefs, Prefs.ZH_EMOJI, "候选里带 emoji", null, true)
                Toggle(prefs, Prefs.ZH_PREEDIT_INLINE, "拼音显示在输入框里", "关闭时拼音只显示在候选栏上方", false)
            }
        }
        item {
            Section("符号") {
                Toggle(prefs, Prefs.AUTO_PAIR, "括号和引号自动配对", "输入（ 时自动补上 ），光标停在中间", true)
                Toggle(prefs, Prefs.SYMBOL_RETURN, "输入符号后回到字母键盘", "符号面板右下角的锁可以临时关掉", true)
                Toggle(prefs, Prefs.SMART_PUNCT, "数字后的标点保持半角", "3.14 不会变成 3。14", true)
            }
        }
        item {
            Section("数学键盘") {
                Choice(prefs, Prefs.MATH_FORMAT, "默认输出格式", listOf("uni" to "符号", "tex" to "LaTeX"))
                Toggle(prefs, Prefs.MATH_CALC, "按 = 后给出计算结果", null, true)
                Toggle(prefs, Prefs.NUMBER_FIELD_MATH, "数字输入框弹出数学键盘", null, true)
            }
        }
        item { LayoutFilesCard() }
        item { Spacer(Modifier.padding(24.dp)) }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
private fun Toggle(prefs: Prefs, key: String, title: String, sub: String?, def: Boolean) {
    var v by remember { mutableStateOf(prefs.sp.getBoolean(key, def)) }
    Row(Modifier.fillMaxWidth().clickable { v = !v; prefs.put(key, v) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = v, onCheckedChange = { v = it; prefs.put(key, it) })
    }
}

@Composable
private fun IntSlider(prefs: Prefs, key: String, title: String, def: Int, range: IntRange, unit: String, sub: String? = null) {
    var v by remember { mutableIntStateOf(prefs.sp.getInt(key, def)) }
    Column {
        Row { Text(title, Modifier.weight(1f)); Text("$v$unit", color = MaterialTheme.colorScheme.primary) }
        if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(value = v.toFloat(), onValueChange = { v = it.roundToInt() }, onValueChangeFinished = { prefs.put(key, v) },
            valueRange = range.first.toFloat()..range.last.toFloat())
    }
}

@Composable
private fun FloatSlider(prefs: Prefs, key: String, title: String, def: Float, range: ClosedFloatingPointRange<Float>, unit: String, sub: String? = null) {
    var v by remember { mutableStateOf(prefs.sp.getFloat(key, def)) }
    Column {
        Row { Text(title, Modifier.weight(1f)); Text("${"%.2f".format(java.util.Locale.ROOT, v).trimEnd('0').trimEnd('.')}$unit", color = MaterialTheme.colorScheme.primary) }
        if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(value = v, onValueChange = { v = it }, onValueChangeFinished = { prefs.put(key, v) }, valueRange = range)
    }
}

@Composable
private fun Choice(prefs: Prefs, key: String, title: String, options: List<Pair<String, String>>) {
    var v by remember { mutableStateOf(prefs.sp.getString(key, options.first().first) ?: options.first().first) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, (id, name) ->
                SegmentedButton(selected = v == id, onClick = { v = id; prefs.put(key, id) },
                    shape = SegmentedButtonDefaults.itemShape(i, options.size)) { Text(name) }
            }
        }
    }
}

@Composable
private fun LanguageList(prefs: Prefs) {
    var order by remember {
        val enabled = prefs.languages
        mutableStateOf(enabled + LANG_NAMES.keys.filter { it !in enabled })
    }
    var enabled by remember { mutableStateOf(prefs.languages.toSet()) }
    fun save() = prefs.put(Prefs.LANGUAGES, order.filter { it in enabled }.joinToString(","))
    Text("勾选要用的语言，顺序就是空格左右滑切换的顺序。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    order.forEachIndexed { i, id ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = id in enabled, onCheckedChange = {
                val next = if (it) enabled + id else enabled - id
                if (next.isNotEmpty()) { enabled = next; save() }
            })
            Text(LANG_NAMES[id] ?: id, Modifier.weight(1f))
            IconButton(onClick = { if (i > 0) { order = order.toMutableList().apply { add(i - 1, removeAt(i)) }; save() } }) { Icon(Icons.Filled.KeyboardArrowUp, "上移") }
            IconButton(onClick = { if (i < order.size - 1) { order = order.toMutableList().apply { add(i + 1, removeAt(i)) }; save() } }) { Icon(Icons.Filled.KeyboardArrowDown, "下移") }
        }
    }
}

@Composable
private fun SetupCard(tick: Int) {
    val ctx = LocalContext.current
    val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    val enabled = remember(tick) { imm.enabledInputMethodList.any { it.packageName == ctx.packageName } }
    val current = remember(tick) { Settings.Secure.getString(ctx.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.startsWith(ctx.packageName) == true }
    var test by remember { mutableStateOf("") }
    Section("开始使用") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (enabled) "① 已在系统里启用" else "① 在系统设置里启用 KK输入法", Modifier.weight(1f))
            if (!enabled) Button(onClick = { ctx.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }) { Text("去启用") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (current) "② 已是当前输入法" else "② 切换为当前输入法", Modifier.weight(1f))
            if (!current) Button(onClick = { imm.showInputMethodPicker() }, enabled = enabled) { Text("切换") }
        }
        OutlinedTextField(value = test, onValueChange = { test = it }, modifier = Modifier.fillMaxWidth(), label = { Text("在这里试打字") }, minLines = 2)
    }
}

@Composable
private fun RimeCard() {
    val ctx = LocalContext.current
    var state by remember { mutableStateOf(RimeEngine.state) }
    DisposableEffect(Unit) {
        val l: (RimeEngine.State) -> Unit = { state = it }
        RimeEngine.addListener(l)
        onDispose { RimeEngine.removeListener(l) }
    }
    Section("中文词库") {
        val text = when (state) {
            RimeEngine.State.READY -> "雾凇拼音已就绪"
            RimeEngine.State.DEPLOYING -> "正在部署词库（首次约需一分钟）…"
            RimeEngine.State.PREPARING -> "正在复制词库文件…"
            RimeEngine.State.FAILED -> "启动失败：${RimeEngine.error ?: "未知错误"}"
            RimeEngine.State.IDLE -> "未启动"
        }
        Text(text)
        Text("词库目录：${RimeEngine.userDir(ctx).absolutePath}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FilledTonalButton(onClick = { RimeEngine.redeploy(ctx) }, enabled = state == RimeEngine.State.READY || state == RimeEngine.State.FAILED) { Text("重新部署") }
    }
}

@Composable
private fun LayoutFilesCard() {
    val ctx = LocalContext.current
    var path by remember { mutableStateOf<String?>(null) }
    Section("自定义布局") {
        Text("所有键盘布局都是 YAML 文件。导出后可以用任意文本编辑器修改键位、滑动字符、长按选项、符号分类和数学键盘；改完点“重新加载”。删掉某个文件就恢复默认。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { path = LayoutRepo(ctx).exportDefaults().absolutePath }) { Text("导出布局文件") }
            FilledTonalButton(onClick = { Prefs(ctx).put("layout_reload", System.currentTimeMillis().toString()) }) { Text("重新加载") }
        }
        path?.let { HorizontalDivider(); Text("已导出到：$it", style = MaterialTheme.typography.bodySmall) }
    }
}
