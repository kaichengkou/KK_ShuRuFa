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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.outlined.Animation
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Backup
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.kk.shurufa.BuildConfig
import com.kk.shurufa.R
import com.kk.shurufa.Prefs
import com.kk.shurufa.keyboard.KbTheme
import com.kk.shurufa.keyboard.Key
import com.kk.shurufa.rime.RimeEngine

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
    "工具" to listOf("toolbar", "symbols", "clipboard", "voice", "stats"),
    "小鲨鱼与 AI" to listOf("online", "ai"),
    "高级" to listOf("backup", "files", "about"),
)

private val PAGE_ICONS: Map<String, ImageVector> = mapOf(
    "look" to Icons.Outlined.Palette, "motion" to Icons.Outlined.Animation, "haptics" to Icons.Outlined.Vibration,
    "gestures" to Icons.Outlined.Swipe, "languages" to Icons.Outlined.Language, "chinese" to Icons.Outlined.Translate,
    "dict" to Icons.AutoMirrored.Outlined.MenuBook, "packs" to Icons.AutoMirrored.Outlined.LibraryBooks,
    "keys" to Icons.Outlined.Keyboard, "toolbar" to Icons.Outlined.Apps, "symbols" to Icons.Outlined.Functions,
    "clipboard" to Icons.Outlined.ContentPaste, "voice" to Icons.Outlined.Mic, "ai" to Icons.Outlined.Key, "online" to Icons.Outlined.AutoAwesome,
    "files" to Icons.Outlined.Code, "stats" to Icons.Outlined.Insights, "backup" to Icons.Outlined.Backup, "about" to Icons.Outlined.Info,
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
    PageInfo("packs", "扩展词库", "语法模型、导入搜狗词库、古诗文、雾凇词库更新"),
    PageInfo("symbols", "符号与数学", "括号配对、数字后标点、数学键盘"),
    PageInfo("clipboard", "剪贴板", "保留条数、复制后的粘贴提示"),
    PageInfo("toolbar", "工具栏", "选择和排列键盘上方的按钮"),
    PageInfo("voice", "语音输入", "本地识别模型、识别语言、系统识别"),
    PageInfo("ai", "AI 服务", "填 Key、选模型、今天的用量"),
    PageInfo("online", "小鲨鱼", "名字、每日一句、聊天、改写、提示词、思考深度"),
    PageInfo("backup", "备份与恢复", "设置、学到的词和自定义词库，换手机不丢"),
    PageInfo("files", "布局文件", "导出和重新加载 YAML 布局"),
    PageInfo("stats", "打字数据", "每天的字数、时段、常用词云"),
    PageInfo("about", "关于与更新", "版本、检查更新、隐私、开源许可"),
)

@Composable
private fun SettingsApp(prefs: Prefs, start: String) {
    var page by remember { mutableStateOf(start) }
    // the home list keeps where it was while a page is open, so 返回 lands back there
    val homeList = androidx.compose.foundation.lazy.rememberLazyListState()
    val back = { page = if (page == "dict_edit") "dict" else "home" }
    LeaveGuard.leave = back
    BackHandler(enabled = page != "home") { LeaveGuard.ask?.invoke() ?: back() }
    AnimatedContent(
        targetState = page,
        transitionSpec = {
            val forward = targetState != "home"
            (slideInHorizontally { if (forward) it / 4 else -it / 4 } + fadeIn()) togetherWith (slideOutHorizontally { if (forward) -it / 4 else it / 4 } + fadeOut())
        },
        label = "page",
    ) { p ->
        when (p) {
            "home" -> Home(prefs, homeList) { page = it }
            else -> SubPage(PAGES.firstOrNull { it.id == p }?.title ?: "", { LeaveGuard.ask?.invoke() ?: run { page = "home" } }, page = p, prefs = prefs,
                header = if (p == "look") ({ KeyboardPreview(prefs) }) else null) {
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
                    "ai" -> AiServicesPage(prefs)
                    "online" -> OnlinePage(prefs) { page = "ai" }
                    "files" -> FilesPage()
                    "backup" -> BackupPage()
                    "stats" -> StatsPage(prefs)
                    "about" -> AboutPage()
                }
            }
        }
    }
}

// ---- home ------------------------------------------------------------------------------

@Composable
private fun Home(prefs: Prefs, list: androidx.compose.foundation.lazy.LazyListState, open: (String) -> Unit) {
    var tick by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    LazyColumn(Modifier.fillMaxSize().systemBarsPadding().imePadding(), state = list, contentPadding = PaddingValues(16.dp, 20.dp, 16.dp, 32.dp)) {
        item {
            // the app's own key, as on the home screen, beside its name
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 18.dp)) {
                AppIcon(56.dp)
                Spacer(Modifier.width(16.dp))
                Column {
                    Text("KK输入法", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("版本 ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item { UpdateBanner { open("about") } }
        item { key(tick) { StatsSummary { open("stats") } } }
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
            RimeEngine.State.FAILED -> "中文引擎没有启动成功，点重试再部署一次；还不行的话，重启手机后再试"
            else -> "未启动"
        }, modifier = Modifier.padding(vertical = 6.dp))
        // what went wrong, small, for a bug report
        if (state == RimeEngine.State.FAILED) RimeEngine.error?.let { Note(it) }
        if (state == RimeEngine.State.FAILED) FilledTonalButton(onClick = { RimeEngine.redeploy(ctx) }) { Text("重试") }
    }
}

/** A newer version is out (checked at most once a day): a card on the home page. */
@Composable
private fun UpdateBanner(open: () -> Unit) {
    val ctx = LocalContext.current
    var r by remember { mutableStateOf(com.kk.shurufa.online.Updates.cached(ctx)) }
    LaunchedEffect(Unit) { com.kk.shurufa.online.Updates.checkDaily(ctx) { r = it ?: r } }
    val rel = r ?: return
    androidx.compose.material3.Card(Modifier.fillMaxWidth().padding(bottom = 12.dp).clickable { open() }, colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Info, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("有新版本 ${rel.version}", fontWeight = FontWeight.Medium)
                Text("点这里看看更新了什么", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
        }
    }
}
