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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.outlined.Animation
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Functions
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Swipe
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.automirrored.outlined.MenuBook
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
            val scheme = remember(kb) { settingsColors(kb) }
            MaterialTheme(colorScheme = scheme) {
                Surface(color = MaterialTheme.colorScheme.background) { SettingsApp(prefs, intent.getStringExtra("page") ?: "home") }
            }
        }
    }
}

/**
 * The settings in the keyboard's own colors: every Material role is derived from the same theme,
 * so no component falls back to a default hue of its own.
 */
private fun settingsColors(kb: KbTheme): androidx.compose.material3.ColorScheme {
    val accent = Color(kb.accent)
    val bg = if (kb.dark) Color(0xFF111316) else Color(0xFFF3F4F7)
    val card = if (kb.dark) Color(0xFF1C1F23) else Color.White
    val high = if (kb.dark) Color(0xFF262A30) else Color(0xFFE9ECF0)
    val ink = Color(kb.ink)
    val inkSoft = Color(kb.inkSoft)
    // a container is the accent laid thinly over the card
    val tint = Color(com.kk.shurufa.keyboard.Colors.blend(card.toArgb(), kb.accent, if (kb.dark) 0.22f else 0.14f))
    val base = if (kb.dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = accent, onPrimary = Color(kb.onAccent),
        primaryContainer = tint, onPrimaryContainer = accent,
        secondary = accent, onSecondary = Color(kb.onAccent),
        secondaryContainer = tint, onSecondaryContainer = if (kb.dark) ink else accent,
        tertiary = accent, onTertiary = Color(kb.onAccent), tertiaryContainer = tint, onTertiaryContainer = accent,
        background = bg, onBackground = ink, surface = bg, onSurface = ink,
        surfaceVariant = high, onSurfaceVariant = inkSoft,
        surfaceContainerLowest = card, surfaceContainerLow = card, surfaceContainer = card,
        surfaceContainerHigh = high, surfaceContainerHighest = high,
        outline = Color(kb.hint), outlineVariant = Color(com.kk.shurufa.keyboard.Colors.blend(card.toArgb(), kb.ink, 0.12f)),
        inverseSurface = ink, inverseOnSurface = bg,
    )
}

private data class PageInfo(val id: String, val title: String, val summary: String)

/** The home page in sections, each page with its icon. */
private val SECTIONS = listOf(
    "外观与手感" to listOf("look", "motion", "haptics", "gestures"),
    "输入" to listOf("languages", "chinese", "dict", "packs", "keys"),
    "工具" to listOf("toolbar", "symbols", "clipboard", "voice", "online"),
    "高级" to listOf("files"),
)

private val PAGE_ICONS: Map<String, ImageVector> = mapOf(
    "look" to Icons.Outlined.Palette, "motion" to Icons.Outlined.Animation, "haptics" to Icons.Outlined.Vibration,
    "gestures" to Icons.Outlined.Swipe, "languages" to Icons.Outlined.Language, "chinese" to Icons.Outlined.Translate,
    "dict" to Icons.AutoMirrored.Outlined.MenuBook, "packs" to Icons.AutoMirrored.Outlined.LibraryBooks,
    "keys" to Icons.Outlined.Keyboard, "toolbar" to Icons.Outlined.Apps, "symbols" to Icons.Outlined.Functions,
    "clipboard" to Icons.Outlined.ContentPaste, "voice" to Icons.Outlined.Mic, "online" to Icons.Outlined.Cloud,
    "files" to Icons.Outlined.Code,
)

