package com.kk.shurufa.rime

/** Native entry points implemented in app/src/main/jni/kk_rime/kk_rime.cc. */
object RimeBridge {
    init { System.loadLibrary("kk_rime") }

    external fun startup(sharedDir: String, userDir: String, fullCheck: Boolean)
    external fun isMaintaining(): Boolean
    external fun joinMaintenance()
    external fun shutdown()
    external fun processKey(keycode: Int, mask: Int): Boolean
    external fun selectCandidate(index: Int): Boolean
    external fun selectCandidateGlobal(index: Int): Boolean
    external fun changePage(backward: Boolean): Boolean
    external fun commitComposition(): Boolean
    external fun clearComposition()
    external fun setOption(name: String, value: Boolean)
    external fun getOption(name: String): Boolean
    external fun selectSchema(id: String): Boolean
    external fun candidates(limit: Int): Array<String>
    external fun deleteCandidate(index: Int): Boolean
    external fun highlightCandidate(index: Int): Boolean
    external fun setInput(input: String): Boolean
    external fun candidateComments(limit: Int): Array<String>
    external fun caretPos(): Int
    external fun setCaretPos(pos: Int)
    external fun query(input: String, limit: Int): Array<String>
    /** Traditional to Simplified Chinese with OpenCC ([config] is a t2s.json path). */
    external fun t2s(text: String, config: String): String
    external fun commitText(): String
    external fun preedit(): String
    external fun input(): String
    external fun cursor(): Int
    external fun pageCandidates(): Array<String>
    external fun pageComments(): Array<String>
    external fun highlighted(): Int
    external fun isLastPage(): Boolean
    external fun pageNo(): Int
    external fun schemaId(): String
    external fun schemaName(): String

    // X11 keysyms used by librime
    const val XK_BackSpace = 0xff08
    const val XK_Return = 0xff0d
    const val XK_Escape = 0xff1b
    const val XK_space = 0x20
}
