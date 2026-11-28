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
import com.kk.shurufa.ime.Panel
import com.kk.shurufa.ime.ZhComposer
import com.kk.shurufa.keyboard.Key
import com.kk.shurufa.keyboard.Output
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.Duration

/**
 * The main typing path, end to end through the service: keys in, corrections, candidates taken,
 * in 中文 (through a stand-in for Rime, [FakeRime]), Russian and English. What lands in the field
 * is what is checked, key by key, the way the keyboard view calls the service.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [ShadowRimeBridge::class], instrumentedPackages = ["com.kk.shurufa.rime"])
class TypingPathTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var ime: KkImeService
    private lateinit var field: Field

    private class Field(view: View) : BaseInputConnection(view, true) {
        val text: Editable = SpannableStringBuilder().also { Selection.setSelection(it, 0) }
        override fun getEditable(): Editable = text
    }

    @Before fun start() {
        for (l in listOf("en", "ru")) File(ctx.filesDir, "learned_$l.tsv").delete()
        Prefs(ctx).apply { put(Prefs.ANIMATIONS, false); put(Prefs.LANGUAGES, "zh,ru,en") }
        FakeRime.ready()
        ime = Robolectric.buildService(KkImeService::class.java).create().get()
        val view = ime.onCreateInputView()
        field = Field(view)
        android.inputmethodservice.InputMethodService::class.java.getDeclaredField("mStartedInputConnection")
            .apply { isAccessible = true }.set(ime, field)
        val info = EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT; packageName = "test.app" }
        // what the system sets before it starts the view: the field's type decides 句首大写
        android.inputmethodservice.InputMethodService::class.java.getDeclaredField("mInputEditorInfo")
            .apply { isAccessible = true }.set(ime, info)
        ime.onStartInputView(info, false)
        idle()
        lang("zh")
    }

    @After fun stop() {
        ime.onDestroy()
        Prefs(ctx).sp.edit().remove(Prefs.LANGUAGES).apply()
        for (l in listOf("en", "ru")) File(ctx.filesDir, "learned_$l.tsv").delete()
        FakeRime.reset()
    }

    private fun idle() { Thread.sleep(20); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(30)) }

    private fun lang(id: String) { ime.onOption(Key(fn = "language"), Output("", "§lang:$id")); idle() }

    private fun type(s: String) {
        for (ch in s) {
            when (ch) {
                ' ' -> ime.onFunction(Key(fn = "space"))
                '\n' -> ime.onFunction(Key(fn = "enter"))
                else -> { val k = Key(main = Output.of(ch.toString())); ime.onOutput(k, k.main!!) }
            }
            idle()
        }
    }

    private fun back(times: Int = 1) = repeat(times) { ime.onFunction(Key(fn = "backspace")); idle() }

    private val text get() = field.text.toString()

    /** Puts [s] in the field with the caret after it, as if the person had typed it before. */
    private fun fieldHas(s: String) {
        field.text.clear(); field.text.append(s); Selection.setSelection(field.text, s.length)
        ime.onUpdateSelection(0, 0, s.length, s.length, -1, -1); idle()
    }

    @Suppress("UNCHECKED_CAST")
    private fun predictions() = KkImeService::class.java.getDeclaredField("predictions").apply { isAccessible = true }.get(ime) as List<String>

    /** The word tables load in the background on first use: waits until [word]'s language suggests. */
    private fun waitForWords(probe: String) {
        val until = System.currentTimeMillis() + 10_000
        type(probe)
        while (predictions().isEmpty() && System.currentTimeMillis() < until) { back(probe.length); type(probe) }
        assertTrue("the word table loaded", predictions().isNotEmpty())
        back(probe.length)
    }

    // ---- 中文 ---------------------------------------------------------------------------------

    @Test fun zhLettersComposeAndSpaceCommitsTheFirst() {
        assertEquals("zh", ime.language)
        type("nihao")
        assertEquals("pinyin stays in the composition, not in the field", "", text)
        assertEquals("nihao", FakeRime.input)
        type(" ")
        assertEquals("你好", text)
        assertEquals("", FakeRime.input)
        type("shijie ")
        assertEquals("space after a commit is not typed into the field", "你好世界", text)
    }

    @Test fun zhCandidatePickedByIndex() {
        type("nihao")
        ime.onCandidate(1); idle()
        assertEquals("拟好", text)
        assertEquals("", FakeRime.input)
    }

    @Test fun zhPartialPickLeavesTheRestComposing() {
        type("nihao")
        // 你 covers "ni": the menu is 你好, 拟好, then the words of the prefix
        val ni = FakeRime.menu().indexOfFirst { it.text == "你" }
        ime.onCandidate(ni); idle()
        assertEquals("nothing is committed until the whole input is chosen", "", text)
        assertEquals("hao", FakeRime.input)
        ime.onCandidate(1); idle()
        assertEquals("你号", text)
    }

    @Test fun zhBackspaceEditsPinyinFirstThenTheField() {
        type("nihao ")
        assertEquals("你好", text)
        type("ni")
        back(2)
        assertEquals("backspace took the pinyin, not the text", "你好", text)
        assertEquals("", FakeRime.input)
        back()
        assertEquals("你", text)
    }

    @Test fun zhEnterCommitsTheLettersAsTyped() {
        type("nihao\n")
        assertEquals("nihao", text)
        assertEquals("", FakeRime.input)
    }

    @Test fun zhAtSignTurnsPinyinIntoAnAddress() {
        type("kk@")
        assertEquals("kk@", text)
        assertEquals("", FakeRime.input)
    }

    @Test fun zhPunctuationFinishesTheCompositionFirst() {
        type("nihao")
        val k = Key(main = Output.of("，")); ime.onOutput(k, k.main!!); idle()
        assertEquals("你好，", text)
        // after a digit the mark goes in half-width
        fieldHas("3")
        ime.onOutput(k, k.main!!); idle()
        assertEquals("3,", text)
    }

    @Test fun zhCorrectedSpellingCommitsTheWordAndIsMarked() {
        type("nihap")
        val menu = FakeRime.menu()
        assertEquals("你好", menu.first().text)
        // the note under the candidate says which key slipped
        val notes = ZhComposer(ctx, Prefs(ctx)).annotations(menu.map { it.text }, menu.map { it.comment })
        assertEquals("p→o", notes.first())
        type(" ")
        assertEquals("你好", text)
    }

    @Test fun zhCompositionIsCommittedWhenTheLanguageChanges() {
        type("nihao")
        lang("ru")
        assertEquals("ru", ime.language)
        assertEquals("the highlighted candidate goes in, the pinyin is not lost", "你好", text)
        assertEquals("", FakeRime.input)
    }

    @Test fun zhLettersWithoutRimeStillReachTheField() {
        RimeEngine_notReady()
        type("ab")
        assertEquals("ab", text)
        FakeRime.ready()
    }

    private fun RimeEngine_notReady() =
        com.kk.shurufa.rime.RimeEngine::class.java.getDeclaredField("state").apply { isAccessible = true }
            .set(null, com.kk.shurufa.rime.RimeEngine.State.PREPARING)

    // ---- Русский ------------------------------------------------------------------------------

    @Test fun ruCapitalisedAtStartAndTypedAsIs() {
        lang("ru")
        assertEquals(Panel.LETTERS, ime.panel)
        type("привет как")
        assertEquals("the sentence start is capitalised, the rest is not", "Привет как", text)
    }

    @Test fun ruSlipCorrectedOnSpaceAndBackspacePutsItBack() {
        lang("ru")
        waitForWords("п")
        type("ну ")
        type("рпивет ")
        assertEquals("Ну привет ", text)
        back()
        assertEquals("Ну рпивет", text)
        type(" ")
        assertEquals("a word put back is not corrected again", "Ну рпивет ", text)
    }

    @Test fun ruCompletionPickedAndPunctuationBeforeItsSpace() {
        lang("ru")
        waitForWords("п")
        type("ну ")
        type("спас")
        val i = predictions().indexOf("спасибо")
        assertTrue("спасибо is offered for спас: ${predictions()}", i >= 0)
        ime.onCandidate(-1 - i); idle()
        assertEquals("Ну спасибо ", text)
        type(",")
        assertEquals("the comma goes before the space the pick added", "Ну спасибо, ", text)
    }

    // ---- English ------------------------------------------------------------------------------

    @Test fun enCompletionPickedThenSentenceEndCapitalises() {
        lang("en")
        waitForWords("t")
        type("so ")
        type("hel")
        val i = predictions().indexOf("hello")
        assertTrue("hello is offered for hel: ${predictions()}", i >= 0)
        ime.onCandidate(-1 - i); idle()
        assertEquals("So hello ", text)
        type(".")
        assertEquals("So hello. ", text)
        type("yes")
        assertEquals("So hello. Yes", text)
    }

    @Test fun enSlipCorrectedAndOriginalChipPutsItBack() {
        lang("en")
        waitForWords("t")
        type("so ")
        type("teh ")
        assertEquals("So the ", text)
        assertEquals("the word as typed is offered first", "teh", predictions().first())
        ime.onCandidate(-1); idle()
        assertEquals("So teh ", text)
    }

    // ---- across languages ---------------------------------------------------------------------

    @Test fun mixedSentenceAcrossTheThreeLanguages() {
        type("zhongwen ")
        lang("ru")
        type(" да ")
        lang("en")
        type("ok")
        lang("zh")
        type("nihao ")
        assertEquals("中文 да ok你好", text)
        assertEquals("", FakeRime.input)
    }
}
