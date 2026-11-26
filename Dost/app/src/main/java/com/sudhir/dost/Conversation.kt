package com.sudhir.dost

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.speech.SpeechRecognizer
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * The hands-free loop: listen -> think -> speak (with face) -> listen again,
 * until the user says bye or stays quiet twice.
 */
class Conversation(
    private val ctx: Context,
    private val face: FaceView,
    private val show: (String) -> Unit,
    private val changed: () -> Unit = {}
) {
    val store = Store(ctx)
    private val brain = Brain(store)
    private val voice = Voice(ctx, store).also { it.face = face }
    private val ears = Ears(ctx)
    private val main = Handler(Looper.getMainLooper())
    var active = false
        private set
    private var misses = 0
    private var busy = false

    private fun hindi() = store.language.tag.startsWith("hi")

    fun toggle() { if (active || busy) stop() else start() }

    fun start() {
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            show("Please allow the microphone for Dost (open the Dost app).")
            return
        }
        if (!ears.available()) {
            show("Speech recognition is missing. Please install or update the Google app.")
            return
        }
        voice.stop()
        active = true
        misses = 0
        face.setEmotion("happy")
        listen()
    }

    fun stop() {
        active = false
        busy = false
        ears.cancel()
        voice.stop()
        face.listening = false
        face.thinking = false
    }

    private fun listen() {
        if (!active) return
        face.listening = true
        ears.listen(store.language.tag, { partial -> show(partial) }) cb@{ text, err ->
            face.listening = false
            if (!active) return@cb
            if (text.isNullOrBlank()) {
                misses++
                if (misses >= 2 || err == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    active = false
                    face.setEmotion("sleepy", 5000)
                    show(if (hindi()) "मैं यहीं हूँ, बात करनी हो तो मुझे छू लेना।" else "I'm right here. Tap me when you want to talk.")
                    return@cb
                }
                main.postDelayed({ listen() }, 350)
                return@cb
            }
            misses = 0
            ask(text)
        }
    }

    /** Send words to the brain (also used for typed messages). */
    fun ask(text: String) {
        busy = true
        if (!text.startsWith("[")) show(text)
        face.thinking = true
        face.setEmotion("thinking")
        brain.ask(text) { r ->
            face.thinking = false
            face.setEmotion(r.emotion, 7000)
            show(r.text)
            val after = act(r.action)
            voice.speak(r.text) {
                busy = false
                if (after != null) after()
                else if (active) main.postDelayed({ listen() }, 250)
            }
        }
    }

    fun greet() {
        ask("[The user just opened you. Greet them warmly in one short line, by name if you know it.]")
    }

    fun remind(t: String) {
        ears.cancel()
        face.setEmotion("surprised", 5000)
        val line = (if (hindi()) "याद दिलाना था: " else "Reminder: ") + t
        show(line)
        voice.speak(line) { if (active) listen() }
    }

    /** Does the task / reminder / call the brain asked for. Returns something to run after speaking. */
    private fun act(a: JSONObject?): (() -> Unit)? {
        if (a == null) return null
        when (a.optString("type")) {
            "task" -> {
                val t = a.optString("text").trim()
                if (t.isNotEmpty()) { store.addTask(t); changed() }
            }
            "done" -> { store.markDone(a.optString("text")); changed() }
            "reminder" -> {
                val at = try {
                    SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.ENGLISH).parse(a.optString("at"))?.time ?: 0L
                } catch (e: Exception) { 0L }
                val t = a.optString("text").trim()
                if (t.isNotEmpty() && at > System.currentTimeMillis()) { Reminders.add(ctx, store, t, at); changed() }
            }
            "call" -> {
                val name = a.optString("name")
                return {
                    val hit = Phone.find(ctx, name)
                    if (hit != null && hit.second.isNotBlank()) {
                        stop()
                        Phone.dial(ctx, hit.second)
                    } else {
                        val msg = if (hindi()) "$name का नंबर तुम्हारे contacts में नहीं मिला।" else "I couldn't find $name in your contacts."
                        show(msg)
                        voice.speak(msg) { if (active) listen() }
                    }
                }
            }
            "end" -> return { stop(); face.setEmotion("love", 3000) }
        }
        return null
    }

    fun shutdown() {
        stop()
        voice.shutdown()
    }
}