private val PAGES = listOf(
    PageInfo("look", "外观", "主题、主体色、按键尺寸与字号"),
    PageInfo("motion", "动效", "动画开关与时长、按键气泡"),
    PageInfo("haptics", "振动与声音", "每类按键的振动效果都可以单独搭配"),
    PageInfo("gestures", "手势", "滑动、长按、空格与删除键"),
    PageInfo("keys", "键位编辑", "改任意键的四向滑动字符和长按选项"),
    PageInfo("languages", "语言", "启用的语言、顺序、自动大写、俄语重音"),
    PageInfo("chinese", "中文", "简繁、emoji、联想、纠错、拼音模式"),
    PageInfo("dict", "自定义词库", "添加、修改、删除自己的词"),
    PageInfo("packs", "扩展词库", "古诗文等，可整句输入并自动接下句"),
    PageInfo("symbols", "符号与数学", "括号配对、数字后标点、数学键盘"),
    PageInfo("clipboard", "剪贴板", "保留条数、复制后的粘贴提示"),
    PageInfo("toolbar", "工具栏", "选择和排列键盘上方的按钮"),
    PageInfo("voice", "语音输入", "本地识别模型、识别语言、系统识别"),
    PageInfo("online", "联网功能", "翻译、词库更新、AI 改写"),
    PageInfo("files", "布局文件", "导出和重新加载 YAML 布局"),
)

@Composable
private fun SettingsApp(prefs: Prefs, start: String) {
    var page by remember { mutableStateOf(start) }
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
                    "packs" -> PacksPage(prefs)
                    "symbols" -> SymbolsPage(prefs)
                    "clipboard" -> ClipboardPage(prefs)
                    "voice" -> VoicePage(prefs)
                    "toolbar" -> ToolbarPage(prefs)
                    "online" -> OnlinePage(prefs)
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
private fun IntSlider(prefs: Prefs, key: String, title: String, def: Int, range: IntRange, unit: String, sub: String? = null, onDone: (() -> Unit)? = null) {
    var v by remember { mutableIntStateOf(prefs.sp.getInt(key, def)) }
    val step = intStep(range.last - range.first)
    Column(Modifier.padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f))
            ValueLabel("$v$unit", title, unit) { t ->
                val n = t.toIntOrNull() ?: return@ValueLabel false
                if (n !in range) return@ValueLabel false
                v = n; prefs.put(key, n); onDone?.invoke(); true
            }
        }
        if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // the value snaps to tidy steps by itself; the track stays clean, without tick marks
        Slider(value = v.toFloat(), onValueChange = { v = (range.first + ((it - range.first) / step).roundToInt() * step).coerceIn(range) },
            onValueChangeFinished = { prefs.put(key, v); onDone?.invoke() },
            valueRange = range.first.toFloat()..range.last.toFloat(), colors = cleanSlider())
    }
}

@Composable
private fun FloatSlider(prefs: Prefs, key: String, title: String, def: Float, range: ClosedFloatingPointRange<Float>, unit: String, sub: String? = null) {
    var v by remember { mutableStateOf(prefs.sp.getFloat(key, def)) }
    val step = floatStep(range.endInclusive - range.start)
    Column(Modifier.padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f))
            ValueLabel("${fmt(v)}$unit", title, unit) { t ->
                val n = t.toFloatOrNull() ?: return@ValueLabel false
                if (n < range.start || n > range.endInclusive) return@ValueLabel false
                v = n; prefs.put(key, n); true
            }
        }
        if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(value = v, onValueChange = { v = (range.start + ((it - range.start) / step).roundToInt() * step).coerceIn(range.start, range.endInclusive) },
            onValueChangeFinished = { prefs.put(key, v) }, valueRange = range, colors = cleanSlider())
    }
}

