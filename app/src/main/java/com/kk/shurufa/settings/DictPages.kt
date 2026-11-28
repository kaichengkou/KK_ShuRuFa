package com.kk.shurufa.settings

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kk.shurufa.Prefs
import com.kk.shurufa.rime.RimeEngine

// 设置 · 扩展词库 (语法模型, 词库订阅, 导入, 雾凇词库更新) and 自定义词库.

@Composable
internal fun PacksPage(prefs: Prefs) {
    GrammarGroup(prefs)
    PackGroups(prefs)
    ImportGroup()
    DictUpdateGroup()
    Group("古诗文") {
        Toggle(prefs, Prefs.PACK_POETRY, "启用古诗文词库", "唐诗宋词、诗经、论语、古文观止等约 3.8 万句，首字母也能打出整句（brysj → 白日依山尽）。切换后重新部署", true)
        Note("数据来自 chinese-poetry 项目（MIT 许可）。")
    }
}

/** 语法模型: download, turn on or off, delete. */
@Composable
private fun GrammarGroup(prefs: Prefs) {
    val ctx = LocalContext.current
    val main = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    var installed by remember { mutableStateOf(com.kk.shurufa.rime.Grammar.installed(ctx)) }
    var progress by remember { mutableStateOf(-1f) }
    var status by remember { mutableStateOf("") }
    var cancel by remember { mutableStateOf(false) }
    Group("语法模型") {
        Note("让整句输入看词语之间怎么搭配，而不只看每个词常不常用，长句一次打对的次数会多很多。模型来自万象（RIME-LMDG），约 ${com.kk.shurufa.rime.Grammar.SIZE_MB} MB，建议在 Wi-Fi 下下载；用的时候直接从存储读取，不占用多少内存。")
        if (installed) {
            Toggle(prefs, Prefs.GRAMMAR, "使用语法模型", "切换后会重新部署", true)
            TextButton(onClick = {
                com.kk.shurufa.rime.Grammar.delete(ctx); installed = false; RimeEngine.redeploy(ctx); status = "已删除"
            }) { Text("删除模型") }
        } else if (progress >= 0f) {
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("已下载 ${(progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { cancel = true }) { Text("暂停") }
            }
        } else {
            Button(onClick = {
                cancel = false; progress = 0f; status = ""
                Thread {
                    val r = runCatching { com.kk.shurufa.rime.Grammar.download(ctx, { f -> main.post { progress = f } }, { cancel }) }
                    main.post {
                        progress = -1f
                        installed = com.kk.shurufa.rime.Grammar.installed(ctx)
                        status = when {
                            installed -> { RimeEngine.redeploy(ctx); "已下载，正在重新部署（约一分钟，期间中文暂不可用）" }
                            cancel -> "已暂停，再点下载会接着下"
                            else -> "下载失败：${r.exceptionOrNull()?.message ?: "网络错误"}，再点下载会接着下"
                        }
                    }
                }.start()
            }, modifier = Modifier.padding(vertical = 6.dp)) { Text("下载（约 ${com.kk.shurufa.rime.Grammar.SIZE_MB} MB）") }
        }
        if (status.isNotEmpty()) Note(status)
    }
}

/**
 * 词库拓展包: the built-in catalog and every subscribed one, by subject. A pack installs with one
 * tap and is removed the same way; the engine redeploys once after a run of changes.
 */
