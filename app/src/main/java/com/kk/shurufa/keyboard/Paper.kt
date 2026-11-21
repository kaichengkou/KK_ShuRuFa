package com.kk.shurufa.keyboard

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Shader
import kotlin.random.Random

/**
 * 纸纹: the grain of rice paper under the 纸墨 keys. A small tile of soft specks and fibres, made
 * once from a fixed seed and repeated; a few percent of opacity, so it is there when looked for
 * and never in the way of the keys.
 */
object Paper {
    private const val SIZE = 160
    private var tile: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    private fun tile(): Bitmap = tile ?: Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ALPHA_8).also { b ->
        val rnd = Random(1913)
        val px = ByteArray(SIZE * SIZE)
        // specks of differing weight
        for (i in px.indices) px[i] = (rnd.nextInt(0, 38) * rnd.nextInt(0, 2)).toByte()
        // a few fibres: short soft strokes
        repeat(26) {
            var x = rnd.nextFloat() * SIZE; var y = rnd.nextFloat() * SIZE
            val a = rnd.nextFloat() * Math.PI.toFloat()
            val dx = kotlin.math.cos(a); val dy = kotlin.math.sin(a)
            repeat(rnd.nextInt(10, 28)) {
                val ix = ((x.toInt() % SIZE) + SIZE) % SIZE; val iy = ((y.toInt() % SIZE) + SIZE) % SIZE
                px[iy * SIZE + ix] = 70
                x += dx; y += dy
            }
        }
        b.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(px))
        tile = b
    }

    /** Lays the grain over [w]×[h]: dark specks on paper, light ones on ink. */
    fun grain(c: Canvas, w: Int, h: Int, dark: Boolean) {
        if (w <= 0 || h <= 0) return
        if (paint.shader == null) paint.shader = BitmapShader(tile(), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        paint.color = if (dark) 0x14FFFFFF else 0x1C5B4A36
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
    }
}