@Composable
private fun cleanSlider() = androidx.compose.material3.SliderDefaults.colors(activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent)

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
        for ((title, ids) in SECTIONS) item {
            Group(title) {
                for (id in ids) {
                    val p = PAGES.first { it.id == id }
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { open(p.id) }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(38.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                            Icon(PAGE_ICONS.getValue(id), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        }
                        Spacer(Modifier.width(14.dp))
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
    }
    Group("按键") {
        IntSlider(prefs, Prefs.KEY_HEIGHT, "按键高度", 46, 36..64, "dp")
        FloatSlider(prefs, Prefs.KEY_GAP, "按键间距", 5f, 2f..10f, "dp")
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
    DisposableEffect(haptics) { onDispose { haptics.release() } }
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
        Toggle(prefs, Prefs.DRAG_CANCEL, "拖远了就不上屏", "按下后手指滑出很远，当作误触取消", true)
        IntSlider(prefs, Prefs.HOLD_CANCEL_MS, "按住太久不上屏", 1000, 0..3000, "ms", "没有长按菜单的键和剪贴板、候选：按住超过这么久松开不算点击；0 为关闭")
        Toggle(prefs, Prefs.TOP_ROW_SCRIPTS, "第一排左右滑输入上下角标数字", "左滑 ¹²³，右滑 ₁₂₃", false)
    }
    Group("空格") {
        Choice(prefs, Prefs.SPACE_SWIPE, "左右滑", listOf("language" to "切换语言", "cursor" to "移动光标", "none" to "无"))
        Choice(prefs, Prefs.SPACE_LONG_PRESS, "长按", listOf("cursor" to "拖动光标", "none" to "无"))
        FloatSlider(prefs, Prefs.CURSOR_STEP_DP, "光标移动一格的距离", 11f, 5f..24f, "dp", "越小越灵敏")
    }
    Group("删除键") {
        Toggle(prefs, Prefs.BACKSPACE_SWIPE, "上滑清空、下滑撤销", "正在打拼音时上滑只清掉拼音", true)
        Toggle(prefs, Prefs.BACKSPACE_RESELECT, "删除后按原拼音推荐", "删掉刚上屏的字后，候选栏按当初打的拼音重新给出候选，不会把字母放回去；直接打字就忽略它。没有拼音记录时按前文联想", true)
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
        Toggle(prefs, Prefs.TYPO_MARK, "纠错批注", "纠错得到的候选后面用小字标出改了哪里，比如 p→o、no⇄on", true)
        Toggle(prefs, Prefs.RARE_ANNOTATE, "生僻字注音", "常用 3500 字以外的字，在候选后面标出读音", true)
        Note("长按候选词可以删除它的学习记录，下次同样的拼音就不会再把它排到前面。点候选栏上方的拼音，可以把光标放到那里直接改。")
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
        Choice(prefs, Prefs.MATH_FORMAT, "默认输出格式", listOf("uni" to "文本", "tex" to "LaTeX"))
        Toggle(prefs, Prefs.MATH_CALC, "按 = 后给出计算结果", null, true)
        Toggle(prefs, Prefs.NUMBER_FIELD_MATH, "数字输入框弹出数字键盘", "按上次用的模式（简易或科学）打开", true)
        Choice(prefs, Prefs.NUM_MODE, "数字键盘默认模式", listOf("simple" to "标准", "sci" to "学术"))
        IntSlider(prefs, Prefs.SYMBOL_MEMORY, "记住符号面板的时间", 7, 0..30, "秒", "用过符号面板后这么久之内，再点 !#1 直接回到符号面板；0 为总是先开数字键盘")
    }
    EmojiGroup(prefs)
}

@Composable
private fun EmojiGroup(prefs: Prefs) {
    val ctx = LocalContext.current
    val data = remember { com.kk.shurufa.ime.EmojiData(ctx).also { runCatching { it.load() } } }
    var version by remember { mutableStateOf(data.version) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    Group("Emoji") {
        Text("当前版本：Emoji $version", modifier = Modifier.padding(vertical = 4.dp))
        Toggle(prefs, Prefs.EMOJI_AUTO_UPDATE, "自动检查更新", "每周最多一次，从 unicode.org 和 CLDR 下载新版表情列表和中英文名称", true)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
            Text(status, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(enabled = !busy, onClick = {
                busy = true; status = "正在检查…"
                val main = android.os.Handler(android.os.Looper.getMainLooper())
                Thread {
                    val r = runCatching { com.kk.shurufa.ime.EmojiData.update(ctx, version) }
                    main.post {
                        busy = false
                        r.onSuccess { v ->
                            if (v == null) status = "已是最新"
                            else { version = v; status = "已更新到 Emoji $v" }
                            prefs.state.edit().putLong(Prefs.EMOJI_CHECKED, System.currentTimeMillis())
                                .putLong(Prefs.EMOJI_RELOAD, System.currentTimeMillis()).apply()
                        }.onFailure { status = "检查失败：${it.message ?: "网络错误"}" }
                    }
                }.start()
            }) { Text("立即检查") }
        }
        Note("新表情只有手机字体支持时才会显示；系统画不出来的会自动隐藏，因为发出去对方多半也看不到。")
    }
}

@Composable
private fun ClipboardPage(prefs: Prefs) {
    Group {
        Toggle(prefs, Prefs.CLIP_SUGGEST, "复制后在候选栏提示粘贴", "一分半钟内点一下就能粘贴", true)
        IntSlider(prefs, Prefs.CLIP_KEEP, "保留的条数", 40, 10..200, "条", "收藏的条目不算在内")
    }
    Group("面板") {
        IntSlider(prefs, Prefs.CLIP_COLUMNS, "收藏的列数", 2, 2..4, "列", "列越多，一屏看到的越多，每条显示得越短")
        IntSlider(prefs, Prefs.CLIP_MAX_CHARS, "每条最多显示", 80, 20..300, "字", "更长的内容截断显示，粘贴时仍是完整的")
        FloatSlider(prefs, Prefs.CLIP_FRICTION, "滑动阻尼", 0.8f, 0.4f..2.5f, "×", "越大越“沉”，甩一下滑过的条数越少")
        Note("最近：横向滑动，停下时总有一条对齐左边。收藏：竖向滑动。点按粘贴，其余操作在长按菜单里。拖动工具栏或顶部横条可以拉高面板。")
    }
    Group {
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
            FilledTonalButton(onClick = { Prefs(ctx).put(Prefs.LAYOUT_RELOAD, System.currentTimeMillis().toString()) }) { Text("重新加载") }
        }
        path?.let { Note("已导出到：$it") }
    }
}

@Composable
private fun PacksPage(prefs: Prefs) {
    Group("古诗文") {
        Toggle(prefs, Prefs.PACK_POETRY, "启用古诗文词库", "唐诗三百首、宋词三百首、千家诗、诗经、论语、楚辞、古文观止，约 3.8 万句。整句拼音或首字母都能打出（比如 brysj → 白日依山尽），上屏一句后联想栏接出下一句。切换后会重新部署", true)
        Note("数据来自 chinese-poetry 项目（MIT 许可）。")
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

// ---- voice ---------------------------------------------------------------------------------

/** A downloadable model row: title, size, state, and 下载 / 停止 / 删除. */
@Composable
private fun ModelRow(title: String, note: String, installed: Boolean, selected: Boolean?, onSelect: (() -> Unit)?,
                     download: ((progress: (Float) -> Unit, cancelled: () -> Boolean) -> Unit), delete: () -> Unit) {
    var progress by remember { mutableStateOf(-1f) }
    var have by remember { mutableStateOf(installed) }
    var cancel by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    val main = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    Row(Modifier.fillMaxWidth().let { if (onSelect != null) it.clickable { onSelect() } else it }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (selected != null) RadioButton(selected = selected, onClick = { onSelect?.invoke() })
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(when {
                progress >= 0f -> "正在下载 ${(progress * 100).toInt()}%"
                msg.isNotEmpty() -> msg
                have -> "已下载 · $note"
                else -> note
            }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        when {
            progress >= 0f -> TextButton(onClick = { cancel = true }) { Text("停止") }
            have -> TextButton(onClick = { delete(); have = false; msg = "已删除" }) { Text("删除") }
            else -> TextButton(onClick = {
                progress = 0f; cancel = false; msg = ""
                Thread {
                    val r = runCatching { download({ p -> main.post { progress = p } }, { cancel }) }
                    main.post {
                        progress = -1f
                        r.onSuccess { if (!cancel) { have = true; msg = "" } else msg = "已停止，下次会接着下载" }
                         .onFailure { msg = "下载失败：${it.message ?: "网络错误"}，可以换个下载来源再试" }
                    }
                }.start()
            }) { Text("下载") }
        }
    }
    if (progress >= 0f) LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun VoicePage(prefs: Prefs) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val granted = remember(tick) { com.kk.shurufa.voice.PermissionActivity.granted(ctx) }
    Group("识别方式") {
        run {
            var pick by remember { mutableStateOf(false) }
            var cur by remember { mutableStateOf(prefs.voiceEngine) }
            val engines = listOf("whisper" to "本地模型（离线）", "system" to "系统语音识别") + com.kk.shurufa.online.Llm.speechTargets(prefs)
            PickRow("识别用", engines.firstOrNull { it.first == cur }?.second ?: com.kk.shurufa.online.Llm.targetLabel(prefs, cur)) { pick = true }
            if (pick) PickerDialog("识别用", engines, cur, onPick = { cur = it; prefs.put(Prefs.VOICE_ENGINE, it) }, onClose = { pick = false })
            Note("也可以用 AI 服务识别：OpenAI 格式的服务（OpenAI、硅基流动、Groq 等）需要先在 联网功能 里启用一个语音模型，如 whisper-1、gpt-4o-mini-transcribe、FunAudioLLM/SenseVoiceSmall、whisper-large-v3-turbo；Gemini 直接用它的对话模型听写。AI 识别要联网，每段说完才出字。语音面板上也能随时切换。")
        }
        Note("本地模型在手机上离线识别，边说边出字。中文和英文都用 SenseVoice（专为中文训练，英文也很好，带标点）；俄语它不支持，用 Whisper。系统识别用手机自带的服务，准确度取决于手机。")
        Choice(prefs, Prefs.VOICE_EN_MODEL, "英文用", listOf("sensevoice" to "SenseVoice（流式）", "whisper" to "Whisper"))
        Toggle(prefs, Prefs.VOICE_RETURN, "说完自动回到键盘", "自动结束收音后回到字母键盘", false)
        Choice(prefs, Prefs.VOICE_LANG, "识别语言", listOf("follow" to "跟随键盘", "zh" to "中文", "en" to "英文", "ru" to "俄语"))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
            Text(if (granted) "已允许使用麦克风" else "还没有麦克风权限", Modifier.weight(1f))
            if (!granted) Button(onClick = { com.kk.shurufa.voice.PermissionActivity.request(ctx) }) { Text("允许") }
        }
    }
    Group("断句和标点") {
        Choice(prefs, Prefs.VOICE_PUNCT, "标点", listOf("full" to "完整标点", "chat" to "聊天风格", "none" to "不加标点"))
        Note("边想边说时中途的停顿不会把话截断：一段话会整体识别，逗号和句号由模型按意思决定。完整标点：保留模型的标点。聊天风格：每段末尾不加句号，下一段用逗号接上。不加标点：只用空格分开。")
        Toggle(prefs, Prefs.VOICE_FILLERS, "去掉语气词", "嗯、呃、额这类停顿时的声音不上屏", true)
        Toggle(prefs, Prefs.VOICE_PANGU, "中英文之间加空格", "“我用 iPhone 拍了 3 张”", false)
        IntSlider(prefs, Prefs.VOICE_COMMIT_PAUSE, "一段话说完的停顿", 2000, 1000..4000, "ms", "停顿超过这么久，这段话才算说完并定稿；想得慢可以调长")
        IntSlider(prefs, Prefs.VOICE_AUTO_STOP, "自动结束", 3, 0..10, "秒", "这么久没有说话就停止收音；0 为不自动结束")
    }
    Group("模型") {
        Choice(prefs, Prefs.VOICE_SOURCE, "下载来源", listOf("hf" to "Hugging Face", "mirror" to "国内镜像"))
        Text("中文", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
        ModelRow("${com.kk.shurufa.voice.VoiceModels.SenseVoiceModel.TITLE} · ${com.kk.shurufa.voice.VoiceModels.SenseVoiceModel.SIZE_MB} MB",
            "阿里通义 SenseVoice-Small，中文准确率高，识别很快，自带标点和数字格式",
            remember(tick) { com.kk.shurufa.voice.VoiceModels.SenseVoiceModel.installed(ctx) }, null, null,
            { p, c -> com.kk.shurufa.voice.VoiceModels.downloadSenseVoice(ctx, prefs.voiceSource, p, c) },
            { com.kk.shurufa.voice.VoiceModels.SenseVoiceModel.delete(ctx) })
        Text("英文、俄语（Whisper，选一个）", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
        var selected by remember { mutableStateOf(prefs.voiceModel) }
        for (m in com.kk.shurufa.voice.VoiceModels.all) {
            ModelRow("${m.title} · ${m.sizeMb} MB", m.note, remember(tick) { com.kk.shurufa.voice.VoiceModels.installed(ctx, m) },
                selected == m.id, { selected = m.id; prefs.put(Prefs.VOICE_MODEL, m.id) },
                { p, c -> com.kk.shurufa.voice.VoiceModels.download(ctx, m, prefs.voiceSource, p, c) },
                { com.kk.shurufa.voice.VoiceModels.file(ctx, m).delete() })
        }
        Note("模型只存在手机里，录音不会上传。下载建议连 Wi-Fi。")
    }
}

// ---- online --------------------------------------------------------------------------------

/**
 * A list to pick from in a dialog, with a search box when it is long. [checked] makes it a
 * multi-select (选中启用); otherwise tapping a row picks it and closes.
 */
@Composable
private fun PickerDialog(title: String, items: List<Pair<String, String>>, selected: String?, checked: Set<String>? = null,
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
private fun PickRow(title: String, value: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f))
        Text("$value ▾", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ProviderCard(prefs: Prefs, p: com.kk.shurufa.online.Llm.Provider, onChanged: () -> Unit) {
    val L = com.kk.shurufa.online.Llm
    val main = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    var open by remember { mutableStateOf(L.configured(prefs, p.id)) }
    var key by remember { mutableStateOf(L.key(prefs, p.id)) }
    var base by remember { mutableStateOf(prefs.sp.getString("llm_${p.id}_base", "")!!) }
    var shown by remember { mutableStateOf(false) }
    var test by remember { mutableStateOf("") }
    var tick by remember { mutableIntStateOf(0) }
    val enabled = remember(tick) { L.enabledModels(prefs, p.id) }
    val current = remember(tick) { L.model(prefs, p.id) }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(p.title, fontWeight = FontWeight.Medium)
                Text(if (L.configured(prefs, p.id)) "已启用 ${enabled.size} 个模型 · 默认 $current" else p.site,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(if (open) "收起" else "设置", color = MaterialTheme.colorScheme.primary)
        }
        if (!open) return@Column
        OutlinedTextField(key, { key = it.trim(); prefs.put("llm_${p.id}_key", key); onChanged() }, Modifier.fillMaxWidth().padding(vertical = 4.dp),
            label = { Text("API Key") }, singleLine = true, placeholder = { Text(p.keyHint) },
            visualTransformation = if (shown) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
            trailingIcon = { TextButton(onClick = { shown = !shown }) { Text(if (shown) "隐藏" else "显示") } })
        if (p.id == "custom") Choice(prefs, "llm_custom_protocol", "接口格式", L.PROTOCOLS)
        if (p.id != "claude") OutlinedTextField(base, { base = it.trim(); prefs.put("llm_${p.id}_base", base); onChanged() }, Modifier.fillMaxWidth().padding(vertical = 4.dp),
            label = { Text("接口地址") }, singleLine = true, placeholder = { Text(p.defaultBase.ifEmpty { "https://…/v1" }) })
        // models: the default one (tap for the list of enabled ones), and 管理模型 to enable more
        var pickDefault by remember { mutableStateOf(false) }
        var manage by remember { mutableStateOf(false) }
        var fetching by remember { mutableStateOf("") }
        PickRow("默认模型", current.ifEmpty { "未选择" }) { pickDefault = true }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(enabled = key.isNotBlank() && fetching != "…", onClick = {
                fetching = "…"
                Thread {
                    val r = runCatching { L.listModels(prefs, p.id) }
                    main.post {
                        r.onSuccess { L.setCachedModels(prefs, p.id, it); fetching = ""; tick++; manage = true }
                         .onFailure { fetching = it.message ?: "获取失败" }
                    }
                }.start()
            }) { Text(if (fetching == "…") "正在获取…" else "获取可用模型") }
            TextButton(onClick = { manage = true }) { Text("管理启用的模型") }
        }
        if (fetching.isNotEmpty() && fetching != "…") Text(fetching, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        if (pickDefault) PickerDialog("${p.title} 默认模型", enabled.map { it to it }, current, onPick = { m ->
            prefs.put("llm_${p.id}_model", m); tick++; onChanged() }, onClose = { pickDefault = false })
        if (manage) {
            val all = (L.cachedModels(prefs, p.id) + L.MODEL_SUGGESTIONS[p.id].orEmpty() + enabled).distinct()
            var checked by remember { mutableStateOf(enabled.toSet()) }
            PickerDialog("选中启用（${p.title}）", all.map { it to it }, null, checked, onPick = {}, onToggle = { m, on ->
                checked = if (on) checked + m else checked - m
                L.setEnabledModels(prefs, p.id, all.filter { it in checked })
                if (on && checked.size == 1) prefs.put("llm_${p.id}_model", m)
                if (!on && m == L.model(prefs, p.id)) checked.firstOrNull()?.let { prefs.put("llm_${p.id}_model", it) }
                tick++; onChanged()
            }, onClose = { manage = false })
        }
        var manual by remember { mutableStateOf("") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(manual, { manual = it.trim() }, Modifier.weight(1f).padding(vertical = 4.dp), singleLine = true,
                placeholder = { Text("手动添加模型名") })
            TextButton(enabled = manual.isNotBlank(), onClick = {
                L.setEnabledModels(prefs, p.id, enabled + manual)
                if (enabled.isEmpty() || L.model(prefs, p.id).isBlank()) prefs.put("llm_${p.id}_model", manual)
                manual = ""; tick++; onChanged()
            }) { Text("添加") }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
            Text(test, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(enabled = key.isNotBlank(), onClick = {
                test = "正在测试…"
                Thread {
                    val r = runCatching {
                        val a = com.kk.shurufa.online.AiWriter.ACTIONS.first { it.id == "fix" }
                        L.complete(prefs, p.id, com.kk.shurufa.online.AiWriter.SYSTEM, com.kk.shurufa.online.AiWriter.prompt(a, "今天天气真好，我门去公园吧"))
                    }
                    main.post { test = r.fold({ "可以用：$it" }, { it.message ?: "失败" }) }
                }.start()
            }) { Text("测试") }
        }
    }
}

@Composable
private fun OnlinePage(prefs: Prefs) {
    val ctx = LocalContext.current
    val main = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    var tick by remember { mutableIntStateOf(0) }
    Group("AI 服务") {
        Note("填哪家的 Key 就能用哪家，可以都填。每家可以“获取可用模型”，搜索并勾选启用；启用的模型都能在改写和翻译里选。Key 只保存在这台手机上，只有你点了操作，文字才会发出去。")
        for (p in com.kk.shurufa.online.Llm.PROVIDERS) ProviderCard(prefs, p) { tick++ }
    }
    val targets = remember(tick) { com.kk.shurufa.online.Llm.targets(prefs) }
    var pickAi by remember { mutableStateOf(false) }
    var pickTr by remember { mutableStateOf(false) }
    Group("AI 改写") {
        if (targets.isEmpty()) Note("先在上面设置至少一个 AI 服务。")
        else {
            val cur = com.kk.shurufa.online.Llm.resolve(prefs, prefs.aiProvider)
            PickRow("使用", cur?.let { com.kk.shurufa.online.Llm.targetLabel(prefs, it) } ?: "未选择") { pickAi = true }
            if (pickAi) PickerDialog("AI 改写使用", targets, cur, onPick = { prefs.put(Prefs.AI_PROVIDER, it); tick++ }, onClose = { pickAi = false })
        }
        Note("工具栏的 ✦：润色、纠错、正式、口语、精简、扩写，翻译成英文 / 俄语 / 中文，以及“输入指令”——直接用键盘打出想怎么改，回车执行。面板右上角可以临时换模型。")
    }
    Group("翻译") {
        val engines = listOf("auto" to "自动（有 AI 用 AI，否则离线）", "mlkit" to "离线（ML Kit）") + targets
        val curE = prefs.translateEngine
        PickRow("翻译引擎", engines.firstOrNull { it.first == curE }?.second ?: com.kk.shurufa.online.Llm.targetLabel(prefs, curE)) { pickTr = true }
        if (pickTr) PickerDialog("翻译引擎", engines, curE, onPick = { prefs.put(Prefs.TRANSLATE_ENGINE, it); tick++ }, onClose = { pickTr = false })
        Choice(prefs, Prefs.TRANSLATE_TARGET, "默认翻译成", com.kk.shurufa.online.TranslateService.LANGS.take(3))
        Toggle(prefs, Prefs.TRANSLATE_WIFI, "离线模型只在 Wi-Fi 下载", "每种语言约 30 MB，下载一次后离线翻译", false)
        Note("点工具栏的翻译按钮，键盘上方会展开翻译栏：可以选语言、⇄ 对调、切换引擎；选中文字时直接翻译并可替换。AI 翻译更地道，离线翻译不联网也能用。")
    }
    Group("词库更新") {
        var status by remember { mutableStateOf("词库来自雾凇拼音（rime-ice），可以随时更新到最新版") }
        var plan by remember { mutableStateOf<com.kk.shurufa.online.DictUpdater.Plan?>(null) }
        var busy by remember { mutableStateOf(false) }
        var progress by remember { mutableStateOf(-1f) }
        Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 4.dp))
        if (progress >= 0f) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
        Row(Modifier.padding(vertical = 4.dp)) {
            Button(enabled = !busy, onClick = {
                busy = true; status = "正在检查…"
                Thread {
                    val r = runCatching { com.kk.shurufa.online.DictUpdater.check(ctx) }
                    main.post {
                        busy = false
                        r.onSuccess { p -> plan = p; status = if (p.changed.isEmpty()) "已是最新" else "有 ${p.changed.size} 个词库文件更新，共 ${"%.1f".format(p.bytes / 1e6)} MB" }
                         .onFailure { status = "检查失败：${it.message ?: "网络错误"}" }
                    }
                }.start()
            }) { Text("检查更新") }
            Spacer(Modifier.width(8.dp))
            val p = plan
            if (p != null && p.changed.isNotEmpty()) Button(enabled = !busy, onClick = {
                busy = true; progress = 0f; status = "正在下载…"
                Thread {
                    val r = runCatching { com.kk.shurufa.online.DictUpdater.apply(ctx, p) { f -> main.post { progress = f } } }
                    main.post {
                        busy = false; progress = -1f; plan = null
                        status = r.fold({ "已下载，正在重新部署词库（约一分钟，期间中文暂不可用）" }, { "下载失败：${it.message ?: "网络错误"}" })
                    }
                }.start()
            }) { Text("下载并部署") }
        }
        Note("更新后的词库放在应用自己的文件夹里，优先于安装包自带的版本；你自己造的词和学习记录不受影响。")
    }
}

// ---- toolbar -------------------------------------------------------------------------------

@Composable
private fun ToolbarPage(prefs: Prefs) {
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
