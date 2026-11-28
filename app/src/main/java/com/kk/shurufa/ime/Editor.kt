package com.kk.shurufa.ime

import android.icu.text.BreakIterator
import android.inputmethodservice.InputMethodService
import android.os.SystemClock
import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import com.kk.shurufa.Prefs
import kotlin.math.abs

/**
 * Everything the keyboard writes into the app's field goes through here: text (with bracket
 * pairing and a caret marker ‸), deletion, clearing, the keyboard's own undo, caret movement that
 * stops at the edges of the text, and the record of typed characters 删除后联想 reads.
 */
class Editor(private val ime: InputMethodService, private val prefs: Prefs) {
    private val history = EditHistory()
    private val ic: InputConnection? get() = ime.currentInputConnection

    /**
     * Sends an image as content (what chat apps take for a pasted picture). False when the field
     * says it takes no image of this type, or the app turned it down.
     */
    fun commitImage(uri: android.net.Uri, mime: String): Boolean { dirty();
        val info = ime.currentInputEditorInfo ?: return false
        val ic = ic ?: return false
        val accepted = EditorInfoCompat.getContentMimeTypes(info)
        if (accepted.none { android.content.ClipDescription.compareMimeTypes(mime, it) }) return false
        val content = InputContentInfoCompat(uri, android.content.ClipDescription("图片", arrayOf(mime)), null)
        return InputConnectionCompat.commitContent(ic, info, content, InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null)
    }

    /** 打字数据: where what is written is counted; off in fields that ask not to be learned from. */
    var stats: TypingStats? = null
    var recording = true

    private fun record(text: String, source: TypingStats.Source, words: Boolean = true) {
        if (recording) stats?.record(text, source, words)
    }

    /** A Russian or English word just finished, for the word cloud of 打字数据. */
    fun countWord(word: String) { if (recording) stats?.word(word) }

    /** Selection as last reported by the editor. */
    var selStart = 0
        private set
    var selEnd = 0
        private set
    val hasSelection get() = selStart != selEnd
    /** 选择模式 of the edit panel: caret moves extend the selection. */
    var selectMode = false

    fun onSelection(start: Int, end: Int) {
        // steps of the space-key trackpad come faster than the app reports them: the report of an
        // earlier step must not take the caret back from where the later ones already put it
        if (stepsAhead.isNotEmpty()) {
            if (start == end && end == stepsAhead.first()) { stepsAhead.removeFirst(); if (stepsAhead.isNotEmpty()) return }
            else stepsAhead.clear()
        }
        selStart = start; selEnd = end; beforeCache = null; caretStale = false
    }
    /** Where [nearCaretStep] put the caret, in order, not yet reported back by the app. */
    private val stepsAhead = ArrayDeque<Int>()
    /** The field changed (or a key went to it) since the app last said where the caret is. */
    private var caretStale = false

    /** Drops the selection to a caret at its end. */
    fun collapseSelection() { dirty(); ic?.setSelection(selEnd, selEnd) }

    /** A new field: nothing to undo, nothing selected on purpose. */
    fun reset() { dirty(); history.clear(); selectMode = false; charLog.clear(); deletedRun.clear() }

    /**
     * The text before the caret. Each read is a call into the app (a round trip to another
     * process), and one key asks several times; what was read is kept until the keyboard changes
     * the field or the selection moves.
     */
    fun textBefore(n: Int): String {
        if (n <= 0) return ""
        val c = beforeCache
        if (c != null && (n <= beforeAsked || c.length < beforeAsked)) return if (c.length <= n) c else c.substring(c.length - n)
        // one read serves every later one of the same key (the longest are 云联想's 80 characters)
        val ask = maxOf(n, 128)
        val t = ic?.getTextBeforeCursor(ask, 0)?.toString() ?: return ""
        beforeCache = t; beforeAsked = ask
        return if (t.length <= n) t else t.substring(t.length - n)
    }
    private var beforeCache: String? = null
    private var beforeAsked = 0
    /** Read now, not kept: for reads in the middle of a change (what was read would be stale right after). */
    private fun raw(n: Int) = ic?.getTextBeforeCursor(n, 0)?.toString().orEmpty()
    /** The field is about to change (or did): what was read of it is read again. */
    fun dirty() { beforeCache = null; caretStale = true }
    fun textAfter(n: Int) = ic?.getTextAfterCursor(n, 0)?.toString().orEmpty()
    fun selectedText(): String? = ic?.getSelectedText(0)?.toString()

