package com.sudhir.dost

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/** Cartoon sounds made by maths on the phone itself: no internet, no credits. */
object Sfx {
    private const val SR = 22050

    private fun play(pcm: ShortArray) {
        Thread {
            try {
                val t = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(SR)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(pcm.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                t.write(pcm, 0, pcm.size)
                t.play()
                Thread.sleep(pcm.size * 1000L / SR + 150)
                t.release()
            } catch (e: Exception) { }
        }.start()
    }

    private fun env(t: Double, dur: Double, att: Double, rel: Double) =
        min(1.0, t / att) * min(1.0, (dur - t) / rel).coerceAtLeast(0.0)

    /** "Oooooo" while sliding: a round vowel sound that rises in pitch. */
    fun ooo() {
        val dur = 1.1
        val n = (SR * dur).toInt()
        val out = ShortArray(n)
        var ph = 0.0
        for (i in 0 until n) {
            val t = i / SR.toDouble()
            val f = 230 + 140 * t / dur + 7 * sin(2 * PI * 5.5 * t)
            ph += 2 * PI * f / SR
            val s = 0.8 * sin(ph) + 0.35 * sin(2 * ph) + 0.08 * sin(3 * ph)
            out[i] = (s * env(t, dur, 0.08, 0.3) * 0.45 * 32767).toInt().toShort()
        }
        play(out)
    }

    /** One snore: a buzzy breath in, then a soft breath out. */
    fun snore() {
        val dur = 2.0
        val n = (SR * dur).toInt()
        val out = ShortArray(n)
        var ph = 0.0
        var y = 0.0
        for (i in 0 until n) {
            val t = i / SR.toDouble()
            val inhale = t < 1.1
            val f = if (inhale) 62 + 10 * sin(2 * PI * 3 * t) else 0.0
            ph += 2 * PI * f / SR
            val saw = if (inhale) ((ph / (2 * PI)) % 1.0) * 2 - 1 else 0.0
            val noise = Math.random() * 2 - 1
            val x = if (inhale) saw * 0.7 + noise * 0.3 else noise * 0.5
            y += (if (inhale) 0.12 else 0.05) * (x - y)
            val e = if (inhale) sin(PI * t / 1.1) else sin(PI * (t - 1.1) / 0.9) * 0.45
            out[i] = (y * e * 0.8 * 32767).coerceIn(-32767.0, 32767.0).toInt().toShort()
        }
        play(out)
    }

    /** Wobbly "woo-woo-woo" when spun around. */
    fun dizzy() {
        val dur = 1.4
        val n = (SR * dur).toInt()
        val out = ShortArray(n)
        var ph = 0.0
        for (i in 0 until n) {
            val t = i / SR.toDouble()
            val f = 820 - 380 * t / dur + 190 * sin(2 * PI * 7 * t)
            ph += 2 * PI * f / SR
            val s = sin(ph) + 0.25 * sin(2 * ph)
            out[i] = (s * env(t, dur, 0.03, 0.3) * 0.35 * 32767).toInt().toShort()
        }
        play(out)
    }

    /** Little "hee-hee-hee" giggle. */
    fun giggle() {
        val note = 0.11
        val gap = 0.05
        val count = 6
        val n = (SR * (note + gap) * count).toInt()
        val out = ShortArray(n)
        for (k in 0 until count) {
            val start = ((note + gap) * k * SR).toInt()
            val f0 = 560.0 - k * 28
            var ph = 0.0
            for (j in 0 until (note * SR).toInt()) {
                val i = start + j
                if (i >= n) break
                val t = j / SR.toDouble()
                ph += 2 * PI * (f0 + 60 * t / note) / SR
                val s = sin(ph) + 0.6 * sin(2 * ph) + 0.3 * sin(3 * ph)
                out[i] = (s * env(t, note, 0.01, 0.05) * 0.22 * 32767).toInt().toShort()
            }
        }
        play(out)
    }

    /** Short angry "hmph!". */
    fun grunt() {
        val dur = 0.35
        val n = (SR * dur).toInt()
        val out = ShortArray(n)
        var ph = 0.0
        var y = 0.0
        for (i in 0 until n) {
            val t = i / SR.toDouble()
            ph += 2 * PI * (150 - 60 * t / dur) / SR
            val x = (((ph / (2 * PI)) % 1.0) * 2 - 1) * 0.7 + (Math.random() * 2 - 1) * 0.3
            y += 0.2 * (x - y)
            out[i] = (y * env(t, dur, 0.01, 0.12) * 0.9 * 32767).coerceIn(-32767.0, 32767.0).toInt().toShort()
        }
        play(out)
    }
}
