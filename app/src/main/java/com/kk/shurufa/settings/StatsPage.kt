package com.kk.shurufa.settings

import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kk.shurufa.Prefs
import com.kk.shurufa.ime.TypingStats
import java.time.LocalDate
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 打字数据: how much was written this week and over the last month, at what time of day, how
 * (typed, spoken, pasted, by AI), the words used most as a cloud, and the favourite emoji. All of
 * it is counted on this phone only.
 */
@Composable
internal fun StatsPage(prefs: Prefs) {
    val ctx = LocalContext.current
    val stats = remember { TypingStats.get(ctx) }
    var snap by remember { mutableStateOf(stats.snapshot()) }
    var confirm by remember { mutableStateOf(false) }
    val c = MaterialTheme.colorScheme
    if (snap.total == 0) {
        Group {
            Column(Modifier.fillMaxWidth().padding(vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("还没有数据", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Note("用 KK输入法打几句字之后，这里会出现你每天打了多少字、什么时候打字最多、最常用的词。")
            }
        }
    } else {
        Hero(snap)
        Group("最近 30 天") { DayBars(snap) }
        Group("一天里的时段") { HourStrip(snap) }
        if (snap.words.size >= 5) Group("常用词") { WordCloud(snap.words) }
        Group("怎么输入的") { Sources(snap) }
        if (snap.emoji.isNotEmpty()) Group("常用表情") {
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for ((e, n) in snap.emoji.take(6)) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(e, fontSize = 28.sp)
                    Text("$n", style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant)
                }
            }
        }
    }
    Group("记录") {
        Toggle(prefs, Prefs.TYPING_STATS, "记录打字数据", "只统计字数、时段和常用词，不保存打过的句子；只存在这台手机上。密码、地址和要求不学习的输入框（比如无痕模式）一律不记", true)
        TextButton(enabled = snap.total > 0, onClick = { confirm = true }) { Text("清除全部数据") }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("清除打字数据？") },
        text = { Text("字数、时段和常用词都会删掉，不能恢复。") },
        confirmButton = { TextButton(onClick = { stats.clear(); snap = stats.snapshot(); confirm = false }) { Text("清除") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("取消") } },
    )
}

/** The home page's line about this week, which opens the page. */
@Composable
internal fun StatsSummary(onClick: () -> Unit) {
    val ctx = LocalContext.current
    val snap = remember { TypingStats.get(ctx).snapshot() }
    if (snap.total == 0) return
    val c = MaterialTheme.colorScheme
    val week = snap.last(7)
    Row(
        Modifier.fillMaxWidth().padding(bottom = 12.dp).clip(RoundedCornerShape(20.dp)).background(c.primaryContainer)
            .clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("本周 ${fmt(week.sumOf { it.second })} 字", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = c.onPrimaryContainer)
            val streak = snap.streak
            Text("今天 ${fmt(week.last().second)}" + if (streak > 1) " · 连续 $streak 天" else "", style = MaterialTheme.typography.bodySmall, color = c.onPrimaryContainer.copy(alpha = 0.75f))
        }
        // seven small bars, today's in full color
        val max = (week.maxOfOrNull { it.second } ?: 1).coerceAtLeast(1)
        Canvas(Modifier.width(70.dp).height(30.dp)) {
            val w = size.width / 7
            week.forEachIndexed { i, (_, n) ->
                val h = max(3.dp.toPx(), size.height * n / max)
                drawRoundRect(if (i == 6) c.primary else c.primary.copy(alpha = 0.35f), Offset(i * w + w * 0.18f, size.height - h), Size(w * 0.64f, h), CornerRadius(w * 0.32f))
            }
        }
    }
}