    /** Absolute selection (start, end) in the field, or null when the editor does not say. */
    fun selection(): Pair<Int, Int>? {
        // only the offsets are wanted: an editor that honours the hint sends a few characters, not
        // the whole document (a long one is a lot to send for each bracket typed)
        val et = ic?.getExtractedText(ExtractedTextRequest().apply { hintMaxChars = 64 }, 0) ?: return null
        return (et.startOffset + minOf(et.selectionStart, et.selectionEnd)) to (et.startOffset + maxOf(et.selectionStart, et.selectionEnd))
    }

    /** All of the field's text, when the editor shares it. */
    fun allText(): String? = ic?.getExtractedText(ExtractedTextRequest(), 0)?.text?.toString()

    // ---- writing ----

    /**
     * Types [text]: commits it (pairing brackets when [pairable]) and records it for undo.
     * [syllables] are the pinyin that produced its Chinese characters (for 删除后联想).
     */
    fun type(text: String, pairable: Boolean = true, syllables: List<String>? = null, source: TypingStats.Source = TypingStats.Source.TYPED) {
        // (what was read of the field is let go in commit, which knows what it becomes)
        val tail = commit(text, pairable, syllables, source) ?: return
        history.push(EditHistory.Op.Insert(tail.first, tail.second))
    }

