package com.kk.shurufa

import com.kk.shurufa.ime.CloudPredict
import org.junit.Assert.assertEquals
import org.junit.Test

/** 云联想: what comes back is cleaned into at most three continuations. */
class CloudPredictTest {
    @Test fun parsesLines() {
        assertEquals(listOf("玩几天比较好", "找个时间去吧", "带我去好不好"),
            CloudPredict.parse("1. 玩几天比较好\n2. 找个时间去吧\n3.「带我去好不好」\n4. 还有一个", "我想去北京"))
        assertEquals(listOf("помощь", "всё"), CloudPredict.parse("- помощь\n- всё\n\n- помощь", "спасибо за "))
        // a line that only repeats the end of the text is dropped
        assertEquals(listOf("you doing today"), CloudPredict.parse("how are\nyou doing today", "how are"))
    }

    /** Text that goes on the way a continuation said gets the rest of it, without asking. */
    @Test fun continuesWhatWasSaid() {
        val given = listOf("我今天" to listOf("想去看电影", "有点累", "想吃火锅"))
        assertEquals(listOf("看电影"), CloudPredict.continuation(given, "我今天想去"))
        assertEquals(listOf("去看电影", "吃火锅"), CloudPredict.continuation(given, "我今天想"))
        assertEquals(listOf("to the park"), CloudPredict.continuation(listOf("I am " to listOf("going to the park", "fine")), "I am going "))
        // all of it typed, or something else: nothing
        assertEquals(null, CloudPredict.continuation(given, "我今天有点累"))
        assertEquals(null, CloudPredict.continuation(given, "我今天不去"))
        assertEquals(null, CloudPredict.continuation(given, "我今天"))
    }

    @Test fun sentenceEnds() {
        for (t in listOf("好的。", "真的吗？", "我想想，", "OK. ", "Да!")) assertEquals(t, true, CloudPredict.sentenceEnd(t))
        for (t in listOf("我想去", "how are ", "")) assertEquals(t, false, CloudPredict.sentenceEnd(t))
    }
}
