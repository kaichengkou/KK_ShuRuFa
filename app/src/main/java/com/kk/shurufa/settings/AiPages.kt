package com.kk.shurufa.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
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
import androidx.compose.material.icons.outlined.Key
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kk.shurufa.Prefs
import com.kk.shurufa.keyboard.Key

// 设置 · AI 服务 and 小鲨鱼 (小K, 提示词, the model each AI feature uses).

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
internal fun AiServicesPage(prefs: Prefs) {
    var tick by remember { mutableIntStateOf(0) }
    Group("服务与模型") {
        Note("填哪家的 Key 就能用哪家，可以都填。每家可以“获取可用模型”，搜索并勾选启用；启用的模型都能在「AI 功能」里选给改写、翻译、云联想用。Key 只保存在这台手机上。")
        for (p in com.kk.shurufa.online.Llm.PROVIDERS) ProviderCard(prefs, p) { tick++ }
    }
    Group("用量") {
        Note("今天已请求 ${com.kk.shurufa.online.AiBudget.today(prefs)} 次（改写、翻译、云联想、AI 候选、AI 语音识别合计）。键盘自己每分钟最多问 ${com.kk.shurufa.online.AiBudget.PER_MINUTE} 次、不重试；服务说太频繁就先停一会儿，连着几次就停 30 分钟。Gemini 免费版实测每分钟 15 次（每个模型分开算），每天的上限在 AI Studio 的 rate-limit 页面能看到。国内网络连不上 Gemini 时可以选智谱 GLM-4-Flash（免费）或硅基流动的免费小模型。")
    }
}