    /**
     * Commits [text] without recording it; `‸` marks where the caret should land. Returns what
     * went in before and after the caret, or null when nothing did.
     */
    fun commit(text: String, pairable: Boolean = true, syllables: List<String>? = null, source: TypingStats.Source = TypingStats.Source.TYPED): Pair<String, String>? {
        // what was read before this key, if anything: with the text added it is what the field
        // holds before the caret after it (unless pinyin was shown in the field, which goes)
        val known = beforeCache?.takeIf { !composingShown }
        val knownAsked = beforeAsked
        dirty()
        val ic = ic ?: return null
        logChars(text.replace("‸", ""), syllables)
        var t = text
        if (pairable && prefs.autoPair) {
            val cps = t.codePointCount(0, t.length)
            // typing a closer that is already there just steps over it
            if (cps == 1 && t in CLOSERS && ic.getTextAfterCursor(1, 0)?.toString() == t) { moveBy(1); return null }
            if (cps == 1 && PAIRS.containsKey(t)) {
                val selected = ic.getSelectedText(0)?.toString().orEmpty()
                t = t + selected + "‸" + PAIRS[t]
            } else if (cps == 2) {
                val a = t.substring(0, t.offsetByCodePoints(0, 1))
                val b = t.substring(a.length)
                if (PAIRS[a] == b) t = "$a‸$b"
            }
        }
        val idx = t.indexOf('‸')
        val clean = t.replace("‸", "")
        record(clean, source)
        if (idx < 0 || idx >= clean.length) {
            ic.commitText(clean, 1)
            composingShown = false
            if (known != null) { beforeCache = (known + clean).takeLast(knownAsked); beforeAsked = knownAsked }
            return clean to ""
        }
        // read the position before the edit: inside a batch edit the editor may report stale values
        val start = selection()?.first
        ic.beginBatchEdit()
        ic.commitText(clean, 1)
        if (start != null) ic.setSelection(start + idx, start + idx)
        else repeat(clean.length - idx) { ime.sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_LEFT) }
        ic.endBatchEdit()
        return clean.substring(0, idx) to clean.substring(idx)
    }

    /** Replaces the whole field (or the selection) with [text], undoable. */
    fun replace(text: String, wholeField: Boolean) { dirty();
        val ic = ic ?: return
        ic.beginBatchEdit()
        if (wholeField) {
            val et = ic.getExtractedText(ExtractedTextRequest(), 0)
            val all = et?.text?.toString().orEmpty()
            history.push(EditHistory.Op.Clear(all, (et?.startOffset ?: 0) + (et?.selectionEnd ?: 0)))
            ic.setSelection(0, all.length)
        }
        ic.commitText(text, 1)
        ic.endBatchEdit()
        record(text, TypingStats.Source.AI)
    }

    /** Puts [text] after the selection (or at the caret), leaving the selection's text alone. */
    fun insertAfterSelection(text: String) { dirty();
        val ic = ic ?: return
        ic.beginBatchEdit()
        selection()?.let { ic.setSelection(it.second, it.second) }
        ic.commitText(text, 1)
        ic.endBatchEdit()
        history.push(EditHistory.Op.Insert(text))
        record(text, TypingStats.Source.AI)
    }

    /** Shows [text] as composing text (replaced by the next compose or commit). */
    fun compose(text: String) { dirty(); composingShown = text.isNotEmpty(); composed = text; ic?.setComposingText(text, 1) }
    /** Pinyin is shown in the field (内嵌拼音), as composing text. */
    private var composingShown = false
    private var composed = ""

    /** The text before the caret without the pinyin shown in the field (内嵌拼音), if it is. */
    fun textBeforeComposing(n: Int): String {
        if (!composingShown) return textBefore(n)
        return textBefore(n + composed.length).removeSuffix(composed).takeLast(n)
    }

    fun finishComposingText() { dirty(); composingShown = false; ic?.finishComposingText() }

    /** Replaces the composing preview with a finished sentence, after deleting [deleteBefore] characters. */
    fun commitVoice(deleteBefore: Int, text: String) { dirty();
        val ic = ic ?: return
        ic.setComposingText("", 1)
        ic.beginBatchEdit()
        if (deleteBefore > 0) ic.deleteSurroundingText(deleteBefore, 0)
        ic.commitText(text, 1)
        ic.endBatchEdit()
        history.push(EditHistory.Op.Insert(text))
        record(text, TypingStats.Source.VOICE)
    }

    /** Deletes [text] when it is right before the caret. */
    fun deleteIfBefore(text: String) { dirty();
        val ic = ic ?: return
        if (text.isNotEmpty() && raw(text.length) == text) ic.deleteSurroundingText(text.length, 0)
    }

    /** Deletes [before] characters, then types [text]: one step for undo. */
    fun replaceBefore(before: Int, text: String) { dirty();
        val ic = ic ?: return
        val old = raw(before)
        ic.beginBatchEdit()
        // where a plain delete does not reach the page (Samsung Notes), the characters are selected and replaced
        val et = if (app in stubborn) ic.getExtractedText(ExtractedTextRequest(), 0) else null
        if (et != null) {
            val end = et.startOffset + maxOf(et.selectionStart, et.selectionEnd)
            ic.setSelection(end - old.length, end)
        } else ic.deleteSurroundingText(before, 0)
        ic.commitText(text, 1)
        ic.endBatchEdit()
        history.push(EditHistory.Op.Replace(old, text))
        // what it adds (the rest of a word picked from the suggestions) is counted; a word
        // corrected in place was counted as it was typed
        if (text.length > old.length) record(text.substring(old.length), TypingStats.Source.TYPED, words = false)
    }

    /**
     * Deletes what is before the caret (or the selection). Returns false when there was plainly
     * nothing left to delete, so a held backspace can stop buzzing.
     *
     * Editors that will not say what is before the caret, or refuse the delete, get a real ⌫ key,
     * which every editor handles. A few apps take a delete into the text they show the keyboard
     * but never onto the page ([stubborn]): there the character is selected and replaced instead.
     */
    fun deleteBackward(): Boolean { dirty();
        val ic = ic ?: return false
        val info = ime.currentInputEditorInfo
        if (info?.inputType == InputType.TYPE_NULL) { ime.sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL); return true }
        if (app in stubborn) return deleteBySelecting(ic)
        val before = ic.getTextBeforeCursor(2, 0)?.toString()
            ?: run { ime.sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL); return true }
        // a selection only when the editor reported one: some answer getSelectedText with text
        // while nothing is selected
        if (hasSelection) {
            val sel = ic.getSelectedText(0)
            if (!sel.isNullOrEmpty()) { history.push(EditHistory.Op.Delete(sel.toString())); ic.commitText("", 1); return true }
        }
        // at the start, as far as the field says: the key still goes, for what it cannot show
        if (before.isEmpty()) { ime.sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL); return false }
        val last = before.substring(before.offsetByCodePoints(before.length, -1))
        history.push(EditHistory.Op.Delete(last))
        // an empty bracket pair goes together
        val pair = prefs.autoPair && PAIRS.containsKey(last) && ic.getTextAfterCursor(1, 0)?.toString() == PAIRS[last]
        val ok = if (pair) ic.deleteSurroundingText(last.length, 1)
            else ic.deleteSurroundingTextInCodePoints(1, 0) || ic.deleteSurroundingText(last.length, 0)
        if (!ok) ime.sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        onCharDeleted(last)
        return true
    }

    /**
     * Apps whose editor deletes only by replacing a selection (see [deleteBackward]). Samsung Notes
     * is known: it takes a delete into the text it shows the keyboard but never onto the page,
     * while a selection replaced by nothing (上滑清空, a selection deleted) works.
     */
    private val stubborn = hashSetOf("com.samsung.android.app.notes", "com.samsung.android.app.memo")

    /** Deletes the selection, or the character before the caret, by selecting it and putting nothing in its place. */
    private fun deleteBySelecting(ic: InputConnection): Boolean {
        val et = ic.getExtractedText(ExtractedTextRequest(), 0)
        val text = et?.text
        if (et == null || text == null) {
            // no full text: the caret as the editor last reported it, and the character before it
            val before = ic.getTextBeforeCursor(2, 0)?.toString()
            if (before.isNullOrEmpty() || selEnd <= 0) { ime.sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL); return !before.isNullOrEmpty() || before == null }
            val from = if (hasSelection) minOf(selStart, selEnd) else selEnd - Character.charCount(Character.codePointBefore(before, before.length))
            if (!hasSelection) { val gone = before.substring(before.offsetByCodePoints(before.length, -1)); history.push(EditHistory.Op.Delete(gone)); onCharDeleted(gone) }
            ic.beginBatchEdit(); ic.setSelection(from, maxOf(selStart, selEnd)); ic.commitText("", 1); ic.endBatchEdit()
            return true
        }
        val s = minOf(et.selectionStart, et.selectionEnd).coerceIn(0, text.length)
        val e = maxOf(et.selectionStart, et.selectionEnd).coerceIn(0, text.length)
        val from = if (s != e) s else if (e > 0) e - Character.charCount(Character.codePointBefore(text, e)) else return false
        if (s == e) { val gone = text.subSequence(from, e).toString(); history.push(EditHistory.Op.Delete(gone)); onCharDeleted(gone) }
        ic.beginBatchEdit()
        ic.setSelection(et.startOffset + from, et.startOffset + e)
        ic.commitText("", 1)
        ic.endBatchEdit()
        return true
    }

    /** 上滑清空: the whole field, undoable. Returns false when there was nothing to clear. */
    fun clearAll(): Boolean { dirty();
        val ic = ic ?: return false
        val et = ic.getExtractedText(ExtractedTextRequest(), 0)
        val text = et?.text?.toString()
        if (text != null) {
            if (text.isEmpty()) return false
            history.push(EditHistory.Op.Clear(text, et.startOffset + et.selectionEnd))
            ic.beginBatchEdit()
            ic.setSelection(0, text.length)
            ic.commitText("", 1)
            ic.endBatchEdit()
        } else {
            val before = ic.getTextBeforeCursor(10000, 0)?.toString().orEmpty()
            val after = ic.getTextAfterCursor(10000, 0)?.toString().orEmpty()
            if (before.isEmpty() && after.isEmpty()) return false
            history.push(EditHistory.Op.Clear(before + after, before.length))
            ic.deleteSurroundingText(before.length, after.length)
        }
        return true
    }

    /** 下滑撤销: undoes the keyboard's own last edit, or asks the app (Ctrl+Z) when there is none. */
    fun undo() { dirty();
        val ic = ic ?: return
        when (val op = history.pop()) {
            is EditHistory.Op.Insert -> {
                val ok = ic.getTextBeforeCursor(op.text.length, 0)?.toString() == op.text &&
                    (op.tail.isEmpty() || ic.getTextAfterCursor(op.tail.length, 0)?.toString() == op.tail)
                if (ok) ic.deleteSurroundingText(op.text.length, op.tail.length) else ctrlZ()
            }
            is EditHistory.Op.Delete -> ic.commitText(op.text, 1)
            is EditHistory.Op.Replace -> {
                if (ic.getTextBeforeCursor(op.text.length, 0)?.toString() == op.text) {
                    ic.beginBatchEdit(); ic.deleteSurroundingText(op.text.length, 0); ic.commitText(op.old, 1); ic.endBatchEdit()
                } else ctrlZ()
            }
            is EditHistory.Op.Clear -> {
                ic.beginBatchEdit()
                ic.commitText(op.text, 1)
                ic.setSelection(op.cursor, op.cursor)
                ic.endBatchEdit()
            }
            null -> ctrlZ()
        }
    }

    private fun ctrlZ() = sendKey(KeyEvent.KEYCODE_Z, KeyEvent.META_CTRL_ON)

    fun performEnter() { dirty();
        val ic = ic ?: return
        val info = ime.currentInputEditorInfo
        val action = (info?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        val noAction = (info?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0
        if (!noAction && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) ic.performEditorAction(action)
        else if (info?.inputType == InputType.TYPE_NULL) ime.sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        else type("\n", pairable = false)
    }

    /** What the enter key does in this field, in words. */
    fun enterActionName() = when ((ime.currentInputEditorInfo?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION) {
        EditorInfo.IME_ACTION_SEND -> "发送"
        EditorInfo.IME_ACTION_SEARCH -> "搜索"
        EditorInfo.IME_ACTION_GO -> "前往"
        EditorInfo.IME_ACTION_NEXT -> "下一项"
        EditorInfo.IME_ACTION_DONE -> "完成"
        else -> "回车"
    }

    fun contextMenu(id: Int)  { dirty(); ic?.performContextMenuAction(id) }

    /** The field is a password: nothing is copied out of it. */
    var secret = false
    /** The app the field belongs to. */
    var app = ""

    /** Reads where the selection is from the field itself (one that was there before the keyboard came). */
    fun refreshSelection() { selection()?.let { (s, e) -> onSelection(s, e) } }

    /**
     * 复制 / 剪切: the selected text onto the clipboard. The keyboard copies it itself: some apps
     * (web pages, chat apps) only copy from their own menu and ignore the request. Nothing is
     * copied out of a password field. Returns what was copied, or null when nothing was selected.
     */
    fun copySelection(cut: Boolean): String? {
        val ic = ic ?: return null
        if (secret) return null
        val text = ic.getSelectedText(0)?.toString().orEmpty()
        if (text.isEmpty()) {
            // no selection we can read: let the app try with its own
            ic.performContextMenuAction(if (cut) android.R.id.cut else android.R.id.copy)
            return null
        }
        ime.getSystemService(android.content.ClipboardManager::class.java)
            ?.setPrimaryClip(android.content.ClipData.newPlainText("KK输入法", text))
        if (cut) { history.push(EditHistory.Op.Delete(text)); ic.commitText("", 1) }
        return text
    }

    // ---- the caret ----

    fun sendKey(code: Int, meta: Int = 0) { dirty();
        val ic = ic ?: return
        val t = SystemClock.uptimeMillis()
        ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0, meta))
        ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0, meta))
    }

    /** Shift for caret keys while selecting. */
    fun shiftMeta() = if (selectMode) KeyEvent.META_SHIFT_ON else 0

    fun moveBy(n: Int) { dirty();
        val ic = ic ?: return
        val sel = selection()
        if (sel != null) {
            val p = (sel.second + n).coerceAtLeast(0)
            ic.setSelection(p, p)
        } else {
            val code = if (n < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
            repeat(abs(n)) { ime.sendDownUpKeyEvents(code) }
        }
    }

    /**
     * Moves the caret one character (or line) inside the field. At the start or end of the text it
     * stops there: arrow keys sent past the edge would move the focus to other parts of the app.
     * In selection mode the moving end of the selection is extended. Returns false at an edge.
     */
    fun caretStep(code: Int): Boolean {
        val known = !caretStale
        dirty()
        val ic = ic ?: return false
        // a plain caret (the common case: the space key as a trackpad) reads only what is next to
        // it; a selection, or an editor that does not say where its caret is, reads the whole text
        if (known && !selectMode && selStart == selEnd && selEnd >= 0) nearCaretStep(ic, code)?.let { return it }
        val et = ic.getExtractedText(ExtractedTextRequest(), 0)
        val text = et?.text?.toString()
        if (et == null || text == null) { sendKey(code, shiftMeta()); return true }
        val anchor = et.selectionStart.coerceIn(0, text.length)
        val caret = et.selectionEnd.coerceIn(0, text.length)
        val bi = BreakIterator.getCharacterInstance().apply { setText(text) }
        fun prev(i: Int) = if (i <= 0) 0 else bi.preceding(i).let { if (it == BreakIterator.DONE) 0 else it }
        fun next(i: Int) = if (i >= text.length) text.length else bi.following(i).let { if (it == BreakIterator.DONE) text.length else it }
        val target: Int = when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT -> if (!selectMode && anchor != caret) minOf(anchor, caret) else prev(caret)
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (!selectMode && anchor != caret) maxOf(anchor, caret) else next(caret)
            KeyEvent.KEYCODE_DPAD_UP -> {
                // a line break above: let the editor move by line; otherwise go to the start
                if (caret > 0 && text.lastIndexOf('\n', caret - 1) >= 0) { sendKey(code, shiftMeta()); return true }
                0
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (text.indexOf('\n', caret) >= 0) { sendKey(code, shiftMeta()); return true }
                text.length
            }
            else -> caret
        }
        if (target == caret && (selectMode || anchor == caret)) return false
        val base = et.startOffset
        if (selectMode) ic.setSelection(base + anchor, base + target) else ic.setSelection(base + target, base + target)
        return true
    }

    /**
     * [caretStep] for a caret without a selection, from a few characters around it (each read is a
     * call into the app, and the whole text of a long document is a lot to send for one step).
     * Null when the editor does not answer: then the whole text is read after all.
     */
    private fun nearCaretStep(ic: InputConnection, code: Int): Boolean? {
        val caret = selEnd
        val target = when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                val before = ic.getTextBeforeCursor(NEAR, 0)?.toString() ?: return null
                if (before.isEmpty()) return false
                val bi = BreakIterator.getCharacterInstance().apply { setText(before) }
                val p = bi.preceding(before.length).let { if (it == BreakIterator.DONE) 0 else it }
                caret - (before.length - p)
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val after = ic.getTextAfterCursor(NEAR, 0)?.toString() ?: return null
                if (after.isEmpty()) return false
                val bi = BreakIterator.getCharacterInstance().apply { setText(after) }
                caret + bi.following(0).let { if (it == BreakIterator.DONE) after.length else it }
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                // a line break above: the editor moves by line; none at all: to the start
                val before = ic.getTextBeforeCursor(LINE_WINDOW, 0)?.toString() ?: return null
                if (before.isEmpty()) return false
                if ('\n' in before || before.length >= LINE_WINDOW) { sendKey(code, 0); return true }
                caret - before.length
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                val after = ic.getTextAfterCursor(LINE_WINDOW, 0)?.toString() ?: return null
                if (after.isEmpty()) return false
                if ('\n' in after || after.length >= LINE_WINDOW) { sendKey(code, 0); return true }
                caret + after.length
            }
            else -> return false
        }
        if (target < 0 || target == caret) return null
        ic.setSelection(target, target)
        // the next step comes before the app reports this one
        selStart = target; selEnd = target; caretStale = false
        if (stepsAhead.size < 64) stepsAhead.addLast(target) else stepsAhead.clear()
        return true
    }

    /** 学术键盘 ⇥: to the next empty slot — (), {}, [] — or past the next separator. */
    fun nextSlot() { dirty();
        val after = ic?.getTextAfterCursor(400, 0)?.toString() ?: return
        val m = SLOT.find(after)
        val off = if (m != null) m.range.first + (if (m.value.endsWith(" ")) m.value.length else 1)
        else after.indexOfFirst { it in ")]}|‖⟩" }.let { if (it >= 0) it + 1 else after.length }
        moveBy(off)
    }

    // ---- 删除后联想: each committed character with the syllable that produced it, aligned with
    // the end of the text; deleting characters collects their syllables to offer the word again ----
    private val charLog = ArrayDeque<Pair<String, String>>()
    /** Syllables of the characters just deleted, in text order. */
    val deletedRun = ArrayDeque<String>()

    private fun logChars(text: String, syllables: List<String>?) {
        var si = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val ch = String(Character.toChars(cp))
            val syl = if (syllables != null && Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN) syllables.getOrNull(si++).orEmpty() else ""
            charLog.addLast(ch to syl)
            i += Character.charCount(cp)
        }
        while (charLog.size > 300) charLog.removeFirst()
        deletedRun.clear()
    }

    /** Called after one character was deleted: keeps the log aligned and collects syllables. */
    private fun onCharDeleted(ch: String) {
        val last = charLog.lastOrNull()
        if (last == null || last.first != ch) { charLog.clear(); deletedRun.clear(); return }
        charLog.removeLast()
        if (last.second.isNotEmpty()) deletedRun.addFirst(last.second) else deletedRun.clear()
    }

    companion object {
        val PAIRS = mapOf(
            "(" to ")", "（" to "）", "[" to "]", "【" to "】", "{" to "}", "«" to "»", "“" to "”", "‘" to "’",
            "《" to "》", "〈" to "〉", "「" to "」", "『" to "』", "〔" to "〕", "„" to "“", "⟨" to "⟩",
        )
        private val CLOSERS = PAIRS.values.toSet()
        /** Characters read beside the caret for one step: more than any one grapheme (a family emoji). */
        private const val NEAR = 16
        /** How far a step up or down looks for a line break before it lets the editor decide. */
        private const val LINE_WINDOW = 2000
        private val SLOT = Regex("\\{\\}|\\(\\)|\\[\\]|, |; |& |\\\\\\\\ ")
    }
}

