package com.kk.shurufa.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.kk.shurufa.BuildConfig
import com.kk.shurufa.Prefs
import com.kk.shurufa.ime.HapticEvent
import com.kk.shurufa.ime.Haptics
import com.kk.shurufa.keyboard.KbTheme
import com.kk.shurufa.keyboard.Key
import com.kk.shurufa.keyboard.KeyOverride
import com.kk.shurufa.keyboard.LayoutRepo
import com.kk.shurufa.keyboard.Dir
import com.kk.shurufa.rime.RimeEngine
import kotlin.math.roundToInt

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val prefs = Prefs(this)
        RimeEngine.start(this)
        setContent {
            var accentTick by remember { mutableIntStateOf(0) }
            DisposableEffect(Unit) {
                val l = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, k -> if (k == Prefs.ACCENT || k == Prefs.THEME) accentTick++ }
                prefs.sp.registerOnSharedPreferenceChangeListener(l)
                onDispose { prefs.sp.unregisterOnSharedPreferenceChangeListener(l) }
            }
            val dark = when (prefs.theme) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
            val kb = remember(accentTick, dark) { KbTheme.resolve(this, prefs) }
            val accent = Color(kb.accent)
            val scheme = if (dark) darkColorScheme(primary = accent, onPrimary = Color(kb.onAccent), background = Color(0xFF111316), surface = Color(0xFF111316),
                surfaceContainer = Color(0xFF1C1F23), surfaceContainerHigh = Color(0xFF24282D))
            else lightColorScheme(primary = accent, onPrimary = Color(kb.onAccent), background = Color(0xFFF3F4F7), surface = Color(0xFFF3F4F7),
                surfaceContainer = Color.White, surfaceContainerHigh = Color(0xFFE9ECF0))
            MaterialTheme(colorScheme = scheme) {
                Surface(color = MaterialTheme.colorScheme.background) { SettingsApp(prefs) }
            }
        }
    }
}

private data class PageInfo(val id: String, val title: String, val summary: String)

private val PAGES = listOf(
    PageInfo("look", "外观", "主题、主体色、按键尺寸与字号"),
    PageInfo("motion", "动效", "动画开关与时长、按键气泡"),
    PageInfo("haptics", "振动与声音", "每类按键的振动效果都可以单独搭配"),
    PageInfo("gestures", "手势", "滑动、长按、空格与删除键"),
    PageInfo("keys", "键位编辑", "改任意键的四向滑动字符和长按选项"),
    PageInfo("languages", "语言", "启用的语言、顺序、自动大写、俄语重音"),
    PageInfo("chinese", "中文", "简繁、emoji、联想、纠错、拼音模式"),
    PageInfo("dict", "自定义词库", "添加、修改、删除自己的词"),
    PageInfo("symbols", "符号与数学", "括号配对、数字后标点、数学键盘"),
    PageInfo("clipboard", "剪贴板", "保留条数、复制后的粘贴提示"),
    PageInfo("files", "布局文件", "导出和重新加载 YAML 布局"),
)

@Composable
private fun SettingsApp(prefs: Prefs) {
    var page by remember { mutableStateOf("home") }
    BackHandler(enabled = page != "home") { page = if (page == "dict_edit") "dict" else "home" }
    AnimatedContent(
        targetState = page,
        transitionSpec = {
            val forward = targetState != "home"
            (slideInHorizontally { if (forward) it / 4 else -it / 4 } + fadeIn()) togetherWith (slideOutHorizontally { if (forward) -it / 4 else it / 4 } + fadeOut())
        },
        label = "page",
    ) { p ->
        when (p) {
            "home" -> Home(prefs) { page = it }
            else -> SubPage(PAGES.firstOrNull { it.id == p }?.title ?: "", { page = "home" }) {
                when (p) {
                    "look" -> LookPage(prefs)
                    "motion" -> MotionPage(prefs)
                    "haptics" -> HapticsPage(prefs)
                    "gestures" -> GesturesPage(prefs)
                    "keys" -> KeyEditorPage(prefs)
                    "languages" -> LanguagesPage(prefs)
                    "chinese" -> ChinesePage(prefs) { page = "dict" }
                    "dict" -> DictPage()
                    "symbols" -> SymbolsPage(prefs)
                    "clipboard" -> ClipboardPage(prefs)
                    "files" -> FilesPage()
                }
            }
        }
    }
}

