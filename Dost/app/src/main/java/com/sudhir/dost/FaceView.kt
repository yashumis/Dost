package com.sudhir.dost

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

val EMOS = setOf("happy", "laugh", "sad", "surprised", "thinking", "love", "calm", "sleepy", "angry", "dizzy", "ouch", "asleep")

/** The cartoon face. Draws itself every frame: blinks, looks around, breathes, and moves its mouth with the voice. */
class FaceView(ctx: Context) : View(ctx) {

    var emotion = "happy"
        private set
    private var emoUntil = 0L
    var talking = false
        set(v) {
            field = v
            if (v) lastPulse = now() else level = -1f
        }
    var listening = false
    var thinking = false
    var snoring = false
    private var shakeUntil = 0L
    private var lookHoldUntil = 0L

    private var level = -1f          // real audio loudness 0..1 (premium voice); -1 = not available
    private var lastPulse = 0L       // start of the current spoken word
    private var mouth = 0f
    private var nextBlink = now() + 1800
    private var blinkStart = 0L
    private var lookX = 0f
    private var lookY = 0f
    private var lookTX = 0f
    private var lookTY = 0f
    private var nextLook = 0L
    private var bounceUntil = 0L

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val head = RadialGradient(
        78f, 66f, 115f,
        intArrayOf(0xFFFFD36B.toInt(), 0xFFFFB627.toInt(), 0xFFE8961A.toInt()),
        floatArrayOf(0f, 0.65f, 1f), Shader.TileMode.CLAMP
    )
    private val path = Path()
    private val r = RectF()

    private val dark = 0xFF10302F.toInt()
    private val lip = 0xFF7A2A10.toInt()
    private val brow = 0xFF7A4A08.toInt()

    // left brow x0,y0,cx,cy,x1,y1 then right brow
    private val brows = mapOf(
        "happy" to floatArrayOf(58f, 66f, 72f, 58f, 86f, 66f, 114f, 66f, 128f, 58f, 142f, 66f),
        "laugh" to floatArrayOf(58f, 62f, 72f, 54f, 86f, 62f, 114f, 62f, 128f, 54f, 142f, 62f),
        "sad" to floatArrayOf(58f, 62f, 72f, 66f, 86f, 70f, 114f, 70f, 128f, 66f, 142f, 62f),
        "surprised" to floatArrayOf(58f, 54f, 72f, 44f, 86f, 54f, 114f, 54f, 128f, 44f, 142f, 54f),
        "thinking" to floatArrayOf(58f, 66f, 72f, 66f, 86f, 68f, 114f, 58f, 128f, 50f, 142f, 58f),
        "love" to floatArrayOf(58f, 64f, 72f, 56f, 86f, 64f, 114f, 64f, 128f, 56f, 142f, 64f),
        "calm" to floatArrayOf(58f, 68f, 72f, 64f, 86f, 68f, 114f, 68f, 128f, 64f, 142f, 68f),
        "sleepy" to floatArrayOf(58f, 70f, 72f, 68f, 86f, 70f, 114f, 70f, 128f, 68f, 142f, 70f),
        "asleep" to floatArrayOf(58f, 72f, 72f, 70f, 86f, 72f, 114f, 72f, 128f, 70f, 142f, 72f),
        "angry" to floatArrayOf(56f, 58f, 72f, 62f, 88f, 74f, 112f, 74f, 128f, 62f, 144f, 58f),
        "dizzy" to floatArrayOf(58f, 60f, 72f, 54f, 86f, 62f, 114f, 64f, 128f, 54f, 142f, 58f),
        "ouch" to floatArrayOf(58f, 72f, 72f, 62f, 86f, 66f, 114f, 66f, 128f, 62f, 142f, 72f)
    )

    private fun now() = System.currentTimeMillis()

    fun setEmotion(e: String, holdMs: Long = 0) {
        emotion = if (e in EMOS) e else "happy"
        emoUntil = if (holdMs > 0) now() + holdMs else 0
        if (emotion == "laugh" || emotion == "surprised") bounceUntil = now() + 1300
        invalidate()
    }

