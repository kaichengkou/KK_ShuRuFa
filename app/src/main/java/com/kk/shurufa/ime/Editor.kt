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
    fun commitImage(uri: android.net.Uri, mime: String): Boolean {
        val info = ime.currentInputEditorInfo ?: return false
        val ic = ic ?: return false
        val accepted = EditorInfoCompat.getContentMimeTypes(info)
        if (accepted.none { android.content.ClipDescription.compareMimeTypes(mime, it) }) return false
        val content = InputContentInfoCompat(uri, android.content.ClipDescription("图片", arrayOf(mime)), null)
        return InputConnectionCompat.commitContent(ic, info, content, InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null)
    }

    /** Selection as last reported by the editor. */
    var selStart = 0
        private set
    var selEnd = 0
        private set
    val hasSelection get() = selStart != selEnd
    /** 选择模式 of the edit panel: caret moves extend the selection. */
    var selectMode = false

    fun onSelection(start: Int, end: Int) { selStart = start; selEnd = end }

    /** Drops the selection to a caret at its end. */
    fun collapseSelection() { ic?.setSelection(selEnd, selEnd) }

    /** A new field: nothing to undo, nothing selected on purpose. */
    fun reset() { history.clear(); selectMode = false; charLog.clear(); deletedRun.clear() }

    fun textBefore(n: Int) = ic?.getTextBeforeCursor(n, 0)?.toString().orEmpty()
    fun textAfter(n: Int) = ic?.getTextAfterCursor(n, 0)?.toString().orEmpty()
    fun selectedText(): String? = ic?.getSelectedText(0)?.toString()

    /** Absolute selection (start, end) in the field, or null when the editor does not say. */
    fun selection(): Pair<Int, Int>? {
        val et = ic?.getExtractedText(ExtractedTextRequest(), 0) ?: return null
        return (et.startOffset + minOf(et.selectionStart, et.selectionEnd)) to (et.startOffset + maxOf(et.selectionStart, et.selectionEnd))
    }

    /** All of the field's text, when the editor shares it. */
    fun allText(): String? = ic?.getExtractedText(ExtractedTextRequest(), 0)?.text?.toString()

    // ---- writing ----

    /**
     * Types [text]: commits it (pairing brackets when [pairable]) and records it for undo.
     * [syllables] are the pinyin that produced its Chinese characters (for 删除后联想).
     */
    fun type(text: String, pairable: Boolean = true, syllables: List<String>? = null) {
        val tail = commit(text, pairable, syllables) ?: return
        history.push(EditHistory.Op.Insert(tail.first, tail.second))
    }

    /**
     * Commits [text] without recording it; `‸` marks where the caret should land. Returns what
     * went in before and after the caret, or null when nothing did.
     */
    fun commit(text: String, pairable: Boolean = true, syllables: List<String>? = null): Pair<String, String>? {
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
        if (idx < 0 || idx >= clean.length) { ic.commitText(clean, 1); return clean to "" }
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
    fun replace(text: String, wholeField: Boolean) {
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
    }

    /** Puts [text] after the selection (or at the caret), leaving the selection's text alone. */
    fun insertAfterSelection(text: String) {
        val ic = ic ?: return
        ic.beginBatchEdit()
        selection()?.let { ic.setSelection(it.second, it.second) }
        ic.commitText(text, 1)
        ic.endBatchEdit()
        history.push(EditHistory.Op.Insert(text))
    }

    /** Shows [text] as composing text (replaced by the next compose or commit). */
    fun compose(text: String) { ic?.setComposingText(text, 1) }

    fun finishComposingText() { ic?.finishComposingText() }

    /** Replaces the composing preview with a finished sentence, after deleting [deleteBefore] characters. */
    fun commitVoice(deleteBefore: Int, text: String) {
        val ic = ic ?: return
        ic.setComposingText("", 1)
        ic.beginBatchEdit()
        if (deleteBefore > 0) ic.deleteSurroundingText(deleteBefore, 0)
        ic.commitText(text, 1)
        ic.endBatchEdit()
        history.push(EditHistory.Op.Insert(text))
    }

    /** Deletes [text] when it is right before the caret. */
    fun deleteIfBefore(text: String) {
        if (text.isNotEmpty() && textBefore(text.length) == text) ic?.deleteSurroundingText(text.length, 0)
    }

    /** Deletes [before] characters, then types [text]: one step for undo. */
    fun replaceBefore(before: Int, text: String) {
        val ic = ic ?: return
        val old = textBefore(before)
        ic.beginBatchEdit()
        ic.deleteSurroundingText(before, 0)
        ic.commitText(text, 1)
        ic.endBatchEdit()
        history.push(EditHistory.Op.Replace(old, text))
    }

    fun deleteBackward() {
        val ic = ic ?: return
        if (ime.currentInputEditorInfo?.inputType == InputType.TYPE_NULL) { ime.sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL); return }
        val sel = ic.getSelectedText(0)
        if (!sel.isNullOrEmpty()) { history.push(EditHistory.Op.Delete(sel.toString())); ic.commitText("", 1); return }
        val before = ic.getTextBeforeCursor(2, 0)?.toString().orEmpty()
        if (before.isEmpty()) return
        val after = ic.getTextAfterCursor(1, 0)?.toString()
        val last = before.substring(before.offsetByCodePoints(before.length, -1))
        // an empty bracket pair goes together
        if (prefs.autoPair && PAIRS[last] == after) { ic.deleteSurroundingText(last.length, 1); return }
        history.push(EditHistory.Op.Delete(last))
        ic.deleteSurroundingTextInCodePoints(1, 0)
        onCharDeleted(last)
    }

    /** 上滑清空: the whole field, undoable. Returns false when there was nothing to clear. */
    fun clearAll(): Boolean {
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
    fun undo() {
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

    fun performEnter() {
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

    fun contextMenu(id: Int) = ic?.performContextMenuAction(id)

    // ---- the caret ----

    fun sendKey(code: Int, meta: Int = 0) {
        val ic = ic ?: return
        val t = SystemClock.uptimeMillis()
        ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0, meta))
        ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0, meta))
    }

    /** Shift for caret keys while selecting. */
    fun shiftMeta() = if (selectMode) KeyEvent.META_SHIFT_ON else 0

    fun moveBy(n: Int) {
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
        val ic = ic ?: return false
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

    /** 学术键盘 ⇥: to the next empty slot — (), {}, [] — or past the next separator. */
    fun nextSlot() {
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
