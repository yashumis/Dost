package com.sudhir.dost

import android.content.Context
import android.media.MediaPlayer
import android.media.audiofx.Visualizer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.sqrt

/** Speaks: free Google phone voice, or the ElevenLabs premium voice. Moves the face's mouth in time. */
class Voice(private val ctx: Context, private val store: Store) {
    var face: FaceView? = null
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ready = false
    private var failed = false
    private var currentId = ""
    private var pendingDone: (() -> Unit)? = null
    private var queuedText: String? = null
    private var player: MediaPlayer? = null
    private var viz: Visualizer? = null

    val speaking: Boolean get() = pendingDone != null

    init {
        tts = TextToSpeech(ctx.applicationContext) { status ->
            main.post {
                ready = status == TextToSpeech.SUCCESS
                failed = !ready
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) { main.post { if (id == currentId) face?.talking = true } }
                    override fun onDone(id: String?) { main.post { if (id == currentId) finish() } }
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) { main.post { if (id == currentId) finish() } }
                    override fun onError(id: String?, code: Int) { main.post { if (id == currentId) finish() } }
                    override fun onRangeStart(id: String?, start: Int, end: Int, frame: Int) {
                        main.post { if (id == currentId) face?.pulse() }
                    }
                })
                val q = queuedText
                queuedText = null
                if (q != null) { if (ready) google(q) else finish() }
            }
        }
    }

    private fun finish() {
        face?.talking = false
        val d = pendingDone
        pendingDone = null
        currentId = ""
        d?.invoke()
    }

    /** free = true always uses the phone's free voice (used for quick reactions, so no credits are spent). */
    fun speak(text: String, free: Boolean = false, done: () -> Unit = {}) {
        stop()
        pendingDone = done
        val clean = text
            .replace(Regex("\\[[^\\]]*\\]"), " ")
            .replace(Regex("[*#_`~]"), "")
            .trim()
        if (clean.isEmpty()) { finish(); return }
        if (!free && store.premium && store.elevenKey.isNotBlank()) eleven(clean) else google(clean)
    }

    private fun google(text: String) {
        val t = tts
        if (t == null || failed) { finish(); return }
        if (!ready) { queuedText = text; return }
        val loc = Locale.forLanguageTag(store.language.tag)
        try {
            t.language = loc
            val best = t.voices
                ?.filter { v ->
                    v.locale.language == loc.language &&
                        !v.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
                }
                ?.maxByOrNull { v -> v.quality + (if (v.locale.country == loc.country) 100 else 0) }
            if (best != null) t.voice = best
        } catch (e: Exception) { }
        t.setPitch(1.08f)
        t.setSpeechRate(1.0f)
        currentId = "u" + System.nanoTime()
        t.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), currentId)
    }

    private fun eleven(text: String) {
        val key = store.elevenKey.trim()
        val voiceId = store.elevenVoice.trim()
        val myId = "e" + System.nanoTime()
        currentId = myId
        face?.thinking = true
        Thread {
            try {
                val c = URL("https://api.elevenlabs.io/v1/text-to-speech/$voiceId?output_format=mp3_44100_64")
                    .openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.doOutput = true
                c.connectTimeout = 15000
                c.readTimeout = 40000
                c.setRequestProperty("xi-api-key", key)
                c.setRequestProperty("Content-Type", "application/json")
                c.setRequestProperty("Accept", "audio/mpeg")
                val body = JSONObject().put("text", text).put("model_id", "eleven_flash_v2_5")
                c.outputStream.use { it.write(body.toString().toByteArray()) }
                if (c.responseCode != 200) throw Exception("ElevenLabs " + c.responseCode)
                val f = File(ctx.cacheDir, "say.mp3")
                c.inputStream.use { inp -> f.outputStream().use { inp.copyTo(it) } }
                main.post { face?.thinking = false; if (currentId == myId) play(f, myId) }
            } catch (e: Exception) {
                // no credits / no internet: fall back to the free voice
                main.post { face?.thinking = false; if (currentId == myId) google(text) }
            }
        }.start()
    }

    private fun play(f: File, myId: String) {
        try {
            val mp = MediaPlayer()
            player = mp
            mp.setDataSource(f.absolutePath)
            mp.prepare()
            mp.setOnCompletionListener { releasePlayer(); if (currentId == myId) finish() }
            try {
                val v = Visualizer(mp.audioSessionId)
                viz = v
                v.captureSize = Visualizer.getCaptureSizeRange()[0]
                v.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(vz: Visualizer?, wave: ByteArray?, rate: Int) {
                        if (wave == null || wave.isEmpty()) return
                        var sum = 0.0
                        for (b in wave) {
                            val x = ((b.toInt() and 0xFF) - 128) / 128.0
                            sum += x * x
                        }
                        val rms = sqrt(sum / wave.size).toFloat()
                        main.post { face?.audioLevel((rms * 4f).coerceIn(0f, 1f)) }
                    }
                    override fun onFftDataCapture(vz: Visualizer?, fft: ByteArray?, rate: Int) {}
                }, Visualizer.getMaxCaptureRate() / 2, true, false)
                v.enabled = true
            } catch (e: Exception) { }
            face?.talking = true
            mp.start()
        } catch (e: Exception) {
            releasePlayer()
            finish()
        }
    }

    private fun releasePlayer() {
        try { viz?.release() } catch (e: Exception) { }
        viz = null
        try { player?.release() } catch (e: Exception) { }
        player = null
    }

    fun stop() {
        currentId = ""
        queuedText = null
        pendingDone = null
        try { tts?.stop() } catch (e: Exception) { }
        releasePlayer()
        face?.talking = false
    }

    fun shutdown() {
        stop()
        try { tts?.shutdown() } catch (e: Exception) { }
    }
}