    /** Called at the start of every spoken word, so the mouth stays in time with the voice. */
    fun pulse() { lastPulse = now() }

    /** Shake the head left-right (for "no no" and when punched). */
    fun shake(ms: Long) { shakeUntil = now() + ms }

    /** Converts a touch point on this view into face drawing units (0..200). */
    fun toFace(x: Float, y: Float): FloatArray {
        val s = min(width, height) / 200f
        if (s <= 0f) return floatArrayOf(100f, 100f)
        return floatArrayOf((x - (width - 200f * s) / 2f) / s, (y - (height - 200f * s) / 2f) / s)
    }

    /** Eyes look at a finger touching this view. */
    fun lookAtView(x: Float, y: Float) {
        val f = toFace(x, y)
        lookDir((f[0] - 100f) / 60f, (f[1] - 92f) / 60f)
    }

    /** Eyes look in a direction (-1..1). */
    fun lookDir(dx: Float, dy: Float) {
        lookTX = dx.coerceIn(-1f, 1f)
        lookTY = dy.coerceIn(-1f, 1f)
        lookHoldUntil = now() + 1200
        nextLook = lookHoldUntil
    }

    /** Real loudness of the voice (premium voice). */
    fun audioLevel(l: Float) { level = l }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val t = now()
        if (emoUntil > 0 && t > emoUntil) { emotion = "happy"; emoUntil = 0 }
        val s = min(width, height) / 200f
        if (s <= 0f) return

        c.save()
        c.translate((width - 200f * s) / 2f, (height - 200f * s) / 2f)
        c.scale(s, s)

        // ground shadow
        fill.shader = null
        fill.color = 0x33000000
        r.set(48f, 180f, 152f, 194f)
        c.drawOval(r, fill)

        val bounce = if (t < bounceUntil) sin(t / 45.0).toFloat() * 4f else 0f
        val breathe = sin(t / (if (emotion == "asleep") 1100.0 else 700.0)).toFloat() * (if (emotion == "asleep") 2.5f else 1.3f)
        val shake = if (t < shakeUntil) sin(t / 35.0).toFloat() * 7f else 0f
        c.translate(shake, bounce + breathe)
        if (emotion == "dizzy") c.rotate(sin(t / 140.0).toFloat() * 9f, 100f, 100f)

        // listening ring
        if (listening) {
            val a = (120 + 100 * sin(t / 180.0)).toInt().coerceIn(20, 230)
            line.color = Color.argb(a, 255, 90, 95)
            line.strokeWidth = 5f
            c.drawCircle(100f, 100f, 90f, line)
        }

        // ears + head
        fill.color = 0xFFE8961A.toInt()
        c.drawCircle(24f, 100f, 16f, fill)
        c.drawCircle(176f, 100f, 16f, fill)
        fill.shader = head
        c.drawCircle(100f, 100f, 80f, fill)
        fill.shader = null

        // shine on the head
        line.color = 0x55FFFFFF
        line.strokeWidth = 7f
        path.reset(); path.moveTo(56f, 48f); path.quadTo(100f, 16f, 144f, 48f)
        c.drawPath(path, line)

        // cheeks
        fill.color = when (emotion) {
            "angry" -> 0xCCFF3B3B.toInt()
            "love", "laugh" -> 0xAAFF6B70.toInt()
            else -> 0x66FF7A7F
        }
        r.set(42f, 116f, 72f, 133f); c.drawOval(r, fill)
        r.set(128f, 116f, 158f, 133f); c.drawOval(r, fill)

        // eyes wander like a living face
        if (t > nextLook && t > lookHoldUntil) {
            lookTX = (Math.random() * 2 - 1).toFloat() * 0.8f
            lookTY = (Math.random() * 2 - 1).toFloat() * 0.5f
            if (talking || listening) { lookTX *= 0.3f; lookTY *= 0.3f }
            nextLook = t + 1400 + (Math.random() * 3200).toLong()
        }
        lookX += (lookTX - lookX) * 0.15f
        lookY += (lookTY - lookY) * 0.15f

