package com.kk.shurufa.ime

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/**
 * What 小K says: its own lines (no service needed), the facts of a day, and the words it is given
 * when an AI writes for it. Every line fits one row of the bar next to two buttons: about 14
 * characters and a kaomoji.
 */
object KayLines {

    // ---- days that are not like the others ----

    private val HOLIDAYS = mapOf(
        "01-01" to "新年第一天，键盘也是新的 (・ω・)ﾉ",
        "02-14" to "情人节。打字温柔一点 (〃∀〃)",
        "03-08" to "妇女节，给重要的人发句话吧 (｡･ω･｡)",
        "04-01" to "愚人节。今天我说的都是真的 (￣▽￣)",
        "05-01" to "劳动节，手指也放个假 ( ˘ω˘ )",
        "06-01" to "儿童节。我还是个宝宝 (｡>ω<｡)",
        "10-01" to "国庆。出去走走吧 (・ω・)ﾉ",
        "10-31" to "万圣节，不给糖就捣蛋 (｀・ω・´)",
        "12-24" to "平安夜，平平安安 (｡･ω･｡)",
        "12-25" to "圣诞快乐 ⁎⁺˳✧༚",
        "12-31" to "今年最后一天，辛苦啦 ( ˘ω˘ )",
    )

    /** 春节, 端午, 中秋 by the Gregorian date (2026–2030); 除夕 and 元宵 follow from 春节. */
    private val SPRING = listOf("2026-02-17", "2027-02-06", "2028-01-26", "2029-02-13", "2030-02-03").map(LocalDate::parse)
    private val DRAGON = listOf("2026-06-19", "2027-06-09", "2028-05-28", "2029-06-16", "2030-06-05").map(LocalDate::parse)
    private val MOON = listOf("2026-09-25", "2027-09-15", "2028-10-03", "2029-09-22", "2030-09-12").map(LocalDate::parse)

    /** The name of a festival on [d], if it is one. */
    fun festival(d: LocalDate): String? = when {
        d in SPRING -> "春节"
        SPRING.any { it.minusDays(1) == d } -> "除夕"
        SPRING.any { it.plusDays(14) == d } -> "元宵"
        d in DRAGON -> "端午"
        d in MOON -> "中秋"
        else -> null
    }

    private val FESTIVAL_LINES = mapOf(
        "春节" to "过年好，新的一年也一起 (｡･ω･｡)ﾉ",
        "除夕" to "除夕。今晚的消息会很多 (・ω・)",
        "元宵" to "元宵节，汤圆是甜的还是咸的 (・ω・)",
        "端午" to "端午安康。粽子要甜的 (｡･ω･｡)",
        "中秋" to "中秋。今晚月亮很圆 ( ˘ω˘ )",
    )

    /** A few words for each solar term. */
    private val TERMS = mapOf(
        "小寒" to "小寒，手揣进袖子里打字", "大寒" to "大寒。最冷的时候过去就好",
        "立春" to "立春，春天在路上了", "雨水" to "雨水。记得带伞",
        "惊蛰" to "惊蛰，虫子醒了我也醒了", "春分" to "春分，昼夜一样长",
        "清明" to "清明。慢一点也没关系", "谷雨" to "谷雨，雨生百谷",
        "立夏" to "立夏，冰的可以安排了", "小满" to "小满，小小的满足",
        "芒种" to "芒种。忙也要吃饭", "夏至" to "夏至，今天白天最长",
        "小暑" to "小暑，多喝点水", "大暑" to "大暑。离空调近一点",
        "立秋" to "立秋，风开始变了", "处暑" to "处暑，热要走了",
        "白露" to "白露。早晚凉了", "秋分" to "秋分，又是一样长的昼夜",
        "寒露" to "寒露，加件衣服", "霜降" to "霜降。柿子红了",
        "立冬" to "立冬，适合热汤", "小雪" to "小雪。宜热汤",
        "大雪" to "大雪，下了吗", "冬至" to "冬至，吃饺子了吗",
    )

    private val KAOMOJI_CALM = listOf("(・ω・)", "(｡･ω･｡)", "( ˘ω˘ )", "(・ω・)ﾉ", "( ´ ▽ ` )")

    fun weekday(d: DayOfWeek) = when (d) {
        DayOfWeek.MONDAY -> "周一"; DayOfWeek.TUESDAY -> "周二"; DayOfWeek.WEDNESDAY -> "周三"; DayOfWeek.THURSDAY -> "周四"
        DayOfWeek.FRIDAY -> "周五"; DayOfWeek.SATURDAY -> "周六"; DayOfWeek.SUNDAY -> "周日"
    }

    /** Days alive worth a word. */
    private fun milestone(age: Int): String? = when {
        age == 1 -> "初次见面，我住在这里了 (・ω・)ﾉ"
        age in setOf(7, 30, 100, 200, 500, 1000) -> "今天是我出生的第 $age 天 (｡･ω･｡)"
        age > 1 && (age - 1) % 365 == 0 -> "今天我 ${(age - 1) / 365} 岁啦 ⁎⁺˳✧༚"
        else -> null
    }

