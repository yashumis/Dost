package com.sudhir.dost

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Lang(val name: String, val tag: String, val prompt: String)

val LANGS = listOf(
    Lang("हिन्दी Hindi", "hi-IN", "Hindi (Devanagari script, simple everyday spoken Hindi)"),
    Lang("Hinglish", "hi-IN", "Hinglish: casual Hindi-English mix as spoken in Delhi; write the Hindi words in Devanagari script and the English words in English letters"),
    Lang("English", "en-IN", "simple friendly Indian English"),
    Lang("বাংলা Bengali", "bn-IN", "Bengali (Bengali script)"),
    Lang("मराठी Marathi", "mr-IN", "Marathi (Devanagari script)"),
    Lang("தமிழ் Tamil", "ta-IN", "Tamil (Tamil script)"),
    Lang("తెలుగు Telugu", "te-IN", "Telugu (Telugu script)"),
    Lang("ગુજરાતી Gujarati", "gu-IN", "Gujarati (Gujarati script)"),
    Lang("ਪੰਜਾਬੀ Punjabi", "pa-IN", "Punjabi (Gurmukhi script)"),
    Lang("ಕನ್ನಡ Kannada", "kn-IN", "Kannada (Kannada script)"),
    Lang("മലയാളം Malayalam", "ml-IN", "Malayalam (Malayalam script)"),
    Lang("اردو Urdu", "ur-IN", "Urdu (Urdu script)"),
    Lang("日本語 Japanese", "ja-JP", "Japanese")
)

const val NEERAJ_VOICE = "H9oourY5nVeVuzarh4D5"

class Store(ctx: Context) {
    private val p = ctx.applicationContext.getSharedPreferences("dost", Context.MODE_PRIVATE)

    private fun g(k: String, d: String): String = p.getString(k, d) ?: d
    private fun s(k: String, v: String) { p.edit().putString(k, v).apply() }

    var langName: String
        get() = g("lang", "")
        set(v) = s("lang", v)
    val language: Lang
        get() = LANGS.firstOrNull { it.name == langName } ?: LANGS[0]

    var userName: String
        get() = g("user", "")
        set(v) = s("user", v)
    var botName: String
        get() = g("bot", "Dost").ifBlank { "Dost" }
        set(v) = s("bot", v)
    var geminiKey: String
        get() = g("gkey", "")
        set(v) = s("gkey", v)
    var elevenKey: String
        get() = g("ekey", "")
        set(v) = s("ekey", v)
    var elevenVoice: String
        get() = g("evoice", NEERAJ_VOICE).ifBlank { NEERAJ_VOICE }
        set(v) = s("evoice", v)
    var premium: Boolean
        get() = p.getBoolean("prem", false)
        set(v) { p.edit().putBoolean("prem", v).apply() }
    var model: String
        get() = g("model", "")
        set(v) = s("model", v)
    var noSearchUntil: Long
        get() = p.getLong("nos", 0L)
        set(v) { p.edit().putLong("nos", v).apply() }

    private fun arr(k: String): JSONArray = try { JSONArray(g(k, "[]")) } catch (e: Exception) { JSONArray() }
    private fun list(k: String): MutableList<JSONObject> {
        val a = arr(k)
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.toMutableList()
    }
    private fun saveList(k: String, l: List<JSONObject>) {
        val a = JSONArray()
        l.forEach { a.put(it) }
        s(k, a.toString())
    }

    // ---- tasks
    fun tasks(): MutableList<JSONObject> = list("tasks")
    fun saveTasks(l: List<JSONObject>) = saveList("tasks", l)
    fun addTask(t: String) {
        val l = tasks()
        l.add(0, JSONObject().put("t", t).put("d", false))
        saveTasks(l)
    }
    fun markDone(text: String) {
        val l = tasks()
        val key = text.trim().lowercase()
        val hit = l.firstOrNull { !it.optBoolean("d") && it.optString("t").lowercase().contains(key) }
            ?: l.firstOrNull { !it.optBoolean("d") && key.contains(it.optString("t").lowercase()) }
        hit?.put("d", true)
        saveTasks(l)
    }

    // ---- reminders
    fun reminders(): MutableList<JSONObject> = list("rems")
    fun saveReminders(l: List<JSONObject>) = saveList("rems", l)
    fun addReminder(t: String, at: Long): Int {
        val id = ((System.currentTimeMillis() / 1000) % 1_000_000_000).toInt()
        val l = reminders()
        l.add(JSONObject().put("id", id).put("t", t).put("at", at).put("f", false))
        l.sortBy { it.optLong("at") }
        saveReminders(l)
        return id
    }
    fun markFired(id: Int) {
        val l = reminders()
        l.firstOrNull { it.optInt("id") == id }?.put("f", true)
        saveReminders(l)
    }
    fun removeReminder(id: Int) {
        saveReminders(reminders().filter { it.optInt("id") != id })
    }

    // ---- short conversation memory
    fun history(): List<String> {
        val a = arr("hist")
        return (0 until a.length()).map { a.optString(it) }
    }
    fun addHistory(line: String) {
        val h = history().toMutableList()
        h.add(line)
        val a = JSONArray()
        h.takeLast(14).forEach { a.put(it) }
        s("hist", a.toString())
    }
}