@Composable
private fun Hero(snap: TypingStats.Snapshot) {
    val c = MaterialTheme.colorScheme
    val week = snap.last(14)
    val thisWeek = week.takeLast(7).sumOf { it.second }
    val lastWeek = week.take(7).sumOf { it.second }
    Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 14.dp).clip(RoundedCornerShape(24.dp)).background(c.primaryContainer).padding(22.dp)) {
        Text("本周", style = MaterialTheme.typography.labelLarge, color = c.onPrimaryContainer.copy(alpha = 0.7f))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(fmt(thisWeek), fontSize = 44.sp, fontWeight = FontWeight.Bold, color = c.onPrimaryContainer)
            Text(" 字", style = MaterialTheme.typography.titleMedium, color = c.onPrimaryContainer, modifier = Modifier.padding(bottom = 8.dp))
            Spacer(Modifier.weight(1f))
            if (lastWeek > 0) {
                val pct = (thisWeek - lastWeek) * 100 / lastWeek
                Text((if (pct >= 0) "比上周多 $pct%" else "比上周少 ${-pct}%"), style = MaterialTheme.typography.bodySmall, color = c.onPrimaryContainer.copy(alpha = 0.75f), modifier = Modifier.padding(bottom = 10.dp))
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            Figure("今天", fmt(week.last().second))
            Figure("连续", "${snap.streak} 天")
            Figure("累计", fmt(snap.total))
        }
    }
}

@Composable
private fun Figure(label: String, value: String) {
    val c = MaterialTheme.colorScheme
    Column {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = c.onPrimaryContainer)
        Text(label, style = MaterialTheme.typography.labelSmall, color = c.onPrimaryContainer.copy(alpha = 0.65f))
    }
}

/** Thirty bars, today's in full color, the rest a lighter tone; the best day is named. */
@Composable
private fun DayBars(snap: TypingStats.Snapshot) {
    val c = MaterialTheme.colorScheme
    val days = snap.last(30)
    val max = (days.maxOfOrNull { it.second } ?: 1).coerceAtLeast(1)
    Canvas(Modifier.fillMaxWidth().height(120.dp).padding(top = 8.dp)) {
        val w = size.width / days.size
        val base = size.height
        // a hairline at the top for scale
        drawLine(c.outlineVariant, Offset(0f, 0f), Offset(size.width, 0f), 1f)
        days.forEachIndexed { i, (_, n) ->
            val h = if (n == 0) 2.dp.toPx() else max(4.dp.toPx(), base * n / max)
            val color = if (i == days.size - 1) c.primary else c.primary.copy(alpha = if (n == max) 0.75f else 0.38f)
            drawRoundRect(color, Offset(i * w + w * 0.2f, base - h), Size(w * 0.6f, h), CornerRadius(w * 0.3f))
        }
    }
    val best = days.maxByOrNull { it.second }
    Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Text(md(days.first().first), style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        if (best != null && best.second > 0) Text("最多：${md(best.first)} · ${fmt(best.second)} 字", style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Text("今天", style = MaterialTheme.typography.labelSmall, color = c.primary)
    }
}

/** The day's twenty-four hours as cells, darker where more was written. */
@Composable
private fun HourStrip(snap: TypingStats.Snapshot) {
    val c = MaterialTheme.colorScheme
    val h = snap.hours
    val max = (h.maxOrNull() ?: 1).coerceAtLeast(1)
    Canvas(Modifier.fillMaxWidth().height(34.dp).padding(top = 6.dp)) {
        val w = size.width / 24
        for (i in 0 until 24) {
            val t = sqrt(h[i].toFloat() / max)
            drawRoundRect(lerp(c.surfaceVariant, c.primary, t), Offset(i * w + 1.5f, 0f), Size(w - 3f, size.height), CornerRadius(4.dp.toPx()))
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        for (label in listOf("0点", "6点", "12点", "18点", "24点")) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant)
            if (label != "24点") Spacer(Modifier.weight(1f))
        }
    }
    val peak = h.indices.maxByOrNull { h[it] } ?: 0
    Note("最常在${part(peak)} $peak 点到 ${peak + 1} 点之间打字。")
}