// ---- building blocks -------------------------------------------------------------------

@Composable
private fun SubPage(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { content() }
        }
    }
}

@Composable
private fun Group(title: String? = null, content: @Composable () -> Unit) {
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
private fun Note(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 4.dp))

@Composable
private fun Toggle(prefs: Prefs, key: String, title: String, sub: String?, def: Boolean) {
    var v by remember { mutableStateOf(prefs.sp.getBoolean(key, def)) }
    Row(Modifier.fillMaxWidth().clickable { v = !v; prefs.put(key, v) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = v, onCheckedChange = { v = it; prefs.put(key, it) })
    }
}

@Composable
private fun IntSlider(prefs: Prefs, key: String, title: String, def: Int, range: IntRange, unit: String, sub: String? = null, onDone: (() -> Unit)? = null) {
    var v by remember { mutableIntStateOf(prefs.sp.getInt(key, def)) }
    Column(Modifier.padding(vertical = 4.dp)) {
        Row { Text(title, Modifier.weight(1f)); Text("$v$unit", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium) }
        if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(value = v.toFloat(), onValueChange = { v = it.roundToInt() }, onValueChangeFinished = { prefs.put(key, v); onDone?.invoke() },
            valueRange = range.first.toFloat()..range.last.toFloat())
    }
}

@Composable
private fun FloatSlider(prefs: Prefs, key: String, title: String, def: Float, range: ClosedFloatingPointRange<Float>, unit: String, sub: String? = null) {
    var v by remember { mutableStateOf(prefs.sp.getFloat(key, def)) }
    Column(Modifier.padding(vertical = 4.dp)) {
        Row { Text(title, Modifier.weight(1f)); Text("${"%.2f".format(java.util.Locale.ROOT, v).trimEnd('0').trimEnd('.')}$unit", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium) }
        if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(value = v, onValueChange = { v = it }, onValueChangeFinished = { prefs.put(key, v) }, valueRange = range)
    }
}

@Composable
private fun Choice(prefs: Prefs, key: String, title: String, options: List<Pair<String, String>>, def: String = options.first().first) {
    var v by remember { mutableStateOf(prefs.sp.getString(key, def) ?: def) }
    Column(Modifier.padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, (id, name) ->
                SegmentedButton(selected = v == id, onClick = { v = id; prefs.put(key, id) },
                    shape = SegmentedButtonDefaults.itemShape(i, options.size)) { Text(name, maxLines = 1) }
            }
        }
    }
}

// ---- home ------------------------------------------------------------------------------

