package com.kk.shurufa.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.material.icons.outlined.Key
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kk.shurufa.Prefs
import com.kk.shurufa.keyboard.Key
import com.kk.shurufa.keyboard.KeyOverride
import com.kk.shurufa.keyboard.LayoutRepo
import com.kk.shurufa.keyboard.Dir
import com.kk.shurufa.rime.RimeEngine

// 设置 · 语言, 中文, 符号与数学, 剪贴板 and 键位编辑.

private val LANG_NAMES = mapOf("zh" to "中文（雾凇拼音）", "ru" to "Русский", "en" to "English")

@Composable
internal fun LanguagesPage(prefs: Prefs) {
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
        Toggle(prefs, Prefs.DOUBLE_PERIOD, "双击句号输入省略号", "中文「。」点两下变成「……」，俄语和英语「.」点两下变成「…」", true)
        Toggle(prefs, Prefs.STRESS_MODE, "重音模式", "俄语元音上滑输入带重音的字母（а́ е́ и́ …）；不开时长按也有", false)
        Toggle(prefs, Prefs.WORD_AUTOCORRECT, "自动纠错", "按空格或标点时，改掉明显按错一个键的词（teh → the），以及 i → I、dont → don't。词库里的词、用过的词和名字不改；紧接着按删除键退回原样", true)
    }
    Group("俄语和英语联想") {
        Toggle(prefs, Prefs.WORD_PREDICT, "补全和下一个词", "补全正在打的词，空格后推荐下一个词", true)
        Toggle(prefs, Prefs.WORD_LEARN, "从我打的字里学习", "记住常用的词、搭配和写法（名字、缩写、大小写），每三个月减半，不常用的会慢慢淡忘。只存在本机", true)
        val ctx = androidx.compose.ui.platform.LocalContext.current
        var cleared by remember { mutableStateOf(false) }
        TextButton(enabled = !cleared, onClick = { com.kk.shurufa.ime.WordPredictor.clearAll(ctx); cleared = true }) {
            Text(if (cleared) "已清除" else "清除学到的词")
        }
    }
}

@Composable
internal fun ChinesePage(prefs: Prefs, openDict: () -> Unit) {
    val ctx = LocalContext.current
    Group("候选") {
        Toggle(prefs, Prefs.ZH_TRAD, "输出繁体", null, false)
        Toggle(prefs, Prefs.ZH_EMOJI, "候选里带 emoji", "黄脸表情排在原位，其他 emoji 排在前五个词之后", true)
        Toggle(prefs, Prefs.ZH_PREDICT, "联想", "上屏后在候选栏推荐常接的词", true)
        Toggle(prefs, Prefs.ZH_PREDICT_LEARN, "联想记住我的习惯", "记住你在一个词后面常接什么（上屏后停留几秒、没被删掉才算），下次联想时排在最前；点选的联想词也算。每三个月减半，只存在本机，临时模式和密码框里不记", true)
        run {
            var cleared by remember { mutableStateOf(false) }
            TextButton(enabled = !cleared, onClick = { com.kk.shurufa.ime.ZhMemory.clearAll(ctx); cleared = true }) {
                Text(if (cleared) "已清除" else "清除联想记住的词")
            }
        }
        Toggle(prefs, Prefs.ZH_CORRECTION, "按键纠错", "按错相邻键、字母打反也能出正确的词；缩写和名字不纠。切换后重新部署", true)
        Toggle(prefs, Prefs.PANGU, "中英文之间自动加空格", "盘古之白：汉字和英文字母、数字相邻时自动隔开，如「用 iPhone 拍了 3 张」", false)
        Toggle(prefs, Prefs.TONE_FILTER, "声调筛选", "展开候选后按声调筛选，多个字时可以逐字挑声调", true)
        Toggle(prefs, Prefs.TYPO_MARK, "纠错批注", "纠错得到的候选后面用小字标出改了哪里，比如 p→o、no⇄on", true)
        Toggle(prefs, Prefs.RARE_ANNOTATE, "生僻字注音", "常用 3500 字以外的字，在候选后面标出读音", true)
        Note("长按候选词可以删除它的学习记录，下次同样的拼音就不会再把它排到前面。点候选栏上方的拼音，可以把光标放到那里直接改。")
    }
    Group("拼音") {
        Toggle(prefs, Prefs.PINYIN_MODE, "拼音模式", "选中候选后上屏带声调的拼音，比如 nǐ hǎo；多音字按你打的拼音判断", false)
        Toggle(prefs, Prefs.ZH_PREEDIT_INLINE, "拼音写进输入框", "打字时拼音直接出现在输入框里，键盘上就不再显示", false)
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
internal fun SymbolsPage(prefs: Prefs) {
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
internal fun ClipboardPage(prefs: Prefs) {
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

// ---- key editor ------------------------------------------------------------------------

@Composable
internal fun KeyEditorPage(prefs: Prefs) {
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
