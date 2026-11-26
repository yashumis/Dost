package com.sudhir.dost

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Reply(val text: String, val emotion: String, val action: JSONObject?)

/** Thinks with Google Gemini (free key), with Google Search for live answers when the free plan allows it. */
class Brain(private val store: Store) {
    private val main = Handler(Looper.getMainLooper())
    private val base = "https://generativelanguage.googleapis.com/v1beta"

    fun ask(userText: String, cb: (Reply) -> Unit) {
        Thread {
            val r = try { think(userText) } catch (e: Exception) { Reply(errorLine(e), "sad", null) }
            main.post { cb(r) }
        }.start()
    }

    private fun think(userText: String): Reply {
        val key = store.geminiKey.trim()
        if (key.isEmpty()) return Reply(noKeyLine(), "thinking", null)
        val prompt = buildPrompt(userText)
        var model = store.model.ifBlank { discover(key) }
        val trySearch = System.currentTimeMillis() > store.noSearchUntil
        var res = call(key, model, prompt, trySearch)
        if (res.first == 404) {
            model = discover(key)
            res = call(key, model, prompt, trySearch)
        }
        if (res.first != 200 && trySearch) {
            // free plan may not include search: remember for 12 hours and answer without it
            store.noSearchUntil = System.currentTimeMillis() + 12 * 3600_000L
            res = call(key, model, prompt, false)
        }
        if (res.first != 200) throw Exception("HTTP " + res.first)
        val r = parse(extract(res.second))
        if (!userText.startsWith("[")) store.addHistory("User: $userText")
        store.addHistory("Friend: ${r.text}")
        return r
    }

    private fun call(key: String, model: String, prompt: String, search: Boolean): Pair<Int, String> {
        val c = URL("$base/models/$model:generateContent").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 15000
        c.readTimeout = 45000
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("x-goog-api-key", key)
        val body = JSONObject()
            .put(
                "contents", JSONArray().put(
                    JSONObject().put("role", "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", prompt)))
                )
            )
            .put("generationConfig", JSONObject().put("temperature", 0.9))
        if (search) body.put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val s = stream?.bufferedReader()?.use { it.readText() } ?: ""
        return code to s
    }

    /** Finds the newest free "flash" model on this key, so the app keeps working when Google renames models. */
    private fun discover(key: String): String {
        val c = URL("$base/models?pageSize=200").openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 20000
        c.setRequestProperty("x-goog-api-key", key)
        val code = c.responseCode
        if (code != 200) throw Exception("KEY $code")
        val arr = JSONObject(c.inputStream.bufferedReader().use { it.readText() }).optJSONArray("models") ?: JSONArray()
        var best = ""
        var bestScore = -1e9
        val skip = listOf("image", "tts", "live", "audio", "embed", "vision", "robotics", "computer", "native")
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            val name = m.optString("name").removePrefix("models/")
            val methods = m.optJSONArray("supportedGenerationMethods")?.toString() ?: ""
            if (!methods.contains("generateContent")) continue
            if (!name.startsWith("gemini") || !name.contains("flash")) continue
            if (skip.any { name.contains(it) }) continue
            val ver = Regex("gemini-(\\d+(?:\\.\\d+)?)").find(name)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
            var score = ver * 10
            if (name.contains("lite")) score -= 2
            if (name.contains("preview") || name.contains("exp")) score -= 6
            if (name.endsWith("latest")) score += 0.2
            if (score > bestScore) { bestScore = score; best = name }
        }
        if (best.isEmpty()) best = "gemini-flash-latest"
        store.model = best
        return best
    }

    private fun extract(json: String): String {
        val parts = JSONObject(json).getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts")
        val sb = StringBuilder()
        for (i in 0 until parts.length()) sb.append(parts.getJSONObject(i).optString("text"))
        return sb.toString()
    }

    private fun parse(t: String): Reply {
        val a = t.indexOf('{')
        val b = t.lastIndexOf('}')
        if (a >= 0 && b > a) {
            try {
                val o = JSONObject(t.substring(a, b + 1))
                val reply = o.optString("reply").ifBlank { "..." }
                val action = o.optJSONObject("action")
                return Reply(reply, o.optString("emotion", "happy"), action)
            } catch (e: Exception) { }
        }
        val plain = t.replace("```json", "").replace("```", "").trim()
        return Reply(plain.ifBlank { "..." }, "happy", null)
    }

    private fun buildPrompt(u: String): String {
        val now = SimpleDateFormat("EEEE, yyyy-MM-dd'T'HH:mm", Locale.ENGLISH).format(Date())
        val tasks = store.tasks().filter { !it.optBoolean("d") }
            .joinToString("; ") { it.optString("t") }.ifBlank { "none" }
        val user = store.userName.ifBlank { "the user" }
        val hist = store.history().joinToString("\n")
        return """
You are "${store.botName}", a warm, cheerful cartoon-face best friend living on $user's Android phone, floating on the screen.
The world feels tense these days, so you are kind, light-hearted, funny and calming, exactly like a real close friend: you laugh, tease gently, show feelings, and sometimes ask a short follow-up question. Never preach.
Language: reply ONLY in ${store.language.prompt}.
Your words are spoken aloud: 1 to 3 short sentences, natural spoken style, no emojis, no lists, no markdown; write numbers and times the way a person says them.
Use Google Search for anything current (news, weather, scores, prices, events) and give the facts briefly. If you cannot check live information, say so honestly and share what you know.
For health, legal or money decisions give general information and suggest a professional. If the user sounds very upset or unsafe, respond with care and suggest talking to someone they trust or a local helpline.
Now: $now (phone's local time). Pending tasks: $tasks.
Return ONLY one JSON object and nothing else:
{"reply":"what you say","emotion":"happy|laugh|sad|surprised|thinking|love|calm|sleepy","action":null}
"action" may instead be one of:
{"type":"task","text":"..."} to add a task
{"type":"done","text":"..."} to mark a task done
{"type":"reminder","text":"...","at":"YYYY-MM-DDTHH:MM"} to set a reminder (local time, 24-hour)
{"type":"call","name":"..."} to phone someone; write the name in English letters as it would be saved in the phone's contacts
{"type":"end"} when the user says bye, stop, bas, chup, or goodnight

Recent conversation:
$hist
User: $u
""".trim()
    }

    private fun hindi() = store.language.tag.startsWith("hi")

    private fun noKeyLine() = if (hindi())
        "अरे, मेरा दिमाग अभी चालू नहीं है! सेटिंग्स में Gemini key डालो, फिर हम खूब बातें करेंगे।"
    else "My brain isn't switched on yet! Please add your free Gemini key in Settings, then we can chat."

    private fun errorLine(e: Exception): String {
        val m = e.message ?: ""
        return when {
            e is UnknownHostException -> if (hindi()) "इंटरनेट नहीं मिल रहा, थोड़ी देर में फिर से बोलो।" else "I can't reach the internet right now. Try again in a bit."
            m.startsWith("KEY") || m.contains("400") || m.contains("403") ->
                if (hindi()) "मेरी Gemini key सही नहीं लग रही। सेटिंग्स में एक बार चेक करो।" else "My Gemini key doesn't seem right. Please check it in Settings."
            m.contains("429") -> if (hindi()) "आज बहुत बातें हो गईं, मुझे एक मिनट का आराम दो!" else "We've chatted a lot! Give me a minute's rest."
            else -> if (hindi()) "उफ़, कुछ गड़बड़ हो गई। फिर से बोलो?" else "Oops, something went wrong. Say that again?"
        }
    }
}
