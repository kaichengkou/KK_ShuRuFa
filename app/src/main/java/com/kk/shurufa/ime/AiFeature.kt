package com.kk.shurufa.ime

import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import com.kk.shurufa.Prefs
import com.kk.shurufa.keyboard.AiPanel
import com.kk.shurufa.keyboard.KayHome
import com.kk.shurufa.keyboard.Motion
import com.kk.shurufa.keyboard.SharkLife
import com.kk.shurufa.online.AiWriter
import com.kk.shurufa.online.Llm

/**
 * 小K's side of the keyboard. Its page (the 小K key): 小K large, its line of the day, and 改写 ·
 * 翻译 · 聊天 to choose from; a choice opens 小K's strip above the keyboard ([TranslateFeature]),
 * which asks this for a rewrite or for 小K's answer. The whole conversation is on a page of its
 * own (看全部), reached from the strip or from 小K's page.
 */
class AiFeature(private val host: Host, private val kay: Kay) {
    private val prefs get() = host.prefs
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var root: FrameLayout
    private lateinit var home: KayHome
    private lateinit var panel: AiPanel
    /** Each run has a number; answers of older runs are dropped. */
    @Volatile private var run = 0

    val kayName get() = kay.name

    fun build(): View {
        root = FrameLayout(host.context)
        home = KayHome(host.context, prefs).apply {
            theme = host.kbTheme
            onFeedback = { host.haptics.play(HapticEvent.FUNCTION) }
            onPoke = { host.haptics.play(HapticEvent.OPTION) }
            onChoose = { id ->
                when (id) {
                    "setup" -> host.openSettings("ai")
                    "all" -> showConversation()
                    else -> host.openStrip(id)
                }
            }
        }
        panel = AiPanel(host.context, prefs).apply {
            theme = host.kbTheme
            visibility = View.GONE
            actions = emptyList()
            chatTab = CHAT
            onFeedback = { host.haptics.play(HapticEvent.FUNCTION) }
            onButton = { b ->
                when (b) {
                    "setup" -> host.openSettings("ai")
                    "home" -> showHome(animate = true)
                    "place" -> showPlace(force = true)
                    "line" -> { recallLine(); syncChat() }
                    "clear" -> { forget(); syncChat() }
                    "reply" -> host.openStrip("chat")
                    "stop" -> { cancel(); syncChat() }
                    else -> if (b.startsWith("target:")) { prefs.put(Prefs.AI_PROVIDER, b.removePrefix("target:")); syncHeader() }
                }
            }
            selectGroup(CHAT)
        }
        root.addView(home, FrameLayout.LayoutParams(-1, -1))
        root.addView(panel, FrameLayout.LayoutParams(-1, -1))
        return root
    }

    /** 小K's page opened (the 小K key). */
    fun onOpened() {
        host.finishComposing()
        showHome(animate = false)
        home.entered()
        // 小K says hello when it has not for a while
        val now = Motion.now()
        if (now - lastWave > 10 * 60_000L) { SharkLife.greet(now); lastWave = now }
    }
    private var lastWave = 0L

    private fun showHome(animate: Boolean) {
        home.kayName = kay.name
        home.line = kay.todayLine()
        home.aiReady = target() != null
        home.talks = kay.talk.size
        home.provider = target()?.let { Llm.targetName(prefs, it) }.orEmpty()
        home.extraLines = kay.chatter()
        kay.prefetchChatter { home.extraLines = kay.chatter() }
        showPlace(force = false)
        crossfade(panel, home, animate)
        if (animate) home.entered()
    }

    /** The whole conversation, on 小K's page. */
    fun showConversation() {
        if (host.panel != Panel.AI) host.show(Panel.AI)
        panel.kayName = kay.name
        syncHeader(); syncChat()
        crossfade(home, panel, animate = true)
    }

    /** [from] fades out as [to] fades in, rising a little. */
    private fun crossfade(from: View, to: View, animate: Boolean) {
        if (to.visibility == View.VISIBLE && from.visibility != View.VISIBLE) return
        from.animate().cancel(); to.animate().cancel()
        if (!animate || !Motion.on(prefs)) { from.visibility = View.GONE; to.visibility = View.VISIBLE; to.alpha = 1f; to.translationY = 0f; return }
        val d = Motion.dur(prefs, Motion.PANEL)
        to.visibility = View.VISIBLE; to.alpha = 0f; to.translationY = host.context.resources.displayMetrics.density * 12
        to.animate().alpha(1f).translationY(0f).setDuration(d).setInterpolator(Motion.ENTER).withLayer().start()
        from.animate().alpha(0f).setDuration(d / 2).setInterpolator(Motion.EXIT).withLayer()
            .withEndAction { from.visibility = View.GONE; from.alpha = 1f }.start()
    }

    /** IP 属地: looked up when the page opens (at most every few minutes), or on a tap. */
    private fun showPlace(force: Boolean) {
        if (!prefs.kayShowIp) { panel.place = null; home.place = null; return }
        fun put() {
            val l = com.kk.shurufa.online.IpPlace.label(); val ok = com.kk.shurufa.online.IpPlace.geminiOk()
            panel.place = l; panel.placeOk = ok; home.place = l; home.placeOk = ok
            panel.invalidate(); home.invalidate()
        }
        put()
        com.kk.shurufa.online.IpPlace.refresh(force) { put() }
    }

    /** The service and model 小K uses ("id|model"), or null when none is set up. */
    private fun target(): String? = Llm.resolve(prefs, prefs.aiProvider)

