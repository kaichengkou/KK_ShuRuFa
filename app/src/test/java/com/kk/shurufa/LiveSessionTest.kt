package com.kk.shurufa

import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.Selection
import android.text.SpannableStringBuilder
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import androidx.test.core.app.ApplicationProvider
import com.kk.shurufa.ime.KkImeService
import com.kk.shurufa.keyboard.Key
import com.kk.shurufa.keyboard.Output
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.Duration

/**
 * A typing session through the service with the real Gemini API: 中文 (through [FakeRime]), AI
 * 候选, Russian and English with 云联想, the translation strip and a chat with 小K, logged as it
 * goes. Needs a key in KK_GEMINI_KEY (never stored in the repository); skipped otherwise.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [ShadowRimeBridge::class], instrumentedPackages = ["com.kk.shurufa.rime"])
class LiveSessionTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val key = System.getenv("KK_GEMINI_KEY")
    private class Field(view: View) : BaseInputConnection(view, true) {
        val text: Editable = SpannableStringBuilder().also { Selection.setSelection(it, 0) }
        var reads = 0; var writes = 0
        override fun getEditable(): Editable = text
        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? { reads++; return super.getTextBeforeCursor(n, flags) }
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean { writes++; return super.commitText(text, newCursorPosition) }
    }
    private lateinit var ime: KkImeService
    private lateinit var field: Field
    private fun log(s: String) = println("[sim] $s")
    private fun idle(ms: Long = 30) { Thread.sleep(ms); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms)) }
    private fun lang(id: String) { ime.onOption(Key(fn = "language"), Output("", "§lang:$id")); idle() }
    private val keyNs = ArrayList<Long>()
    private fun type(s: String) {
        for (ch in s) {
            val t0 = System.nanoTime()
            when (ch) {
                ' ' -> ime.onFunction(Key(fn = "space"))
                '\n' -> ime.onFunction(Key(fn = "enter"))
                else -> { val k = Key(main = Output.of(ch.toString())); ime.onOutput(k, k.main!!) }
            }
            keyNs += System.nanoTime() - t0
            idle(25)
        }
    }
    private fun waitUntil(ms: Long, done: () -> Boolean): Long { val t0 = System.currentTimeMillis(); while (!done() && System.currentTimeMillis() - t0 < ms) idle(50); return System.currentTimeMillis() - t0 }
    private fun get(o: Any, name: String): Any? {
        var c: Class<*>? = o.javaClass
        while (c != null) {
            runCatching { return c!!.getDeclaredField(name).apply { isAccessible = true }.get(o) }
            runCatching { return (c!!.getDeclaredField("$name\$delegate").apply { isAccessible = true }.get(o) as Lazy<*>).value }
            c = c.superclass
        }
        error(name)
    }
    @Suppress("UNCHECKED_CAST") private fun predictions() = get(ime, "predictions") as List<String>
    @Suppress("UNCHECKED_CAST") private fun cloudShown() = get(ime, "cloudShown") as List<String>
    @Suppress("UNCHECKED_CAST") private fun floatCloud() = get(ime, "floatCloud") as List<String>

    @Test fun session() {
        assumeTrue(!key.isNullOrBlank())
        for (l in listOf("en", "ru")) File(ctx.filesDir, "learned_$l.tsv").delete()
        val p = Prefs(ctx)
        p.apply { put(Prefs.ANIMATIONS, false); put(Prefs.LANGUAGES, "zh,ru,en"); put("llm_gemini_key", key!!); put(Prefs.AI_PROVIDER, "gemini|gemini-3.5-flash-lite") }
        FakeRime.ready()
        ime = Robolectric.buildService(KkImeService::class.java).create().get()
        val view = ime.onCreateInputView()
        field = Field(view)
        android.inputmethodservice.InputMethodService::class.java.getDeclaredField("mStartedInputConnection").apply { isAccessible = true }.set(ime, field)
        val info = EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT; packageName = "test.chat" }
        android.inputmethodservice.InputMethodService::class.java.getDeclaredField("mInputEditorInfo").apply { isAccessible = true }.set(ime, info)
        ime.onStartInputView(info, false); idle()
        // 1. 中文
        lang("zh")
        type("nihao shijie ")
        log("中文 → 「${field.text}」 联想 ${predictions().take(5)}")
        type("。")
        // 2. AI 候选 on a long pinyin
        p.put(Prefs.AI_ASSIST, "candidate")
        type("womenzaixuexiaomenkoujianmian")
        val aiCand = get(ime, "aiCand")!!
        val wa = waitUntil(15_000) { aiCand.javaClass.getMethod("answerFor", String::class.java).invoke(aiCand, "womenzaixuexiaomenkoujianmian") != null }
        log("AI 候选 ${wa} ms → ${aiCand.javaClass.getMethod("answerFor", String::class.java).invoke(aiCand, "womenzaixuexiaomenkoujianmian")} (浮窗: ${get(ime, "floatCand")})")
        ime.onFunction(Key(fn = "enter")); idle()
        log("回车上屏字母 → 「${field.text}」")
        // 3. Русский, with 云联想
        p.put(Prefs.AI_ASSIST, "cloud")
        lang("ru")
        type("\nпривет, как дела? спасибо за ")
        val wr = waitUntil(15_000) { floatCloud().isNotEmpty() || cloudShown().isNotEmpty() }
        log("俄语 → 「${field.text.toString().substringAfterLast('\n')}」 词联想 ${predictions().take(5)} 云联想 ${wr} ms ${floatCloud() + cloudShown()}")
        // 4. English
        lang("en")
        type("\nhow are ")
        val we = waitUntil(15_000) { floatCloud().isNotEmpty() || cloudShown().isNotEmpty() }
        log("英语 → 「${field.text.toString().substringAfterLast('\n')}」 词联想 ${predictions().take(5)} 云联想 ${we} ms ${floatCloud() + cloudShown()}")
        type("you? i ")
        log("英语续 → 「${field.text.toString().substringAfterLast('\n')}」")
        p.put(Prefs.AI_ASSIST, "off")
        // 5. 翻译条: typed in Russian, into Chinese
        val tr = get(ime, "translate")!!
        ime.openStrip("translate"); idle()
        type("я очень устал сегодня")
        val wt = waitUntil(20_000) { (get(tr, "result") as String).isNotEmpty() }
        log("翻译 ${wt} ms → ${get(tr, "result")}")
        ime.onFunction(Key(fn = "enter")); idle(200)
        log("翻译上屏后 → 「${field.text.toString().substringAfterLast('\n')}」")
        // 6. 小K 聊天
        val ai = get(ime, "ai")!!
        var answer: String? = null
        ai.javaClass.getMethod("chat", String::class.java, Function1::class.java, Function1::class.java).invoke(ai, "今天有点累，给我一句鼓励的话", { _: String -> Unit }, { r: Result<String> -> answer = r.getOrNull() ?: ("!! " + r.exceptionOrNull()?.message) })
        val wc = waitUntil(30_000) { answer != null }
        log("小K 聊天 ${wc} ms → $answer")
        val avg = keyNs.drop(5).average() / 1e6
        log("按键 ${keyNs.size} 次，服务内处理平均 ${"%.2f".format(avg)} ms，最慢 ${"%.1f".format(keyNs.max() / 1e6)} ms；读输入框 ${field.reads} 次，写 ${field.writes} 次")
        log("最终输入框：\n${field.text}")
        ime.onDestroy()
        Prefs(ctx).sp.edit().remove(Prefs.LANGUAGES).remove("llm_gemini_key").remove(Prefs.AI_ASSIST).apply()
        FakeRime.reset()
    }
}
