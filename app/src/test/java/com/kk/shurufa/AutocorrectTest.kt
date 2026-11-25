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
import com.kk.shurufa.ime.WordPredictor
import com.kk.shurufa.keyboard.Key
import com.kk.shurufa.keyboard.Output
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** 自动纠错 (Russian, English): what it corrects, and above all what it leaves alone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AutocorrectTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun loaded(lang: String): WordPredictor {
        java.io.File(ctx.filesDir, "learned_$lang.tsv").delete()
        val p = WordPredictor(ctx, lang)
        p.load()
        val until = System.currentTimeMillis() + 10_000
        while (!p.ready && System.currentTimeMillis() < until) Thread.sleep(20)
        assertTrue(p.ready)
        return p
    }

    @Test fun correctsObviousSlips() {
        val ru = loaded("ru")
        val fixes = mapOf(
            "рпивет" to "привет", "спсибо" to "спасибо", "сегодян" to "сегодня",
            "хорошл" to "хорошо", "пожалуйтса" to "пожалуйста",
        )
        for ((typo, want) in fixes) assertEquals(typo, want, ru.correction(typo, sentenceStart = false))
        // at a sentence start the case follows
        assertEquals("Привет", ru.correction("Рпивет", sentenceStart = true))

        val en = loaded("en")
        val enFixes = mapOf(
            "teh" to "the", "becuase" to "because", "thier" to "their", "i" to "I", "im" to "I'm", "dont" to "don't",
            "didnt" to "didn't", "ive" to "I've", "cant" to "can't", "recieve" to "receive",
        )
        for ((typo, want) in enFixes) assertEquals(typo, want, en.correction(typo, sentenceStart = false))
    }

    @Test fun leavesNamesAbbreviationsAndRealWordsAlone() {
        val ru = loaded("ru")
        // real words, also ones a key away from a more common one (сто/что, мир/мой)
        for (w in listOf("сто", "что", "мир", "дом", "кот", "как", "так", "привет", "андрей", "ещё", "еще", "тоже", "также", "басков", "вася", "взяло", "веер"))
            assertNull(w, ru.correction(w, sentenceStart = false))
        // names, abbreviations, slang the table lacks: unknown but not one slip from a common word
        for (w in listOf("физфак", "мгу", "кк", "кайчэн", "лол", "спб", "вшэ", "мфти", "норм", "ок"))
            assertNull(w, ru.correction(w, sentenceStart = false))
        // a capital inside a sentence is a name; capitals throughout an abbreviation
        assertNull(ru.correction("Леонидович", sentenceStart = false))
        assertNull(ru.correction("Рпивет", sentenceStart = false))
        assertNull(ru.correction("МГУ", sentenceStart = true))

        val en = loaded("en")
        for (w in listOf("form", "from", "id", "ill", "its", "were", "well", "helo", "lol", "omg", "kk", "pinyin", "aloe", "asexual", "akin", "gemini", "xiaoming", "zhang", "msu"))
            assertNull(w, en.correction(w, sentenceStart = false))
        assertNull(en.correction("I", sentenceStart = false))
    }

    @Test fun aWordUsedOrPutBackStays() {
        val ru = loaded("ru")
        assertEquals("привет", ru.correction("рпивет", false))
        ru.keep("рпивет", learn = false)
        assertNull(ru.correction("рпивет", false))
        // a word this person has written is theirs
        val en = loaded("en")
        assertEquals("the", en.correction("teh", false))
        en.learn(emptyList(), "teh")
        assertNull(en.correction("teh", false))
        en.clearLearned()
    }

    /** A sweep over the table: no common word (score ≥ 95) is ever "corrected". */
    @Test fun commonWordsAreNeverTouched() {
        for (lang in listOf("ru", "en")) {
            val p = loaded(lang)
            var checked = 0
            ctx.assets.open("data/${lang}_words.txt").bufferedReader().useLines { lines ->
                for (l in lines) {
                    val parts = l.split('\t')
                    val sc = parts.getOrNull(1)?.toIntOrNull() ?: continue
                    if (sc < 95 || checked > 4000 || parts[0].length < 3) continue
                    if (lang == "en" && parts.getOrNull(2)?.startsWith("I") == true) continue
                    val fix = p.correction(parts[0], false)
                    // only the English contractions may change a word the table has
                    if (fix != null) assertTrue("$lang ${parts[0]} → $fix", lang == "en" && fix.contains('\''))
                    checked++
                }
            }
        }
    }

    private class Field(view: View) : BaseInputConnection(view, true) {
        val text: Editable = SpannableStringBuilder().also { Selection.setSelection(it, 0) }
        override fun getEditable(): Editable = text
    }

    @Test fun correctsOnSpaceAndBackspacePutsItBack() {
        Prefs(ctx).apply { put(Prefs.ANIMATIONS, false); put(Prefs.LANGUAGES, "en,zh") }
        java.io.File(ctx.filesDir, "learned_en.tsv").delete()
        val ime = Robolectric.buildService(KkImeService::class.java).create().get()
        val view = ime.onCreateInputView()
        val field = Field(view)
        android.inputmethodservice.InputMethodService::class.java.getDeclaredField("mStartedInputConnection")
            .apply { isAccessible = true }.set(ime, field)
        ime.onStartInputView(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT; packageName = "test.app" }, false)
        fun idle() { Thread.sleep(30); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(30)) }
        fun type(s: String) {
            for (ch in s) {
                if (ch == ' ') ime.onFunction(Key(fn = "space"))
                else { val k = Key(main = Output.of(ch.toString())); ime.onOutput(k, k.main!!) }
                idle()
            }
        }
        fun back() { ime.onFunction(Key(fn = "backspace")); idle() }
        @Suppress("UNCHECKED_CAST")
        fun predictions() = KkImeService::class.java.getDeclaredField("predictions").apply { isAccessible = true }.get(ime) as List<String>
        // the tables load in the background on first use
        val until = System.currentTimeMillis() + 10_000
        type("t"); while (predictions().isEmpty() && System.currentTimeMillis() < until) { idle(); back(); type("t") }
        back()
        // auto-capitalisation is on at the start: type in lowercase after a word
        field.text.clear(); field.text.append("ok "); Selection.setSelection(field.text, 3); ime.onUpdateSelection(0, 0, 3, 3, -1, -1)
        type("teh ")
        assertEquals("ok the ", field.text.toString())
        assertEquals("the word as typed comes first", "teh", predictions().first())
        // backspace right after puts it back, and it is not corrected again
        back()
        assertEquals("ok teh", field.text.toString())
        type(" ")
        assertEquals("ok teh ", field.text.toString())
        // punctuation finishes a word too; the 原 chip puts it back with the comma kept
        type("becuase,")
        assertEquals("ok teh because,", field.text.toString())
        type(" ")
        assertTrue("a key after the correction ends it: ${predictions()}", predictions().firstOrNull() != "becuase")
        type("thier")
        ime.onFunction(Key(fn = "space")); idle()
        assertEquals("ok teh because, their ", field.text.toString())
        ime.onCandidate(-1); idle()
        assertEquals("ok teh because, thier ", field.text.toString())
        // helo is one key from both hello and help: too close to call, left as typed
        type("helo ")
        assertEquals("ok teh because, thier helo ", field.text.toString())
        ime.onDestroy()
        Prefs(ctx).sp.edit().remove(Prefs.LANGUAGES).apply()
        java.io.File(ctx.filesDir, "learned_en.tsv").delete()
    }
}