/** Typed, spoken, pasted and AI-written characters as one bar in four tones, with a legend. */
@Composable
private fun Sources(snap: TypingStats.Snapshot) {
    val c = MaterialTheme.colorScheme
    val parts = listOf(
        // one hue in four depths: the parts read as one whole, typing the strongest
        Triple("打字", snap.bySource(TypingStats.Source.TYPED), c.primary),
        Triple("语音", snap.bySource(TypingStats.Source.VOICE), c.primary.copy(alpha = 0.6f)),
        Triple("粘贴", snap.bySource(TypingStats.Source.PASTE), c.primary.copy(alpha = 0.35f)),
        Triple("AI", snap.bySource(TypingStats.Source.AI), c.outline.copy(alpha = 0.6f)),
    ).filter { it.second > 0 }
    val total = parts.sumOf { it.second }.coerceAtLeast(1)
    Canvas(Modifier.fillMaxWidth().height(14.dp).padding(top = 2.dp).clip(RoundedCornerShape(7.dp))) {
        var x = 0f
        val seam = 2.dp.toPx()
        for ((k, part) in parts.withIndex()) {
            val w = size.width * part.second / total
            // a thin seam of background between the parts
            drawRect(part.third, Offset(x, 0f), Size((w - if (k < parts.size - 1) seam else 0f).coerceAtLeast(0f), size.height)); x += w
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        for ((name, n, color) in parts) Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(6.dp))
            Text("$name ${n * 100 / total}%", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * The words used most, larger the more they were used, laid out from the middle outward along a
 * spiral so the biggest sit together in the center. The most used take the accent; the rest fade
 * toward the text color.
 */
@Composable
private fun WordCloud(words: List<Pair<String, Int>>) {
    val c = MaterialTheme.colorScheme
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth().height(260.dp).padding(vertical = 6.dp)) {
        val wPx = with(density) { maxWidth.toPx() }
        val hPx = with(density) { maxHeight.toPx() }
        val small = with(density) { 13.sp.toPx() }
        val large = with(density) { 40.sp.toPx() }
        val placed = remember(words, wPx, hPx) { cloud(words.take(60), wPx, hPx, small, large) }
        val ink = c.onSurface; val soft = c.onSurfaceVariant; val accent = c.primary
        Canvas(Modifier.fillMaxWidth().height(maxHeight)) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
            placed.forEachIndexed { i, p ->
                paint.textSize = p.size
                paint.typeface = if (i < 3) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                val t = i.toFloat() / placed.size
                paint.color = (if (i < 3) accent else lerp(lerp(accent, ink, 0.55f), soft, t)).toArgb()
                drawContext.canvas.nativeCanvas.drawText(p.word, p.x, p.y, paint)
            }
        }
    }
}

private class Placed(val word: String, val size: Float, val x: Float, val y: Float)

/** Places [words] on a spiral from the center, each where it overlaps nothing placed before it. */
private fun cloud(words: List<Pair<String, Int>>, w: Float, h: Float, small: Float, large: Float): List<Placed> {
    if (words.isEmpty() || w <= 0f) return emptyList()
    val top = words.first().second.toFloat()
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val taken = ArrayList<RectF>()
    val out = ArrayList<Placed>()
    val gap = small * 0.25f
    for ((i, pair) in words.withIndex()) {
        val (word, n) = pair
        val size = small + (large - small) * sqrt(n / top)
        paint.textSize = size
        paint.typeface = if (i < 3) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        val tw = paint.measureText(word); val fm = paint.fontMetrics
        val th = fm.descent - fm.ascent
        var t = 0f
        while (t < 120f) {
            // an ellipse as wide as the space, so the cloud fills a wide card
            val r = t * small * 0.12f
            val cx = w / 2 + r * cos(t) * (w / h).coerceAtMost(2.2f)
            val cy = h / 2 + r * sin(t)
            val rect = RectF(cx - tw / 2 - gap, cy - th / 2 - gap, cx + tw / 2 + gap, cy + th / 2 + gap)
            if (rect.left >= 0 && rect.right <= w && rect.top >= 0 && rect.bottom <= h && taken.none { RectF.intersects(it, rect) }) {
                taken += rect
                out += Placed(word, size, cx, cy - (fm.ascent + fm.descent) / 2)
                break
            }
            t += 0.18f
        }
    }
    return out
}

private fun fmt(n: Int): String = "%,d".format(n)
private fun md(d: LocalDate) = "${d.monthValue}月${d.dayOfMonth}日"
private fun part(hour: Int) = when (hour) { in 5..10 -> "上午"; in 11..12 -> "中午"; in 13..17 -> "下午"; in 18..22 -> "晚上"; else -> "深夜" }
