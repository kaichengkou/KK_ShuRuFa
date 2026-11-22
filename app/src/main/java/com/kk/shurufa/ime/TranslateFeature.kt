package com.kk.shurufa.ime

import android.os.Handler
import android.os.Looper
import android.view.View
import com.kk.shurufa.Prefs
import com.kk.shurufa.keyboard.Motion
import com.kk.shurufa.keyboard.PickList
import com.kk.shurufa.keyboard.TranslateStrip
import com.kk.shurufa.online.Llm
import com.kk.shurufa.online.TranslateService

/**
 * 翻译: like 表情查找, the keyboard types into the query; the strip above the bar shows the
 * translation as it arrives (or of the selection, right away), and 上屏 / 替换 sends it.
 */
class TranslateFeature(private val host: Host, private val service: TranslateService) {
    private val prefs get() = host.prefs
    private val handler = Handler(Looper.getMainLooper())
    private val run = Runnable { translate() }

    private var to = "en"
    private var from = "auto"
    private var fromSelection = false
    private var result = ""
    private var resultFor = ""

    lateinit var strip: TranslateStrip
        private set

    fun build(): View {
        strip = TranslateStrip(host.context, prefs).apply {
            theme = host.kbTheme
            visibility = View.GONE
            languages = TranslateService.LANGS
            onFeedback = { host.haptics.play(HapticEvent.FUNCTION) }
            onClose = { host.closeSink() }
            onCommit = { commit() }
            onInsert = {
                val (t, field) = insertable()
                if (t.isNotEmpty()) {
                    // taken from the field: the translation will replace it there
                    fromField = field
                    host.query.setLength(0); host.query.append(t); sync()
                }
            }
            onSwap = {
                val src = if (from == "auto") TranslateService.guess(host.query.toString()) else from
                from = to; to = src
                // the previous result becomes the text to translate back
                if (result.isNotEmpty() && !fromSelection) { host.query.setLength(0); host.query.append(result) }
                resultFor = ""; sync()
            }
            onPick = { which, value ->
                when (which) {
                    "from" -> from = value
                    "engine" -> prefs.put(Prefs.TRANSLATE_ENGINE, value)
                    else -> { to = value; prefs.put(Prefs.TRANSLATE_TARGET, value) }
                }
                resultFor = ""; sync()
            }
            onEngine = {
                if (Llm.targets(prefs).isEmpty()) host.notice("在 设置 → AI 服务 里填好 Key 后，可以用 AI 翻译")
                sync()
            }
        }
        return strip
    }

    /** The translate sink opened: the selection (if any) is the text, shown right away. */
    fun onOpened(selection: String?) {
        to = prefs.translateTarget
        from = "auto"
        fromSelection = selection != null
        fromField = ""
        result = ""; resultFor = ""
        strip.visibility = View.VISIBLE
        if (Motion.on(prefs)) {
            strip.alpha = 0f; strip.translationY = strip.resources.displayMetrics.density * 24
            strip.animate().alpha(1f).translationY(0f).setDuration(Motion.dur(prefs, Motion.PANEL))
                .setInterpolator(Motion.ENTER).withLayer().start()
        }
        sync()
    }

    fun onClosed() {
        service.cancel()
        handler.removeCallbacks(run)
        if (::strip.isInitialized) strip.visibility = View.GONE
    }

    /** Updates the strip; a changed text is translated after a short pause in typing. */
    fun sync() {
        if (host.sink != Sink.TRANSLATE) return
        val q = host.query.toString()
        val st = strip
        val src = if (from == "auto") TranslateService.guess(q) else from
        if (src == to) to = if (src == "zh") "en" else "zh"
        st.from = from; st.to = to
        st.fromName = if (from == "auto") (if (q.isBlank()) "自动" else "自动·" + TranslateService.name(src)) else TranslateService.name(from)
        st.toName = TranslateService.name(to)
        st.engineName = service.engineName()
        st.engine = service.engine
        st.engines = listOf(PickList.Item("mlkit", "离线翻译", "ML Kit")) + aiItems(prefs, Llm.targets(prefs))
        st.source = q; st.sourceIsSelection = fromSelection
        if (q.isEmpty()) fromField = ""
        st.insertable = if (q.isEmpty() && !fromSelection) insertable().first else ""
        st.replaces = fromSelection || fromField.isNotEmpty()
        val fresh = resultFor == q
        if (!fresh) { st.result = ""; st.busy = q.isNotBlank() }
        if (q.isBlank()) st.status = ""
        if (!fresh && q.isNotBlank()) { handler.removeCallbacks(run); handler.postDelayed(run, if (fromSelection) 0 else 400) }
        st.refresh()
    }

    private fun translate() {
        val q = host.query.toString()
        if (host.sink != Sink.TRANSLATE || q.isBlank()) return
        service.translate(q, from, to, onPartial = { t ->
            if (host.query.toString() == q) { strip.result = t; strip.refresh() }
        }) { r ->
            if (host.sink != Sink.TRANSLATE || host.query.toString() != q) return@translate
            resultFor = q
            r.onSuccess { result = it; strip.result = it; strip.status = "" }
             .onFailure { result = ""; strip.result = ""; strip.status = it.message ?: "翻译失败" }
            strip.busy = false
            strip.refresh()
        }
    }

    /**
     * What 一键插入 puts in the box: what was just copied (a message to answer, most often), or
     * else what is already written in the field.
     */
    private fun insertable(): Pair<String, String> {
        val cm = host.context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = runCatching { cm.primaryClip?.getItemAt(0)?.coerceToText(host.context)?.toString() }.getOrNull()?.trim().orEmpty()
        if (clip.isNotEmpty() && clip.length <= 2000) return clip to ""
        val raw = host.editor.textBefore(500)
        return raw.trim() to raw
    }
    /** What 一键插入 took from the field (before the caret), to be replaced by the translation. */
    private var fromField = ""

    /** ↵ or 上屏: sends the translation when it is for the text as it is now. */
    fun commit() {
        val t = result
        if (t.isEmpty() || resultFor != host.query.toString()) return
        val field = fromField
        host.closeSink()
        // what was taken from the field gives way to its translation
        if (field.isNotEmpty() && host.editor.textBefore(field.length) == field) host.editor.replaceBefore(field.length, field.takeWhile { it.isWhitespace() })
        // a selection being translated is replaced: committing over it does that
        host.editor.type(t, pairable = false, source = TypingStats.Source.AI)
    }
}

/** Services' models as list rows: the short model name, and whose it is. */
internal fun aiItems(prefs: Prefs, targets: List<Pair<String, String>>) = targets.map { (t, _) ->
    PickList.Item(t, Llm.targetName(prefs, t), Llm.provider(Llm.targetId(t)).title)
}