@Composable
private fun PackGroups(prefs: Prefs) {
    val ctx = LocalContext.current
    val main = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    val builtIn = remember { com.kk.shurufa.rime.DictPacks.builtIn(ctx) }
    var feeds by remember { mutableStateOf(com.kk.shurufa.rime.DictPacks.subscriptions(prefs)) }
    val fetched = remember { mutableStateOf(mapOf<String, Result<com.kk.shurufa.rime.DictPacks.Catalog>>()) }
    var installed by remember { mutableStateOf(com.kk.shurufa.rime.ImportedDicts.list(ctx)) }
    var busy by remember { mutableStateOf(setOf<String>()) }
    var status by remember { mutableStateOf("") }
    var open by remember { mutableStateOf<String?>(null) }
    // subscribed catalogs are read once per visit
    LaunchedEffect(feeds) {
        for (f in feeds) if (f !in fetched.value) Thread {
            val r = runCatching { com.kk.shurufa.rime.DictPacks.fetch(f) }
            main.post { fetched.value = fetched.value + (f to r) }
        }.start()
    }
    fun install(p: com.kk.shurufa.rime.DictPacks.Pack) {
        busy = busy + p.id; status = "正在下载「${p.name}」…"
        Thread {
            val r = runCatching { com.kk.shurufa.rime.DictPacks.install(ctx, p) }
            main.post {
                busy = busy - p.id; installed = com.kk.shurufa.rime.ImportedDicts.list(ctx)
                status = r.fold({ n -> RimeEngine.redeploySoon(ctx); "「${p.name}」装好了，$n 个词；稍后重新部署（约一分钟）" }, { "「${p.name}」安装失败：${it.message ?: "网络错误"}" })
            }
        }.start()
    }
    fun remove(p: com.kk.shurufa.rime.DictPacks.Pack) {
        installed.filter { it.source == p.id }.forEach { com.kk.shurufa.rime.ImportedDicts.delete(it) }
        installed = com.kk.shurufa.rime.ImportedDicts.list(ctx)
        RimeEngine.redeploySoon(ctx); status = "已移除「${p.name}」"
    }
    @Composable
    fun Catalog(c: com.kk.shurufa.rime.DictPacks.Catalog) {
        if (c.note.isNotEmpty()) Note(c.note)
        for ((group, packs) in c.packs.groupBy { it.group }) {
            val key = c.source + "/" + group
            val have = packs.count { p -> installed.any { it.source == p.id } }
            Row(Modifier.fillMaxWidth().clickable { open = if (open == key) null else key }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(group, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Text(if (have > 0) "已装 $have / ${packs.size}" else "${packs.size} 个", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Icon(if (open == key) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, null, modifier = Modifier.padding(start = 4.dp))
            }
            if (open == key) for (p in packs) Row(Modifier.fillMaxWidth().padding(start = 8.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(p.name)
                    if (p.words > 0) Text("${p.words} 个词", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                when {
                    p.id in busy -> Text("下载中…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 12.dp))
                    installed.any { it.source == p.id } -> TextButton(onClick = { remove(p) }) { Text("移除") }
                    else -> FilledTonalButton(onClick = { install(p) }) { Text("安装") }
                }
            }
        }
    }
    Group("词库拓展包") {
        Note("按需装上专业或兴趣词库，装好的词和雾凇拼音一起组句，常用词的排序不受影响。")
        Catalog(builtIn)
        if (status.isNotEmpty()) Note(status)
    }
    for (f in feeds) Group(fetched.value[f]?.getOrNull()?.name ?: "订阅的词库") {
        val r = fetched.value[f]
        when {
            r == null -> Note("正在读取 $f …")
            r.isFailure -> Note("读取失败：${r.exceptionOrNull()?.message ?: "网络错误"}（$f）")
            else -> Catalog(r.getOrThrow())
        }
        TextButton(onClick = {
            feeds = feeds - f; com.kk.shurufa.rime.DictPacks.setSubscriptions(prefs, feeds)
        }) { Text("取消订阅") }
    }
    Group("订阅词库源") {
        Note("词库源是一个 JSON 文件，列出一批可以一键安装的词库，格式见下面的说明。订阅后它的词库会出现在上面。")
        var link by remember { mutableStateOf("") }
        OutlinedTextField(link, { link = it }, label = { Text("词库源链接") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(enabled = link.trim().startsWith("http"), onClick = {
            feeds = feeds + link.trim(); com.kk.shurufa.rime.DictPacks.setSubscriptions(prefs, feeds); link = ""
        }, modifier = Modifier.padding(vertical = 6.dp)) { Text("订阅") }
        Note("""格式：{"name": "名字", "packs": [{"id": "唯一编号", "group": "分类", "name": "词库名", "words": 词数, "url": "下载链接"}]}。下载链接可以是搜狗 .scel、文本词表或 Rime 的 .dict.yaml；id 写成 sogou:编号 时可以不写链接。""")
    }
}

/** 导入词库: add .scel or text word lists; each can be removed again. */
@Composable
private fun ImportGroup() {
    val ctx = LocalContext.current
    val main = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    var dicts by remember { mutableStateOf(com.kk.shurufa.rime.ImportedDicts.list(ctx)) }
    var status by remember { mutableStateOf("") }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        status = "正在导入…"
        Thread {
            val r = runCatching {
                val name = ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "导入的词库"
                val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("读不到文件")
                com.kk.shurufa.rime.ImportedDicts.import(ctx, name.substringBeforeLast('.'), bytes)
            }
            main.post {
                dicts = com.kk.shurufa.rime.ImportedDicts.list(ctx)
                status = r.fold({ n -> RimeEngine.redeploySoon(ctx); "导入了 $n 个词，稍后重新部署（约一分钟）" }, { "导入失败：${it.message}" })
            }
        }.start()
    }
    Group("导入词库") {
        Note("也可以导入自己的词库文件。支持搜狗细胞词库（.scel）和文本词表（每行一个词，可以带拼音和词频，用 Tab 或空格隔开）。导入的词和雾凇拼音一起组句；没写拼音的词按字自动注音。导入词的词频有上限，只补充新词，不会把常用词挤到后面。")
        for (d in dicts) Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(d.title)
                Text("${d.words} 个词", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = {
                com.kk.shurufa.rime.ImportedDicts.delete(d); dicts = com.kk.shurufa.rime.ImportedDicts.list(ctx)
                RimeEngine.redeploySoon(ctx); status = "已移除「${d.title}」，稍后重新部署"
            }) { Text("移除") }
        }
        FilledTonalButton(onClick = { pick.launch(arrayOf("*/*")) }, modifier = Modifier.padding(vertical = 6.dp)) { Text("选择词库文件") }
        var link by remember { mutableStateOf("") }
        OutlinedTextField(link, { link = it }, label = { Text("或者贴一个链接") }, placeholder = { Text("搜狗词库页面，或 .scel / 文本的下载链接") },
            singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
        val url = com.kk.shurufa.rime.DictPacks.resolve(link)
        Button(enabled = url != null, onClick = {
            val u = url ?: return@Button
            status = "正在下载…"
            Thread {
                val r = runCatching {
                    val bytes = com.kk.shurufa.rime.DictPacks.download(u)
                    val title = if (com.kk.shurufa.rime.ImportedDicts.Scel.isScel(bytes)) com.kk.shurufa.rime.ImportedDicts.Scel.title(bytes) else u.substringAfterLast('/').substringBefore('?')
                    com.kk.shurufa.rime.ImportedDicts.import(ctx, title.ifBlank { "链接导入的词库" }, bytes, source = u) to title
                }
                main.post {
                    dicts = com.kk.shurufa.rime.ImportedDicts.list(ctx)
                    status = r.fold({ (n, t) -> RimeEngine.redeploySoon(ctx); link = ""; "导入了「$t」$n 个词，稍后重新部署（约一分钟）" }, { "导入失败：${it.message ?: "网络错误"}" })
                }
            }.start()
        }, modifier = Modifier.padding(vertical = 6.dp)) { Text("从链接导入") }
        if (status.isNotEmpty()) Note(status)
    }
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

/** 不再出现: the words taken off the candidates from the long-press menu, each can come back. */
@Composable
private fun MutedGroup() {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    var list by remember { mutableStateOf(com.kk.shurufa.ime.Muted.all(ctx).sorted()) }
    Group("不再出现的词") {
        if (list.isEmpty()) Note("长按候选词，选「不再出现」，它就不会再出现在候选和联想里。")
        list.forEach { w ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(w, Modifier.weight(1f))
                TextButton(onClick = {
                    com.kk.shurufa.ime.Muted.remove(ctx, w); list = com.kk.shurufa.ime.Muted.all(ctx).sorted()
                    prefs.put(Prefs.MUTED_STAMP, System.currentTimeMillis())
                }) { Text("恢复") }
            }
        }
    }
}

@Composable
internal fun DictPage() {
    val ctx = LocalContext.current
    var data by remember { mutableStateOf(readPhrases(ctx)) }
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<Pair<Int, Phrase>?>(null) }
    var dirty by remember { mutableStateOf(false) }
    var askLeave by remember { mutableStateOf(false) }
    fun save() { writePhrases(ctx, data.first, data.second); RimeEngine.redeploy(ctx); dirty = false }
    androidx.compose.runtime.DisposableEffect(dirty) {
        LeaveGuard.ask = if (dirty) ({ askLeave = true }) else null
        onDispose { LeaveGuard.ask = null }
    }
    if (askLeave) AlertDialog(
        onDismissRequest = { askLeave = false },
        title = { Text("还没保存") },
        text = { Text("改过的词还没保存，现在离开就不算数了。") },
        confirmButton = { TextButton(onClick = { askLeave = false; save(); LeaveGuard.ask = null; LeaveGuard.leave() }) { Text("保存并离开") } },
        dismissButton = {
            Row {
                TextButton(onClick = { askLeave = false; dirty = false; LeaveGuard.ask = null; LeaveGuard.leave() }) { Text("不保存", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = { askLeave = false }) { Text("继续改") }
            }
        },
    )
    Group {
        Note("这里的词会在打对应编码时排在最前面。编码是不带声调、不加空格的拼音，比如“kkdz”可以对应你的地址。保存后会重新部署，几秒到十几秒后生效。")
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(query, { query = it }, Modifier.weight(1f), placeholder = { Text("搜索词或编码") }, singleLine = true, shape = RoundedCornerShape(14.dp))
            IconButton(onClick = { editing = -1 to Phrase("", "", "99") }) { Icon(Icons.Filled.Add, "添加") }
        }
        if (dirty) Button(onClick = { save() },
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
    MutedGroup()
}

/** 雾凇词库更新: check and download the latest rime-ice dictionaries. */
@Composable
private fun DictUpdateGroup() {
    val ctx = LocalContext.current
    val main = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    Group("雾凇词库更新") {
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