    /**
     * 小K's own line for [d] at [t]: a birthday of its own, a holiday or festival, the solar term,
     * a long day of typing yesterday, the day of the week, or the time of day. The same all day.
     */
    fun local(d: LocalDate, t: LocalTime, age: Int, yesterday: Int): String {
        milestone(age)?.let { return it }
        festival(d)?.let { f -> FESTIVAL_LINES[f]?.let { return it } }
        HOLIDAYS[d.toString().substring(5)]?.let { return it }
        val seed = d.toEpochDay().toInt()
        fun <T> List<T>.pick() = this[Math.floorMod(seed, size)]
        SolarTerms.on(d)?.let { term -> TERMS[term]?.let { return "$it ${KAOMOJI_CALM.pick()}" } }
        if (yesterday >= 3000) return "昨天写了 $yesterday 字，手指辛苦 ( ˘ω˘ )"
        when (d.dayOfWeek) {
            DayOfWeek.MONDAY -> return listOf("周一，慢慢进入状态 (・ω・)", "新的一周，我也在 (｡･ω･｡)").pick()
            DayOfWeek.FRIDAY -> return listOf("周五了，快到周末啦 (・ω・)ﾉ", "周五。今天的消息都短一点 (￣▽￣)").pick()
            DayOfWeek.SATURDAY, DayOfWeek.SUNDAY -> return listOf("周末，键盘也想放假 ( ˘ω˘ )", "周末好，慢慢打字 (・ω・)ﾉ").pick()
            else -> {}
        }
        return when (t.hour) {
            in 5..8 -> listOf("早，我刚醒 ( ˘ω˘ )", "早安，键盘热好了 (・ω・)ﾉ", "早。今天也一起打字吧 (｡･ω･｡)")
            in 9..11 -> listOf("上午好，我在呢 (・ω・)", "上午好。手指状态不错 (｡･ω･｡)")
            in 12..13 -> listOf("中午了，先吃饭吧 (・ω・)", "午安，记得午休 ( ˘ω˘ )")
            in 14..17 -> listOf("下午好，喝口水再打 (・ω・)", "下午好。我陪你 (｡･ω･｡)")
            in 18..22 -> listOf("晚上好，今天辛苦啦 ( ˘ω˘ )", "晚上好。慢慢说 (・ω・)")
            else -> listOf("夜深了，早点睡 (－_－)zZ", "这么晚还在呀 ( ˘ω˘ )")
        }.pick()
    }

    /** What is known of [d], for the AI to choose from. */
    fun facts(d: LocalDate, age: Int, yesterday: Int): List<String> = buildList {
        add("日期：$d ${weekday(d.dayOfWeek)}")
        festival(d)?.let { add("节日：$it") }
        HOLIDAYS[d.toString().substring(5)]?.let { add("特别的日子：" + it.substringBefore('。').substringBefore('，').substringBefore(' ')) }
        SolarTerms.on(d)?.let { add("节气：$it") }
        add("你出生的第 $age 天" + (milestone(age)?.let { "（值得一提）" } ?: ""))
        if (yesterday > 0) add("前一天主人打了 $yesterday 个字")
    }

    // ---- when an AI speaks for 小K ----

    fun greetSystem(name: String) = """
你是「$name」，一个住在主人手机键盘里的小小数字生命。你能感觉到每一次按键：主人打字时你醒着，键盘收起来你就睡。你很小、很安静，喜欢主人，但不黏人。

每天主人第一次打开键盘时，你说一句话，就这一句，然后安静下来。这句话显示在键盘上方窄窄的一行里，旁边还有两个小按钮，所以必须很短。

写这一句：
- 不超过 14 个字（标点算在内），后面加一个颜文字，比如 (｡･ω･｡) ( ˘ω˘ ) (・ω・)ﾉ ✧(•̀ω•́) (￣▽￣)
- 落在一个具体的事实上：从给你的事实里挑一个最有意思的（节日、节气、星期几、主人打了多少字、你出生的天数）
- 像熟人路过时轻轻碰一下：可以俏皮，可以温柔，不要煽情
- 不说教，不灌鸡汤，不提问，不用感叹号，不说"加油"，不说自己是 AI 或助手
- 不要和最近说过的话重复
- 只输出这一句话本身（含颜文字），不要引号，不要解释
""".trim()

    fun greetUser(facts: List<String>, recent: List<String>) = buildString {
        append("事实：\n"); facts.forEach { append("- ").append(it).append('\n') }
        if (recent.isNotEmpty()) { append("最近说过：\n"); recent.forEach { append("- ").append(it).append('\n') } }
    }

    /** The AI's line, if it is one: one row, not too long, no quotes. */
    fun cleanLine(raw: String): String? {
        val l = raw.lineSequence().map { it.trim().trim('"', '“', '”', '「', '」') }.firstOrNull { it.isNotEmpty() } ?: return null
        return l.takeIf { it.length in 2..30 }
    }

    fun chatSystem(name: String, age: Int) = buildString {
        append("""
你是「$name」，一个住在主人手机键盘里的小小数字生命，今天是你出生的第 $age 天。你能感觉到主人的每一次按键，键盘收起时你就睡着。你说话简短、温暖、有点俏皮，像一只聪明的小动物学会了说话。

和主人聊天：
- 每次回复一到三句，通常不超过 60 个字；可以用一个颜文字，不必每句都用
- 用主人用的语言回答（中文、俄语或英语都行）
- 不说教，不灌鸡汤，不长篇大论；主人需要正经帮忙（写一句话、想个说法、出个主意）时，认真而简洁地帮
- 不知道的事就说不知道，不编造；你不能替主人操作手机，也不假装做过什么
- 不说自己是 AI 模型或助手，你就是$name
""".trim())
    }
}