/** 小K: its name, the line of the day, the space key, talking and what it remembers. */
@Composable
private fun KayGroups(prefs: Prefs) {
    val ctx = LocalContext.current
    val kay = remember { com.kk.shurufa.ime.Kay(ctx, prefs) }
    Group("小鲨鱼") {
        LocalPageKeys.current?.add(Prefs.KAY_NAME)
        var name by remember { mutableStateOf(prefs.kayName) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("名字", Modifier.weight(1f))
            OutlinedTextField(name, { v -> name = v.take(8); prefs.put(Prefs.KAY_NAME, name.trim()) }, singleLine = true,
                modifier = Modifier.width(150.dp), shape = RoundedCornerShape(14.dp), placeholder = { Text("小K") })
        }
        Note("住在键盘里的小鲨鱼，今天是它出生的第 ${kay.age()} 天。工具栏上那个会眨眼的就是它：点一下打开它，长按摸摸头。")
        Choice(prefs, Prefs.KAY_ICON, "工具栏上的图标", listOf("shark" to "小鲨鱼", "shark_fin" to "鱼鳍", "ai" to "小星星"))
        Toggle(prefs, Prefs.KAY_SPACE, "空格键上也有它", "在语言名字旁边，打字时也看得见。它想干嘛就干嘛：东张西望、啃一口语言名、唱歌、敲电脑……说的话在名字底下一行浅灰小字。不碰按键，不影响打字", true)
        Toggle(prefs, Prefs.KAY_GREET, "每天打个招呼", "每天第一次在空白输入框里打开键盘时，在工具栏说一句话。点「已阅」、按任何键或点别处就消失", true)
        Toggle(prefs, Prefs.KAY_AI_GREET, "这句话让 AI 来写", "每天傍晚请 AI 写好第二天的那一句（一天一次，只发日期、节日、节气和字数，不发你写的内容）。没有 AI 服务时用它自己的话", true)
    }
    Group("空格键上的它") {
        IntSlider(prefs, Prefs.KAY_ACT_EVERY, "多久动一下", 8, 3..60, " 秒", "大约每隔这么久，它自己找点事做；打不打字都一样")
        IntSlider(prefs, Prefs.KAY_LONG_SHARE, "长动作的比例", 35, 0..100, "%", "唱歌、敲电脑、晒太阳、啃小鱼干、做操。长动作做完才停，只有长按工具栏上的它（摸头）能叫停")
        IntSlider(prefs, Prefs.KAY_SPEED, "动作速度", 70, 30..150, "%", "越小越慢；删字、发消息时的反应不受影响")
        IntSlider(prefs, Prefs.KAY_SAY_EVERY, "多久说一句话", 40, 10..300, " 秒")
        IntSlider(prefs, Prefs.KAY_SAY_SEC, "一句话停留", 12, 3..60, " 秒", "长动作的话会一直留到动作做完")
        IntSlider(prefs, Prefs.KAY_SAY_SIZE, "小字大小", 100, 70..150, "%")
    }
    Group("最近的一言") {
        val log = remember { kay.log() }
        if (log.isEmpty()) Note("每天第一次打开键盘时它说的那句，会记在这里（最近 30 天）。")
        else {
            val fmt = java.time.format.DateTimeFormatter.ofPattern("M月d日")
            for ((day, line) in log.take(14)) Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
                Text(day.format(fmt) + " " + com.kk.shurufa.ime.KayLines.weekday(day.dayOfWeek), Modifier.width(92.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(line, style = MaterialTheme.typography.bodyMedium)
            }
            Note("聊天里点「一言」也能找回今天的那句。")
        }
    }
    Group("聊天") {
        Toggle(prefs, Prefs.KAY_SHOW_IP, "显示 IP 属地", "在小鲨鱼页面顶部，用小字显示你现在的网络出口在哪个国家或地区；绿点表示 Gemini 在那里能用，黄点表示不能。打开面板时查一次（最多每 5 分钟一次，只问 Cloudflare，不带任何输入内容），点一下重新查", true)
        IntSlider(prefs, Prefs.KAY_CHAT_CAP, "每天最多聊", 30, 5..100, " 句", "聊天会把你说的话发给 AI 服务；到了上限它就去睡了。聊过的话不保存，键盘关掉就忘了")
    }
}

/** 提示词: what 小鲨鱼 is told, in the person's own words if they want (blank: the built-in ones). */
@Composable
private fun PromptGroups(prefs: Prefs) {
    val name = prefs.kayName
    Group("提示词") {
        Note("小鲨鱼跟 AI 说的「人设和规矩」。留空就用自带的；写了就用你的。可以用 {名字} 和 {天数}，会换成它的名字和出生天数。")
        for ((key, title, def) in listOf(
            Triple(Prefs.KAY_PROMPT_CHAT, "聊天", com.kk.shurufa.ime.KayLines.chatSystem(name, 1)),
            Triple(Prefs.KAY_PROMPT_GREET, "每日一言", com.kk.shurufa.ime.KayLines.greetSystem(name)),
            Triple(Prefs.KAY_PROMPT_CHATTER, "主页碎碎念", com.kk.shurufa.ime.KayLines.chatterSystem(name)))) {
            LocalPageKeys.current?.add(key)
            var text by remember { mutableStateOf(prefs.sp.getString(key, "") ?: "") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), fontWeight = FontWeight.Medium)
                if (text.isBlank()) TextButton(onClick = { text = def; prefs.put(key, def) }) { Text("在自带的上改") }
                else TextButton(onClick = { text = ""; prefs.put(key, "") }) { Text("恢复自带") }
            }
            OutlinedTextField(text, { v -> text = v; prefs.put(key, v) }, modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 12,
                shape = RoundedCornerShape(14.dp), textStyle = MaterialTheme.typography.bodySmall,
                placeholder = { Text("（用自带的）", style = MaterialTheme.typography.bodySmall) })
        }
    }
}