        // blinking
        if (t > nextBlink) {
            blinkStart = t
            nextBlink = t + 2000 + (Math.random() * 3800).toLong()
            if (Math.random() < 0.15) nextBlink = t + 320 // sometimes a double blink
        }
        val bp = (t - blinkStart) / 170f
        var open = if (bp in 0f..1f) abs(1f - 2f * bp) else 1f
        if (emotion == "sleepy") open *= 0.35f
        if (emotion == "calm") open *= 0.8f
        drawEye(c, 72f, 92f, open)
        drawEye(c, 128f, 92f, open)

        // eyebrows (raised a little when listening)
        val b = brows[emotion] ?: brows.getValue("happy")
        val lift = if (listening) -4f else 0f
        line.color = brow
        line.strokeWidth = 5f
        path.reset(); path.moveTo(b[0], b[1] + lift); path.quadTo(b[2], b[3] + lift, b[4], b[5] + lift)
        path.moveTo(b[6], b[7] + lift); path.quadTo(b[8], b[9] + lift, b[10], b[11] + lift)
        c.drawPath(path, line)

        drawMouth(c, t)

        if (emotion == "love") {
            text.color = 0xFFFF5A5F.toInt()
            text.textSize = 22f
            val f = (t / 25 % 40).toFloat()
            c.drawText("♥", 28f, 52f - f * 0.4f, text)
            c.drawText("♥", 170f, 44f - f * 0.3f, text)
        }
        if (snoring) {
            text.color = Color.WHITE
            for (i in 0..2) {
                val ph = ((t / 900.0 + i / 3.0) % 1.0).toFloat()
                text.textSize = 14f + ph * 16f
                text.alpha = (255 * (1f - ph)).toInt()
                c.drawText("Z", 150f + ph * 30f + i * 4f, 60f - ph * 50f, text)
            }
            text.alpha = 255
        }
        if (thinking) {
            fill.color = Color.WHITE
            for (i in 0..2) {
                val y = 26f + sin(t / 150.0 + i).toFloat() * 3f
                c.drawCircle(150f + i * 12f, y, 4.5f, fill)
            }
        }
        c.restore()
        postInvalidateOnAnimation()
    }

    private fun drawEye(c: Canvas, cx: Float, cy: Float, open: Float) {
        val left = cx < 100f
        when (emotion) {
            "asleep" -> {
                line.color = dark
                line.strokeWidth = 4.5f
                path.reset(); path.moveTo(cx - 12f, cy); path.quadTo(cx, cy + 9f, cx + 12f, cy)
                c.drawPath(path, line)
                return
            }
            "ouch" -> {
                line.color = dark
                line.strokeWidth = 5f
                path.reset()
                if (left) { path.moveTo(cx - 11f, cy - 9f); path.lineTo(cx + 9f, cy); path.lineTo(cx - 11f, cy + 9f) }
                else { path.moveTo(cx + 11f, cy - 9f); path.lineTo(cx - 9f, cy); path.lineTo(cx + 11f, cy + 9f) }
                c.drawPath(path, line)
                return
            }
            "dizzy" -> {
                fill.color = Color.WHITE
                r.set(cx - 15f, cy - 17f, cx + 15f, cy + 17f)
                c.drawOval(r, fill)
                line.color = dark
                line.strokeWidth = 2.6f
                path.reset()
                val rot = now() / 120.0 * (if (left) 1 else -1)
                for (k in 0..34) {
                    val a = k * 0.5 + rot
                    val rad = k * 0.38f
                    val x = cx + (Math.cos(a) * rad).toFloat()
                    val y = cy + (Math.sin(a) * rad).toFloat()
                    if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                c.drawPath(path, line)
                return
            }
        }
        if (emotion == "laugh") {
            line.color = dark
            line.strokeWidth = 5f
            path.reset(); path.moveTo(cx - 12f, cy + 4f); path.quadTo(cx, cy - 12f, cx + 12f, cy + 4f)
            c.drawPath(path, line)
            return
        }
        val big = if (emotion == "surprised") 1.15f else 1f
        val ry = 18f * open * big
        if (ry < 2.5f) {
            line.color = dark
            line.strokeWidth = 4f
            c.drawLine(cx - 13f, cy, cx + 13f, cy, line)
            return
        }
        fill.color = Color.WHITE
        r.set(cx - 15f * big, cy - ry, cx + 15f * big, cy + ry)
        c.drawOval(r, fill)
        c.save()
        path.reset(); path.addOval(r, Path.Direction.CW)
        c.clipPath(path)
        val px = cx + lookX * 5f
        val py = cy + 2f + lookY * 5f + (if (emotion == "sad") 3f else 0f)
        fill.color = dark
        c.drawCircle(px, py, 8f * big, fill)
        fill.color = Color.WHITE
        c.drawCircle(px + 3f, py - 4f, 2.6f, fill)
        c.restore()
    }

    private fun drawMouth(c: Canvas, t: Long) {
        var target = 0f
        if (talking) {
            target = if (level >= 0f) {
                (level * 1.3f).coerceIn(0f, 1f)
            } else {
                // each word restarts the jaw cycle, so the mouth follows the words being spoken
                val since = (t - lastPulse).toFloat()
                val syl = abs(sin(since / 80.0)).toFloat()
                if (since > 900) 0.15f + 0.45f * syl else 0.25f + 0.7f * syl
            }
        }
        mouth += (target - mouth) * 0.5f

        if (mouth > 0.06f) {
            val w = 16f + 8f * mouth
            val h = 4f + 22f * mouth
            r.set(100f - w, 129f - h * 0.35f, 100f + w, 129f + h)
            fill.color = lip
            c.drawOval(r, fill)
            c.save()
            path.reset(); path.addOval(r, Path.Direction.CW)
            c.clipPath(path)
            fill.color = 0xFFFF7A7F.toInt()
            c.drawCircle(100f, r.bottom, w * 0.6f, fill)
            fill.color = Color.WHITE
            c.drawRect(100f - w, r.top - 1f, 100f + w, r.top + max(2f, h * 0.15f), fill)
            c.restore()
            return
        }

        line.color = lip
        line.strokeWidth = 5f
        path.reset()
        when (emotion) {
            "laugh" -> {
                path.moveTo(70f, 122f); path.quadTo(100f, 170f, 130f, 122f); path.close()
                fill.color = lip
                c.drawPath(path, fill)
                return
            }
            "surprised" -> {
                fill.color = lip
                r.set(91f, 124f, 109f, 146f)
                c.drawOval(r, fill)
                return
            }
            "sad" -> { path.moveTo(76f, 144f); path.quadTo(100f, 126f, 124f, 144f) }
            "angry" -> {
                line.strokeWidth = 6f
                path.moveTo(78f, 142f); path.quadTo(100f, 128f, 122f, 142f)
            }
            "dizzy" -> {
                path.moveTo(78f, 134f)
                path.cubicTo(88f, 124f, 92f, 144f, 100f, 134f)
                path.cubicTo(108f, 124f, 112f, 144f, 122f, 134f)
            }
            "ouch" -> {
                fill.color = lip
                r.set(93f, 127f, 107f, 143f)
                c.drawOval(r, fill)
                return
            }
            "asleep" -> {
                fill.color = lip
                val br = 3.5f + 1.5f * sin(t / 1100.0).toFloat()
                c.drawCircle(100f, 136f, br, fill)
                return
            }
            "thinking" -> { path.moveTo(82f, 138f); path.quadTo(100f, 134f, 118f, 140f) }
            "calm" -> { path.moveTo(80f, 132f); path.quadTo(100f, 142f, 120f, 132f) }
            "sleepy" -> { path.moveTo(86f, 136f); path.quadTo(100f, 140f, 114f, 136f) }
            else -> { path.moveTo(74f, 128f); path.quadTo(100f, 152f, 126f, 128f) }
        }
        c.drawPath(path, line)
    }
}