    private fun syncHeader() {
        val t = target()
        panel.needsSetup = t == null
        panel.targets = aiItems(prefs, Llm.targets(prefs))
        panel.currentTarget = t.orEmpty()
        panel.provider = if (t == null) "" else Llm.targetName(prefs, t)
        panel.invalidate()
    }

    /** 改写's changes, marked against what it was. */
    fun marked(s: CharSequence, base: String?): CharSequence = if (::panel.isInitialized) panel.marked(s.toString(), base) else s

    // ---- 改写 ----

    /** Rewrites [src] the way [a] says; [onText] as it streams, [onDone] at the end (on the main thread). */
    fun rewrite(a: AiWriter.Action, src: String, onText: (String) -> Unit, onDone: (Result<String>) -> Unit) {
        val t = target() ?: return onDone(Result.failure(IllegalStateException("先在设置里添加 AI 服务")))
        val id = ++run
        SharkLife.thinking = true
        Thread {
            com.kk.shurufa.online.AiBudget.asked(prefs)
            val r = runCatching {
                Llm.completeTarget(prefs, t, AiWriter.SYSTEM, AiWriter.prompt(a, src),
                    onText = { s -> handler.post { if (id == run) onText(s) } }, cancelled = { id != run })
            }
            handler.post {
                if (id != run) return@post
                SharkLife.thinking = false
                onDone(r)
                host.haptics.play(HapticEvent.GESTURE)
            }
        }.apply { isDaemon = true; name = "kk-ai" }.start()
    }

    // ---- 聊天 ----

    @Volatile var chatBusy = false; private set
    private var pendingSaid = ""
    private var pendingAnswer = ""

    /** 小K's latest answer in the conversation. */
    fun lastAnswer(): String = kay.talk.lastOrNull { !it.mine }?.text.orEmpty()

    /** 一言: today's line comes back into the conversation (once, at the bottom). */
    fun recallLine() {
        val l = kay.todayLine()
        if (kay.talk.lastOrNull()?.let { !it.mine && it.text == l } != true) kay.talk += Kay.Turn(false, l)
    }

    fun forget() { cancel(); kay.forgetTalk(); panel.status = "" }

    /** 接着聊 from the line of the day: it starts the conversation, and the strip is ready to answer. */
    fun openChat(first: String?) {
        if (first != null && kay.talk.isEmpty()) kay.talk += Kay.Turn(false, first)
        host.openStrip("chat")
    }

    /** Says [said] to 小K; [onText] as the answer streams, [onDone] at the end (on the main thread). */
    fun chat(said: String, onText: (String) -> Unit, onDone: (Result<String>) -> Unit) {
        val t = target() ?: return onDone(Result.failure(IllegalStateException("先在设置里添加 AI 服务")))
        if (!kay.mayTalk()) {
            kay.talk += Kay.Turn(true, said); kay.talk += Kay.Turn(false, "今天聊得够多啦，明天见 (｡･ω･｡)ﾉ")
            syncChat(); return onDone(Result.success(lastAnswer()))
        }
        val id = ++run
        pendingSaid = said; pendingAnswer = ""
        chatBusy = true; panel.running = CHAT; panel.status = ""
        SharkLife.thinking = true
        syncChat()
        kay.talked()
        Thread {
            com.kk.shurufa.online.AiBudget.asked(prefs)
            val r = runCatching {
                Llm.completeTarget(prefs, t, kay.chatSystem(), kay.chatUser(said),
                    onText = { s -> handler.post { if (id == run) { pendingAnswer = s.trim(); syncChat(); onText(pendingAnswer) } } },
                    cancelled = { id != run })
            }
            handler.post {
                if (id != run) return@post
                chatBusy = false; panel.running = null
                SharkLife.thinking = false
                pendingSaid = ""; pendingAnswer = ""
                val out = r.map { kay.heard(said, it) }
                out.onFailure { kay.talk += Kay.Turn(true, said); panel.status = it.message ?: "没连上" }
                syncChat()
                onDone(out)
                host.haptics.play(HapticEvent.GESTURE)
            }
        }.apply { isDaemon = true; name = "kk-kay" }.start()
    }

    /** What was said, styled: 主人's words in a softer ink, 小K's name in the accent. */
    private fun syncChat() {
        if (!::panel.isInitialized) return
        val sb = android.text.SpannableStringBuilder()
        fun line(who: String, text: String, mine: Boolean) {
            if (sb.isNotEmpty()) sb.append("\n")
            val start = sb.length
            sb.append(who).append("  ")
            sb.setSpan(android.text.style.ForegroundColorSpan(if (mine) host.kbTheme.hint else host.kbTheme.accent), start, sb.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(android.text.style.RelativeSizeSpan(0.82f), start, sb.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            val t0 = sb.length
            sb.append(text)
            if (mine) sb.setSpan(android.text.style.ForegroundColorSpan(host.kbTheme.inkSoft), t0, sb.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        for (t in kay.talk) line(if (t.mine) "我" else kay.name, t.text, t.mine)
        if (pendingSaid.isNotEmpty()) {
            line("我", pendingSaid, true)
            if (pendingAnswer.isNotEmpty()) line(kay.name, pendingAnswer, false)
        }
        panel.chat = sb
        panel.chatHasAnswer = kay.talk.any { !it.mine }
    }

    /** Leaving stops a run that is still streaming; what was said stays in the conversation. */
    fun cancel() {
        run++
        SharkLife.thinking = false
        if (pendingSaid.isNotEmpty()) { kay.talk += Kay.Turn(true, pendingSaid); pendingSaid = ""; pendingAnswer = "" }
        chatBusy = false
        if (::panel.isInitialized) panel.running = null
    }

    private companion object { const val CHAT = "聊天" }
}