@Composable
private fun Home(prefs: Prefs, open: (String) -> Unit) {
    var tick by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    LazyColumn(Modifier.fillMaxSize().systemBarsPadding().imePadding(), contentPadding = PaddingValues(16.dp, 20.dp, 16.dp, 32.dp)) {
        item {
            Text("KK输入法", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("版本 ${BuildConfig.VERSION_NAME}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
        }
        item { Setup(tick) }
        item { RimeStatus() }
        item {
            Group {
                PAGES.forEachIndexed { i, p ->
                    Row(Modifier.fillMaxWidth().clickable { open(p.id) }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.title, fontWeight = FontWeight.Medium)
                            Text(p.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun Setup(tick: Int) {
    val ctx = LocalContext.current
    val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    val enabled = remember(tick) { imm.enabledInputMethodList.any { it.packageName == ctx.packageName } }
    val current = remember(tick) { Settings.Secure.getString(ctx.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.startsWith(ctx.packageName) == true }
    var test by remember { mutableStateOf("") }
    Group(if (enabled && current) "试一下" else "开始使用") {
        if (!enabled || !current) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                Text(if (enabled) "① 已在系统里启用" else "① 在系统设置里启用 KK输入法", Modifier.weight(1f))
                if (!enabled) Button(onClick = { ctx.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }) { Text("去启用") }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                Text("② 切换为当前输入法", Modifier.weight(1f))
                Button(onClick = { imm.showInputMethodPicker() }, enabled = enabled) { Text("切换") }
            }
        }
        OutlinedTextField(value = test, onValueChange = { test = it }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), placeholder = { Text("在这里试打字") }, minLines = 2, shape = RoundedCornerShape(14.dp))
    }
}

@Composable
private fun RimeStatus() {
    val ctx = LocalContext.current
    var state by remember { mutableStateOf(RimeEngine.state) }
    DisposableEffect(Unit) {
        val l: (RimeEngine.State) -> Unit = { state = it }
        RimeEngine.addListener(l)
        onDispose { RimeEngine.removeListener(l) }
    }
    if (state == RimeEngine.State.READY) return
    Group("中文词库") {
        Text(when (state) {
            RimeEngine.State.DEPLOYING -> "正在部署词库（首次约需一分钟）…"
            RimeEngine.State.PREPARING -> "正在复制词库文件…"
            RimeEngine.State.FAILED -> "启动失败：${RimeEngine.error ?: "未知错误"}"
            else -> "未启动"
        }, modifier = Modifier.padding(vertical = 6.dp))
        if (state == RimeEngine.State.FAILED) FilledTonalButton(onClick = { RimeEngine.redeploy(ctx) }) { Text("重试") }
    }
}

// ---- pages -----------------------------------------------------------------------------

@Composable
private fun LookPage(prefs: Prefs) {
    val ctx = LocalContext.current
    Group("主题") {
        Choice(prefs, Prefs.THEME, "明暗", listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色"))
        var accent by remember { mutableStateOf(prefs.accent) }
        Text("主体色", modifier = Modifier.padding(top = 6.dp))
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            KbTheme.ACCENTS.forEach { (id, v) ->
                val (name, colors) = v
                val dark = isSystemInDarkTheme()
                val c = if (id == "system") {
                    if (android.os.Build.VERSION.SDK_INT >= 31) Color(ctx.getColor(if (dark) android.R.color.system_accent1_200 else android.R.color.system_accent1_600)) else Color.Gray
                } else Color(if (dark) colors.second else colors.first)
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
    }
    Group("按键") {
        IntSlider(prefs, Prefs.KEY_HEIGHT, "按键高度", 46, 36..64, "dp")
        FloatSlider(prefs, Prefs.KEY_GAP, "按键间距", 5f, 2f..10f, "dp")
        FloatSlider(prefs, Prefs.KEY_RADIUS, "按键圆角", 7f, 0f..18f, "dp")
        IntSlider(prefs, Prefs.BOTTOM_PADDING, "键盘底部留白", 4, 0..24, "dp")
        Toggle(prefs, Prefs.KEY_BORDERS, "实心键的投影", "字母、数字键下方的一道浅影", true)
        Toggle(prefs, Prefs.NUMBER_ROW, "常驻数字行", "字母键盘最上面加一排数字", false)
    }
    Group("文字") {
        FloatSlider(prefs, Prefs.LABEL_SCALE, "按键字号", 1f, 0.8f..1.3f, "×")
        FloatSlider(prefs, Prefs.HINT_SCALE, "角标字号", 1f, 0.7f..1.4f, "×")
        FloatSlider(prefs, Prefs.CAND_SCALE, "候选字号", 1f, 0.8f..1.4f, "×")
        Toggle(prefs, Prefs.SHOW_HINTS, "显示角标", "按键右上角的滑动字符", true)
        Toggle(prefs, Prefs.SHOW_ALL_HINTS, "显示四个方向的角标", "下滑、左滑、右滑的字符也画出来", false)
    }
}

@Composable
private fun MotionPage(prefs: Prefs) {
    Group {
        Toggle(prefs, Prefs.ANIMATIONS, "动画", "按键、气泡、弹出选项、面板切换的过渡", true)
        FloatSlider(prefs, Prefs.ANIM_SPEED, "动画时长", 1f, 0.5f..2f, "×", "数值越小越快")
        Toggle(prefs, Prefs.KEY_PREVIEW, "按键气泡", "按下时在按键上方放大显示", true)
    }
}

@Composable
private fun HapticsPage(prefs: Prefs) {
    val ctx = LocalContext.current
    val haptics = remember { Haptics(ctx, prefs) }
    Group {
        Toggle(prefs, Prefs.VIBRATE, "振动", "总开关", true)
        Note("每类事件单独选效果。轻触、点击、重击、双击是系统内置的触感；自定义可以调强度和时长。")
    }
    HapticEvent.values().forEach { e ->
        var type by remember { mutableStateOf(haptics.type(e)) }
        var amp by remember { mutableIntStateOf(haptics.amp(e)) }
        var ms by remember { mutableIntStateOf(haptics.ms(e)) }
        Group(e.title) {
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                HapticEvent.TYPES.take(3).forEach { (id, name) ->
                    FilterChip(selected = type == id, onClick = { type = id; prefs.put("hap_${e.id}_type", id); haptics.play(e) }, label = { Text(name) })
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                HapticEvent.TYPES.drop(3).forEach { (id, name) ->
                    FilterChip(selected = type == id, onClick = { type = id; prefs.put("hap_${e.id}_type", id); haptics.play(e) }, label = { Text(name) })
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { haptics.play(e) }) { Text("试一下") }
            }
            if (type == "custom") {
                Row { Text("强度", Modifier.weight(1f)); Text("$amp", color = MaterialTheme.colorScheme.primary) }
                Slider(value = amp.toFloat(), onValueChange = { amp = it.roundToInt() }, onValueChangeFinished = { prefs.put("hap_${e.id}_amp", amp); haptics.play(e) }, valueRange = 1f..255f)
                Row { Text("时长", Modifier.weight(1f)); Text("${ms}ms", color = MaterialTheme.colorScheme.primary) }
                Slider(value = ms.toFloat(), onValueChange = { ms = it.roundToInt() }, onValueChangeFinished = { prefs.put("hap_${e.id}_ms", ms); haptics.play(e) }, valueRange = 3f..60f)
            }
        }
    }
    Group("声音") {
        Toggle(prefs, Prefs.SOUND, "按键音", null, false)
        FloatSlider(prefs, Prefs.SOUND_VOLUME, "音量", 0.3f, 0.05f..1f, "")
    }
}

@Composable
private fun GesturesPage(prefs: Prefs) {
    Group("滑动和长按") {
        FloatSlider(prefs, Prefs.FLICK_DP, "滑动判定距离", 22f, 12f..40f, "dp", "手指移动超过这个距离才算滑动")
        IntSlider(prefs, Prefs.LONG_PRESS_MS, "长按时间", 300, 150..700, "ms")
        Toggle(prefs, Prefs.LP_HINTS, "长按包含滑动字符", "角标上的符号排在最前面", true)
        Toggle(prefs, Prefs.LP_CASE, "长按包含大写和小写", null, true)
        Toggle(prefs, Prefs.LP_DIGITS, "第一排长按包含数字", null, true)
    }
    Group("空格") {
        Choice(prefs, Prefs.SPACE_SWIPE, "左右滑", listOf("language" to "切换语言", "cursor" to "移动光标", "none" to "无"))
        Choice(prefs, Prefs.SPACE_LONG_PRESS, "长按", listOf("cursor" to "拖动光标", "none" to "无"))
        FloatSlider(prefs, Prefs.CURSOR_STEP_DP, "光标移动一格的距离", 11f, 5f..24f, "dp", "越小越灵敏")
    }
    Group("删除键") {
        Toggle(prefs, Prefs.BACKSPACE_SWIPE, "上滑清空、下滑撤销", "正在打拼音时上滑只清掉拼音", true)
        IntSlider(prefs, Prefs.BACKSPACE_REPEAT, "长按连删的间隔", 55, 25..150, "ms", "匀速连删，数值越小越快")
    }
}

private val LANG_NAMES = mapOf("zh" to "中文（雾凇拼音）", "ru" to "Русский", "en" to "English")

@Composable
private fun LanguagesPage(prefs: Prefs) {
    Group("启用的语言") {
        var order by remember {
            val enabled = prefs.languages
            mutableStateOf(enabled + LANG_NAMES.keys.filter { it !in enabled })
        }
        var enabled by remember { mutableStateOf(prefs.languages.toSet()) }
        fun save() = prefs.put(Prefs.LANGUAGES, order.filter { it in enabled }.joinToString(","))
        Note("顺序就是空格左右滑切换的顺序。")
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
        Toggle(prefs, Prefs.PER_APP_LANG, "按应用记住语言", "比如 Telegram 记住俄语，微信记住中文", true)
    }
    Group("俄语和英语") {
        Toggle(prefs, Prefs.AUTO_CAP, "句首自动大写", "输入框开头、句号问号感叹号之后", true)
        Toggle(prefs, Prefs.DOUBLE_SPACE, "双击空格输入句号", null, true)
        Toggle(prefs, Prefs.STRESS_MODE, "重音模式", "俄语元音上滑输入带重音的字母（а́ е́ и́ …）；不开时长按也有", false)
    }
}

@Composable
private fun ChinesePage(prefs: Prefs, openDict: () -> Unit) {
    val ctx = LocalContext.current
    Group("候选") {
        Toggle(prefs, Prefs.ZH_TRAD, "输出繁体", null, false)
        Toggle(prefs, Prefs.ZH_EMOJI, "候选里带 emoji", "黄脸表情排在原位，其他 emoji 排在前五个词之后", true)
        Toggle(prefs, Prefs.ZH_PREDICT, "联想", "上屏后在候选栏推荐常接的词", true)
        Toggle(prefs, Prefs.ZH_CORRECTION, "按键纠错", "按错相邻键也能出正确的词；可能多出几个不相干的候选。切换后会重新部署", true)
        Note("长按候选词可以删除它的学习记录，下次同样的拼音就不会再把它排到前面。")
    }
    Group("拼音") {
        Toggle(prefs, Prefs.PINYIN_MODE, "拼音模式", "选中候选后上屏带声调的拼音，比如 nǐ hǎo；多音字按你打的拼音判断", false)
        Toggle(prefs, Prefs.ZH_PREEDIT_INLINE, "拼音显示在输入框里", "关闭时只显示在候选栏上方", false)
    }
    Group("词库") {
        Row(Modifier.fillMaxWidth().clickable { openDict() }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("自定义词库", Modifier.weight(1f)); Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
        }
        Text("词库目录：${RimeEngine.userDir(ctx).absolutePath}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FilledTonalButton(onClick = { RimeEngine.redeploy(ctx) }, modifier = Modifier.padding(vertical = 8.dp)) { Text("重新部署") }
    }
}

@Composable
private fun SymbolsPage(prefs: Prefs) {
    Group("符号") {
        Toggle(prefs, Prefs.AUTO_PAIR, "括号和引号自动配对", "输入（ 时补上 ），光标停在中间", true)
        Toggle(prefs, Prefs.SYMBOL_RETURN, "输入符号后回到字母键盘", "符号面板里的锁可以临时关掉", true)
        Toggle(prefs, Prefs.SMART_PUNCT, "数字后的标点保持半角", "3.14 不会变成 3。14", true)
    }
    Group("数学键盘") {
        Choice(prefs, Prefs.MATH_FORMAT, "默认输出格式", listOf("uni" to "符号", "tex" to "LaTeX"))
        Toggle(prefs, Prefs.MATH_CALC, "按 = 后给出计算结果", null, true)
        Toggle(prefs, Prefs.NUMBER_FIELD_MATH, "数字输入框弹出数学键盘", null, true)
    }
}

@Composable
private fun ClipboardPage(prefs: Prefs) {
    Group {
        Toggle(prefs, Prefs.CLIP_SUGGEST, "复制后在候选栏提示粘贴", "一分半钟内点一下就能粘贴", true)
        IntSlider(prefs, Prefs.CLIP_KEEP, "保留的条数", 40, 10..200, "条", "固定的条目不算在内")
        Note("标记为敏感内容的复制（比如密码管理器）不会被记录。")
    }
}

@Composable
private fun FilesPage() {
    val ctx = LocalContext.current
    var path by remember { mutableStateOf<String?>(null) }
    Group {
        Note("所有键盘布局都是 YAML 文件。导出后可以用文本编辑器改键位、滑动字符、长按选项、符号分类和数学键盘；改完点“重新加载”。删掉某个文件就恢复默认。只想改几个键的话，用“键位编辑”更方便。")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
            FilledTonalButton(onClick = { path = LayoutRepo(ctx).exportDefaults().absolutePath }) { Text("导出布局文件") }
            FilledTonalButton(onClick = { Prefs(ctx).put("layout_reload", System.currentTimeMillis().toString()) }) { Text("重新加载") }
        }
        path?.let { Note("已导出到：$it") }
    }
}

// ---- key editor ------------------------------------------------------------------------

@Composable
private fun KeyEditorPage(prefs: Prefs) {
    val ctx = LocalContext.current
    var layoutId by remember { mutableStateOf("zh") }
    var tick by remember { mutableIntStateOf(0) }
    val rows = remember(layoutId, tick) {
        runCatching { LayoutRepo(ctx, overrides = { KeyOverrides.load(prefs, it) }).keyboard(layoutId).rows }.getOrDefault(emptyList())
    }
    var editing by remember { mutableStateOf<Key?>(null) }
    Group {
        Note("点一个键，修改它上、下、左、右滑出的字符和长按选项。改动立即生效，不影响布局文件。")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            val opts = listOf("zh" to "中文", "ru" to "俄语", "en" to "英文", "num" to "数字页")
            opts.forEachIndexed { i, (id, name) ->
                SegmentedButton(selected = layoutId == id, onClick = { layoutId = id }, shape = SegmentedButtonDefaults.itemShape(i, opts.size)) { Text(name) }
            }
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            rows.forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    row.forEach { k ->
                        val w = k.width
                        if (k.spacer) Spacer(Modifier.weight(w))
                        else {
                            val editable = k.main != null
                            Box(
                                Modifier.weight(w).height(46.dp).clip(RoundedCornerShape(8.dp))
                                    .background(if (editable) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
                                    .then(if (editable) Modifier.clickable { editing = k } else Modifier),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(k.main?.label ?: k.label ?: "", fontSize = if (k.main != null) 17.sp else 11.sp, maxLines = 1,
                                    color = if (editable) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                                k.flicks[Dir.UP]?.let {
                                    Text(it.label, fontSize = 9.sp, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Clip,
                                        modifier = Modifier.align(Alignment.TopEnd).padding(top = 2.dp, end = 4.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
        TextButton(onClick = { KeyOverrides.clear(prefs, layoutId); tick++ }) { Text("恢复这个键盘的所有默认键位") }
    }
    editing?.let { k ->
        KeyEditDialog(k, onDismiss = { editing = null }, onSave = { o ->
            KeyOverrides.save(prefs, layoutId, k.main!!.text, o); editing = null; tick++
        })
    }
}

@Composable
private fun KeyEditDialog(k: Key, onDismiss: () -> Unit, onSave: (KeyOverride?) -> Unit) {
    var up by remember { mutableStateOf(k.flicks[Dir.UP]?.text.orEmpty()) }
    var down by remember { mutableStateOf(k.flicks[Dir.DOWN]?.text.orEmpty()) }
    var left by remember { mutableStateOf(k.flicks[Dir.LEFT]?.text.orEmpty()) }
    var right by remember { mutableStateOf(k.flicks[Dir.RIGHT]?.text.orEmpty()) }
    var long by remember { mutableStateOf(k.long.joinToString(" ") { it.text }) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑「${k.main?.label}」") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(up, { up = it }, Modifier.weight(1f), label = { Text("上滑") }, singleLine = true)
                    OutlinedTextField(down, { down = it }, Modifier.weight(1f), label = { Text("下滑") }, singleLine = true)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(left, { left = it }, Modifier.weight(1f), label = { Text("左滑") }, singleLine = true)
                    OutlinedTextField(right, { right = it }, Modifier.weight(1f), label = { Text("右滑") }, singleLine = true)
                }
                OutlinedTextField(long, { long = it }, Modifier.fillMaxWidth(), label = { Text("长按额外选项（空格分隔）") })
                Text("留空表示这个方向不放字符。长按时还会自动加上滑动字符和大小写。", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(KeyOverride(up, down, left, right, long.trim().split(Regex("\\s+")).filter { it.isNotEmpty() })) }) { Text("保存") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onSave(null) }) { Text("恢复默认") }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

// ---- custom dictionary -----------------------------------------------------------------

private data class Phrase(val text: String, val code: String, val weight: String)

private fun readPhrases(ctx: Context): Pair<List<String>, List<Phrase>> {
    val f = RimeEngine.customPhraseFile(ctx)
    if (!f.isFile) return emptyList<String>() to emptyList()
    val header = mutableListOf<String>()
    val list = mutableListOf<Phrase>()
    f.readLines().forEach { line ->
        if (line.startsWith("#") || line.isBlank()) { if (list.isEmpty()) header += line; return@forEach }
        val p = line.split('\t')
        if (p.size >= 2) list += Phrase(p[0], p[1], p.getOrNull(2) ?: "")
    }
    return header to list
}

private fun writePhrases(ctx: Context, header: List<String>, list: List<Phrase>) {
    val f = RimeEngine.customPhraseFile(ctx)
    f.parentFile?.mkdirs()
    val body = list.joinToString("\n") { listOf(it.text, it.code, it.weight).filter { s -> s.isNotEmpty() }.joinToString("\t") }
    f.writeText((if (header.isEmpty()) "# Rime table\n# coding: utf-8\n#@/db_name\tcustom_phrase.txt\n#@/db_type\ttabledb\n#\n" else header.joinToString("\n") + "\n") + body + "\n")
}

@Composable
private fun DictPage() {
    val ctx = LocalContext.current
    var data by remember { mutableStateOf(readPhrases(ctx)) }
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<Pair<Int, Phrase>?>(null) }
    var dirty by remember { mutableStateOf(false) }
    Group {
        Note("这里的词会在打对应编码时排在最前面。编码是不带声调、不加空格的拼音，比如“kkdz”可以对应你的地址。保存后会重新部署，几秒到十几秒后生效。")
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(query, { query = it }, Modifier.weight(1f), placeholder = { Text("搜索词或编码") }, singleLine = true, shape = RoundedCornerShape(14.dp))
            IconButton(onClick = { editing = -1 to Phrase("", "", "99") }) { Icon(Icons.Filled.Add, "添加") }
        }
        if (dirty) Button(onClick = { writePhrases(ctx, data.first, data.second); RimeEngine.redeploy(ctx); dirty = false },
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) { Text("保存并重新部署") }
    }
    val shown = data.second.withIndex().filter { (_, p) -> query.isBlank() || p.text.contains(query) || p.code.contains(query) }
    Group("${shown.size} 个词") {
        shown.take(300).forEach { (i, p) ->
            Row(Modifier.fillMaxWidth().clickable { editing = i to p }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(p.text, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(p.code, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (shown.size > 300) Note("只显示前 300 个，用搜索缩小范围。")
    }
    editing?.let { (index, p) ->
        var text by remember(index) { mutableStateOf(p.text) }
        var code by remember(index) { mutableStateOf(p.code) }
        var weight by remember(index) { mutableStateOf(p.weight) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(if (index < 0) "添加词" else "修改词") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), label = { Text("词") })
                    OutlinedTextField(code, { code = it.lowercase().replace(" ", "") }, Modifier.fillMaxWidth(), label = { Text("编码（拼音）") }, singleLine = true)
                    OutlinedTextField(weight, { weight = it.filter { c -> c.isDigit() } }, Modifier.fillMaxWidth(), label = { Text("权重（越大越靠前）") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(enabled = text.isNotBlank() && code.isNotBlank(), onClick = {
                    val list = data.second.toMutableList()
                    val np = Phrase(text.trim(), code.trim(), weight)
                    if (index < 0) list.add(0, np) else list[index] = np
                    data = data.first to list; dirty = true; editing = null
                }) { Text("确定") }
            },
            dismissButton = {
                Row {
                    if (index >= 0) TextButton(onClick = {
                        data = data.first to data.second.toMutableList().apply { removeAt(index) }; dirty = true; editing = null
                    }) { Text("删除") }
                    TextButton(onClick = { editing = null }) { Text("取消") }
                }
            },
        )
    }
}
