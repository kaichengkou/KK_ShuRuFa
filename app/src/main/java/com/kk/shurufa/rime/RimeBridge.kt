package com.kk.shurufa.rime

/** Native entry points implemented in app/src/main/jni/kk_rime/kk_rime.cc. */
object RimeBridge {
    init { System.loadLibrary("kk_rime") }

    external fun startup(sharedDir: String, userDir: String, fullCheck: Boolean)
    external fun joinMaintenance()
    external fun shutdown()
    external fun processKey(keycode: Int, mask: Int): Boolean
    external fun selectCandidateGlobal(index: Int): Boolean
    external fun commitComposition(): Boolean
    external fun clearComposition()
    external fun setOption(name: String, value: Boolean)
    external fun selectSchema(id: String): Boolean
    external fun candidates(limit: Int): Array<String>
    /** The first [limit] candidates' texts, then their comments, from one pass over the menu. */
    external fun candidatesWithComments(limit: Int): Array<String>
    /** Changes whenever the composition does: what was fetched at one generation is still the menu's. */
    external fun generation(): Int
    external fun deleteCandidate(index: Int): Boolean
    external fun highlightCandidate(index: Int): Boolean
    external fun syncUserData(): Boolean
    external fun learn(input: String, text: String): Boolean
    external fun setInput(input: String): Boolean
    external fun caretPos(): Int
    external fun setCaretPos(pos: Int)
    external fun query(input: String, limit: Int): Array<String>
    /** Traditional to Simplified Chinese with OpenCC ([config] is a t2s.json path). */
    external fun t2s(text: String, config: String): String
    external fun commitText(): String
    external fun preedit(): String
    external fun input(): String
    external fun cursor(): Int

    // X11 keysyms used by librime
    const val XK_BackSpace = 0xff08
    const val XK_Return = 0xff0d
    const val XK_space = 0x20

    /**
     * The first [n] candidates and their comments, fetched once per change of the composition: the
     * bar, the opened grid and the long-press menus all read the same menu. Fetching more extends
     * what was fetched (Rime walks its menu from the start again, but only when more is asked for).
     */
    fun menu(n: Int): Menu {
        val gen = generation()
        val m = cached
        if (m != null && m.generation == gen && (m.texts.size >= n || m.complete)) return m
        val all = candidatesWithComments(n)
        val k = all.size / 2
        return Menu(gen, all.copyOfRange(0, k).asList(), all.copyOfRange(k, 2 * k).asList(), complete = k < n).also { cached = it }
    }
    @Volatile private var cached: Menu? = null

    /** Candidates fetched at [generation]; [complete] when the menu has no more than these. */
    class Menu(val generation: Int, val texts: List<String>, val comments: List<String>, val complete: Boolean) {
        fun take(n: Int): Pair<List<String>, List<String>> = texts.take(n) to comments.take(n)
    }
}