/** 用 AI 做什么: each feature and the model it uses. The services themselves are on [AiServicesPage]. */
@Composable
internal fun OnlinePage(prefs: Prefs, openServices: () -> Unit) {
    var tick by remember { mutableIntStateOf(0) }
    val targets = remember(tick) { com.kk.shurufa.online.Llm.targets(prefs) }
    var pickAi by remember { mutableStateOf(false) }
    var pickTr by remember { mutableStateOf(false) }
    if (targets.isEmpty()) Group("还没有 AI 服务") {
        Note("改写、云联想和 AI 候选要先在「AI 服务」里填一个 Key、启用一个模型；翻译不填也能用离线引擎。")
        Button(onClick = openServices, modifier = Modifier.padding(vertical = 4.dp)) { Text("去填 Key") }
    } else Group("AI 服务") {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("已启用 ${targets.size} 个模型，下面每项可以分别选", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = openServices) { Text("管理") }
        }
    }
    KayGroups(prefs)
    Group("帮写") {
        if (targets.isEmpty()) Note("没有可用的模型。")
        else {
            val cur = com.kk.shurufa.online.Llm.resolve(prefs, prefs.aiProvider)
            PickRow("使用", cur?.let { com.kk.shurufa.online.Llm.targetLabel(prefs, it) } ?: "未选择") { pickAi = true }
            if (pickAi) PickerDialog("帮写使用", targets, cur, onPick = { prefs.put(Prefs.AI_PROVIDER, it); tick++ }, onClose = { pickAi = false })
        }
        Note("点工具栏上的小鲨鱼，选「改写」：润色、纠错、正式、口语、精简、扩写，加上你自己的改法。上方条的模型按钮可以临时换模型。")
    }
    Group("思考深度") {
        Choice(prefs, Prefs.AI_THINKING, "模型想多久再回答", listOf("off" to "不思考", "low" to "浅", "medium" to "中", "high" to "深"))
        Note("默认不思考：回答最快、最省额度。有的模型必须思考，就用它允许的最浅一档。调深一些，聊天里的难题、长文改写会更周到，但要多等几秒。对所有 AI 服务都有效。")
    }
    Group("改写的选项") {
        LocalPageKeys.current?.add(Prefs.KAY_BASE_ACTIONS)
        var base by remember { mutableStateOf(prefs.kayBaseActions ?: com.kk.shurufa.online.AiWriter.baseText) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("名称：要怎么改", Modifier.weight(1f), fontWeight = FontWeight.Medium)
            if (prefs.kayBaseActions != null) TextButton(onClick = {
                prefs.sp.edit().remove(Prefs.KAY_BASE_ACTIONS).apply(); base = com.kk.shurufa.online.AiWriter.baseText
            }) { Text("恢复自带") }
        }
        OutlinedTextField(base, { v -> base = v; prefs.put(Prefs.KAY_BASE_ACTIONS, v) }, modifier = Modifier.fillMaxWidth(), minLines = 4, maxLines = 14,
            shape = RoundedCornerShape(14.dp), textStyle = MaterialTheme.typography.bodySmall)
        Note("一行一个，冒号前是条子上显示的名字，冒号后告诉 AI 怎么改。可以改、删、调顺序，也可以加新的。")
    }
    Group("我自己的改法") {
        LocalPageKeys.current?.add(Prefs.KAY_CUSTOM_ACTIONS)
        var text by remember { mutableStateOf(prefs.sp.getString(Prefs.KAY_CUSTOM_ACTIONS, "") ?: "") }
        OutlinedTextField(text, { v -> text = v; prefs.put(Prefs.KAY_CUSTOM_ACTIONS, v) }, modifier = Modifier.fillMaxWidth(), minLines = 3,
            shape = RoundedCornerShape(14.dp), placeholder = { Text("一行一条，比如：\n改成朋友圈文案\n翻成东北话\n语气软一点，加个表情") })
        Note("每一行是一种改法，排在上面那些后面。在上方条里点「✎ 我来说」直接打一句新的，也会记到这里，一直用到你换掉；条子上点它后面的 ✕ 就删掉。")
    }
    PromptGroups(prefs)
    Group("AI 帮打字") {
        LocalPageKeys.current?.add(Prefs.AI_ASSIST)
        var mode by remember { mutableStateOf(prefs.aiAssist) }
        val options = listOf("off" to "关", "cloud" to "云联想", "candidate" to "AI 候选")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("两种只能开一种")
            ResetMark(mode != "off") { mode = "off"; prefs.put(Prefs.AI_ASSIST, "off") }
        }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            options.forEachIndexed { i, (id, name) ->
                SegmentedButton(selected = mode == id, onClick = { mode = id; prefs.put(Prefs.AI_ASSIST, id) },
                    shape = SegmentedButtonDefaults.itemShape(i, options.size)) { Text(name, maxLines = 1) }
            }
        }
        when (mode) {
            "cloud" -> {
                Note("打字停顿时，把光标前的字发给 AI，它给的几种接法显示出来。临时模式、密码和地址栏里不发。")
                IntSlider(prefs, Prefs.CLOUD_PAUSE, "停顿多久再问", 300, 150..1500, " 毫秒", "短一些来得快，长一些问得少、省电也省额度")
                Toggle(prefs, Prefs.AI_UNMETERED, "只在 Wi-Fi 下问", "用流量时不发请求：移动网络每次联网都要唤醒基带，比键盘本身耗电得多", false)
                IntSlider(prefs, Prefs.CLOUD_CONTEXT, "带上前文", 80, 20..200, " 字", "越多越懂上下文，也发出去越多")
                IntSlider(prefs, Prefs.CLOUD_COUNT, "给几种接法", 3, 1..5, " 种")
            }
            "candidate" -> {
                Note("打长拼音停顿时，把拼音和前面几个字发给 AI，它给出一个整句。密码和地址栏里不发。")
                IntSlider(prefs, Prefs.AI_CAND_SYLLABLES, "几个音节起问", 3, 2..8, " 个")
                IntSlider(prefs, Prefs.AI_CAND_PAUSE, "停顿多久再问", 450, 200..1500, " 毫秒")
                Toggle(prefs, Prefs.AI_UNMETERED, "只在 Wi-Fi 下问", "用流量时不发请求：移动网络每次联网都要唤醒基带，比键盘本身耗电得多", false)
                IntSlider(prefs, Prefs.AI_CAND_CONTEXT, "带上前文", 20, 0..80, " 字")
            }
        }
        if (mode != "off") {
            Choice(prefs, Prefs.AI_PLACE, "显示在", listOf("float" to "键盘右上角", "bar" to "候选栏里"), def = "float")
            if (targets.isNotEmpty()) {
                var pickQuick by remember { mutableStateOf(false) }
                val q = prefs.quickTarget.takeIf { it.isNotEmpty() }?.let { com.kk.shurufa.online.Llm.resolve(prefs, it) }
                PickRow("使用", q?.let { com.kk.shurufa.online.Llm.targetLabel(prefs, it) } ?: "跟 AI 改写相同") { pickQuick = true }
                if (pickQuick) PickerDialog("AI 帮打字使用", listOf("" to "跟 AI 改写相同") + targets, prefs.quickTarget,
                    onPick = { prefs.put(Prefs.QUICK_TARGET, it); tick++ }, onClose = { pickQuick = false })
            }
            Note("今天已请求 ${com.kk.shurufa.online.AiBudget.today(prefs)} 次。每分钟最多 ${com.kk.shurufa.online.AiBudget.PER_MINUTE} 次，额度说明见「AI 服务」。")
        }
    }
    Group("翻译") {
        val engines = listOf("auto" to "自动（有 AI 用 AI，否则离线）", "mlkit" to "离线（ML Kit）") + targets
        val curE = prefs.translateEngine
        PickRow("翻译引擎", engines.firstOrNull { it.first == curE }?.second ?: com.kk.shurufa.online.Llm.targetLabel(prefs, curE)) { pickTr = true }
        if (pickTr) PickerDialog("翻译引擎", engines, curE, onPick = { prefs.put(Prefs.TRANSLATE_ENGINE, it); tick++ }, onClose = { pickTr = false })
        Choice(prefs, Prefs.TRANSLATE_TARGET, "默认翻译成", com.kk.shurufa.online.TranslateService.LANGS.take(3))
        IntSlider(prefs, Prefs.TRANSLATE_PAUSE, "AI 翻译：停顿多久再译", 1200, 300..3000, " 毫秒", "边打边译时，停下这么久才发一次请求；打完一句（。！？）立刻译，按上屏也立刻译。离线翻译不受影响，一直是即时的")
        Toggle(prefs, Prefs.TRANSLATE_WIFI, "离线模型只在 Wi-Fi 下载", "每种语言约 30 MB，下载一次后离线翻译", false)
        Note("点工具栏的翻译按钮，键盘上方会展开翻译栏：可以选语言、⇄ 对调、切换引擎；选中文字时直接翻译并可替换。AI 翻译更地道，离线翻译不联网也能用。")
    }
}