/** The keyboard's own undo stack: what it typed, deleted, replaced or cleared. */
class EditHistory {
    sealed class Op {
        /** [text] went in before the caret, [tail] after it (the closing half of a pair). */
        data class Insert(val text: String, val tail: String = "") : Op()
        data class Delete(val text: String) : Op()
        data class Replace(val old: String, val text: String) : Op()
        data class Clear(val text: String, val cursor: Int) : Op()
    }

    private val ops = ArrayDeque<Op>()
    private var lastAt = 0L

    fun push(op: Op) {
        val now = SystemClock.uptimeMillis()
        val prev = ops.lastOrNull()
        // consecutive single deletions or insertions within a short pause undo together
        if (now - lastAt < 1500 && prev != null) {
            if (op is Op.Delete && prev is Op.Delete) { ops.removeLast(); ops.addLast(Op.Delete(op.text + prev.text)); lastAt = now; return }
            if (op is Op.Insert && prev is Op.Insert && op.tail.isEmpty() && prev.tail.isEmpty() && op.text.length <= 2 && !op.text.contains('\n')) {
                ops.removeLast(); ops.addLast(Op.Insert(prev.text + op.text)); lastAt = now; return
            }
        }
        ops.addLast(op)
        while (ops.size > 50) ops.removeFirst()
        lastAt = now
    }

    fun pop(): Op? = ops.removeLastOrNull().also { lastAt = 0 }
    fun clear() = ops.clear()
}
