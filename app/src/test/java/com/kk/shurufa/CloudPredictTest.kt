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
}
