package com.sudhir.dost

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    companion object {
        var instance: MainActivity? = null
    }

    private val teal = Color.parseColor("#127B7A")
    private val coral = Color.parseColor("#FF5A5F")
    private val ink = Color.parseColor("#10302F")
    private val soft = Color.parseColor("#5A7473")
    private val chip = Color.parseColor("#EEF6F5")

    private lateinit var store: Store
    private lateinit var face: FaceView
    private lateinit var said: TextView
    private lateinit var talkBtn: Button
    private lateinit var taskBox: LinearLayout
    private lateinit var remBox: LinearLayout
    private lateinit var langSpin: Spinner
    private var conv: Conversation? = null
    private var d = 1f

    private fun dp(v: Int) = (v * d).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        store = Store(this)
        d = resources.displayMetrics.density
        window.statusBarColor = teal

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(40))
        }
        setContentView(ScrollView(this).apply { setBackgroundColor(teal); addView(col) })

        col.addView(TextView(this).apply {
            text = store.botName
            textSize = 32f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        })
        face = FaceView(this)
        col.addView(face, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(240)))
        face.setOnClickListener { face.setEmotion("laugh", 2500) }

        said = TextView(this).apply {
            textSize = 17f
            setTextColor(ink)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = round(Color.WHITE, 18)
            text = "Namaste! Tap “Talk” and just speak. I will listen and reply by myself."
        }
        col.addView(said, lpm(8))

        talkBtn = btn("Talk to me (hands-free)", coral) { toggleTalk() }
        col.addView(talkBtn, lpm(12))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(btn("Float on screen", Color.WHITE) { startFloat() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) })
        row.addView(btn("Hide floating", Color.WHITE) { stopService(Intent(this, FloatService::class.java)) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(6) })
        col.addView(row, lpm(10))

        // typed chat
        val typeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val msg = input("Or type here…")
        typeRow.addView(msg, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        typeRow.addView(btn("Send", Color.WHITE) {
            val t = msg.text.toString().trim()
            if (t.isNotEmpty()) { msg.setText(""); conv?.ask(t) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(8) })
        col.addView(typeRow, lpm(10))

        // tasks
        val tasks = card(col, "Tasks")
        val taskRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val taskIn = input("New task")
        taskRow.addView(taskIn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        taskRow.addView(btn("Add", coral) {
            val t = taskIn.text.toString().trim()
            if (t.isNotEmpty()) { store.addTask(t); taskIn.setText(""); refreshLists() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(8) })
        tasks.addView(taskRow)
        taskBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tasks.addView(taskBox)

        // reminders
        val rems = card(col, "Reminders")
        val remIn = input("What should I remind you?")
        rems.addView(remIn)
        rems.addView(btn("Pick date & time", coral) { pickTime(remIn) }, lpm(8))
        if (!Reminders.canBeExact(this) && Build.VERSION.SDK_INT >= 31) {
            rems.addView(btn("Allow on-time reminders", Color.WHITE) {
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
                } catch (e: Exception) { }
            }, lpm(8))
        }
        remBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        rems.addView(remBox)
        rems.addView(note("Tip: just say “remind me at 5 pm to call the buyer”."))

        // settings
        val set = card(col, "Settings")
        set.addView(label("Language"))
        langSpin = Spinner(this)
        langSpin.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, LANGS.map { it.name })
        langSpin.setSelection(LANGS.indexOf(store.language).coerceAtLeast(0))
        set.addView(langSpin)
        set.addView(label("Your name"))
        val uName = input("e.g. Sudhir").apply { setText(store.userName) }
        set.addView(uName)
        set.addView(label("Friend's name"))
        val bName = input("Dost").apply { setText(store.botName) }
        set.addView(bName)
        set.addView(label("Gemini key (free, for answers)"))
        val gKey = input("Paste key from aistudio.google.com").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(store.geminiKey)
        }
        set.addView(gKey)
        set.addView(note("Get it free: aistudio.google.com → Get API key → Create API key → copy."))

        val prem = Switch(this).apply {
            text = "Premium voice (ElevenLabs, uses your credits)"
            textSize = 16f
            setTextColor(ink)
            isChecked = store.premium
        }
        set.addView(prem, lpm(14))
        set.addView(label("ElevenLabs API key"))
        val eKey = input("elevenlabs.io → Developers → API keys").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(store.elevenKey)
        }
        set.addView(eKey)
        set.addView(label("ElevenLabs voice ID"))
        val eVoice = input(NEERAJ_VOICE).apply { setText(store.elevenVoice) }
        set.addView(eVoice)
        val credits = note("Free plan: about 10,000 credits a month (roughly 20 minutes of Dost speaking).")
        set.addView(credits)
        set.addView(btn("Check my ElevenLabs credits", Color.WHITE) { checkCredits(eKey.text.toString().trim(), credits) }, lpm(6))

        set.addView(btn("Save settings", coral) {
            val newKey = gKey.text.toString().trim()
            if (newKey != store.geminiKey) store.model = ""
            store.langName = LANGS[langSpin.selectedItemPosition].name
            store.userName = uName.text.toString().trim()
            store.botName = bName.text.toString().trim()
            store.geminiKey = newKey
            store.elevenKey = eKey.text.toString().trim()
            store.elevenVoice = eVoice.text.toString().trim()
            store.premium = prem.isChecked
            toast("Saved")
            conv?.shutdown()
            conv = makeConv()
        }, lpm(12))

        conv = makeConv()
        refreshLists()
        askPermissions()
        if (store.langName.isBlank()) chooseLanguage()
        else if (store.geminiKey.isBlank()) said.text = "Add your free Gemini key in Settings below so I can answer you."
    }

    private fun makeConv() = Conversation(this, face, { said.text = it }, { refreshLists() })

    private fun toggleTalk() {
        val c = conv ?: return
        if (FloatService.instance != null) stopService(Intent(this, FloatService::class.java))
        c.toggle()
        talkBtn.text = if (c.active) "Stop talking" else "Talk to me (hands-free)"
        if (c.active) said.text = "Listening… bolo!"
    }

    private fun chooseLanguage() {
        AlertDialog.Builder(this)
            .setTitle("Choose Dost's language / भाषा चुनें")
            .setItems(LANGS.map { it.name }.toTypedArray()) { _, i ->
                store.langName = LANGS[i].name
                langSpin.setSelection(i)
                conv?.shutdown()
                conv = makeConv()
                said.text = if (store.geminiKey.isBlank())
                    "Great! Now paste your free Gemini key in Settings below and press Save."
                else "Great! Tap Talk and speak."
            }
            .setCancelable(false)
            .show()
    }

    private fun startFloat() {
        if (!Settings.canDrawOverlays(this)) {
            toast("Turn ON “Allow display over other apps” for Dost, then come back and press Float again")
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            askPermissions()
            return
        }
        conv?.stop()
        talkBtn.text = "Talk to me (hands-free)"
        startForegroundService(Intent(this, FloatService::class.java))
        moveTaskToBack(true)
    }

    private fun pickTime(remIn: EditText) {
        val t = remIn.text.toString().trim()
        if (t.isEmpty()) { toast("First write what to remind"); return }
        val c = Calendar.getInstance()
        DatePickerDialog(this, { _, y, m, day ->
            TimePickerDialog(this, { _, h, min ->
                val cal = Calendar.getInstance().apply { set(y, m, day, h, min, 0) }
                if (cal.timeInMillis <= System.currentTimeMillis()) { toast("Pick a time in the future"); return@TimePickerDialog }
                Reminders.add(this, store, t, cal.timeInMillis)
                remIn.setText("")
                refreshLists()
                toast("Reminder set")
            }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), false).show()
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
    }

    fun refreshLists() {
        if (!::taskBox.isInitialized) return
        taskBox.removeAllViews()
        val tasks = store.tasks()
        if (tasks.isEmpty()) taskBox.addView(note("No tasks yet. Say “add task: pack Osaka samples”."))
        tasks.forEachIndexed { i, task ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val cb = CheckBox(this).apply {
                text = task.optString("t")
                textSize = 16f
                setTextColor(if (task.optBoolean("d")) soft else ink)
                isChecked = task.optBoolean("d")
                setOnCheckedChangeListener { _, on ->
                    val l = store.tasks()
                    if (i < l.size) { l[i].put("d", on); store.saveTasks(l) }
                    if (on) face.setEmotion("laugh", 2000)
                    refreshLists()
                }
            }
            row.addView(cb, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(small("✕") {
                val l = store.tasks()
                if (i < l.size) { l.removeAt(i); store.saveTasks(l) }
                refreshLists()
            })
            taskBox.addView(row)
        }

        remBox.removeAllViews()
        val fmt = SimpleDateFormat("d MMM, h:mm a", Locale.ENGLISH)
        val now = System.currentTimeMillis()
        val upcoming = store.reminders().filter { !it.optBoolean("f") && it.optLong("at") > now }
        if (upcoming.isEmpty()) remBox.addView(note("No upcoming reminders."))
        upcoming.forEach { r: JSONObject ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(TextView(this).apply {
                text = r.optString("t") + "\n" + fmt.format(Date(r.optLong("at")))
                textSize = 16f
                setTextColor(ink)
                setPadding(0, dp(8), 0, dp(8))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(small("✕") {
                Reminders.cancel(this, store, r.optInt("id"), r.optString("t"))
                refreshLists()
            })
            remBox.addView(row)
        }
    }

    fun remind(t: String) { conv?.remind(t); refreshLists() }

    private fun checkCredits(key: String, out: TextView) {
        if (key.isEmpty()) { out.text = "Paste your ElevenLabs key first."; return }
        out.text = "Checking…"
        Thread {
            val msg = try {
                val c = URL("https://api.elevenlabs.io/v1/user/subscription").openConnection() as HttpURLConnection
                c.setRequestProperty("xi-api-key", key)
                if (c.responseCode != 200) "Could not check (error ${c.responseCode}). The key may need 'User: read' permission."
                else {
                    val o = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                    val used = o.optLong("character_count")
                    val limit = o.optLong("character_limit")
                    "Used $used of $limit credits this month (${(limit - used).coerceAtLeast(0)} left)."
                }
            } catch (e: Exception) { "Could not check: no internet?" }
            runOnUiThread { out.text = msg }
        }.start()
    }

    private fun askPermissions() {
        val need = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE
        )
        if (Build.VERSION.SDK_INT >= 33) need.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = need.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
    }

    override fun onPause() {
        super.onPause()
        conv?.stop()
        if (::talkBtn.isInitialized) talkBtn.text = "Talk to me (hands-free)"
    }

    override fun onDestroy() {
        instance = null
        conv?.shutdown()
        super.onDestroy()
    }

    // ---------- small UI helpers ----------
    private fun round(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }

    private fun lpm(top: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(top) }

    private fun btn(text: String, color: Int, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 16f
        setTextColor(if (color == Color.WHITE) ink else Color.WHITE)
        background = round(color, 24)
        minHeight = dp(48)
        setPadding(dp(16), dp(8), dp(16), dp(8))
        setOnClickListener { onClick() }
    }

    private fun small(text: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        textSize = 18f
        setTextColor(soft)
        gravity = Gravity.CENTER
        minWidth = dp(44)
        minHeight = dp(44)
        setOnClickListener { onClick() }
    }

    private fun input(hint: String) = EditText(this).apply {
        this.hint = hint
        textSize = 16f
        setTextColor(ink)
        setSingleLine(true)
        background = round(chip, 12)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        minHeight = dp(48)
    }

    private fun label(t: String) = TextView(this).apply {
        text = t
        textSize = 15f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(ink)
        setPadding(0, dp(12), 0, dp(4))
    }

    private fun note(t: String) = TextView(this).apply {
        text = t
        textSize = 14f
        setTextColor(soft)
        setPadding(0, dp(6), 0, dp(4))
    }

    private fun card(parent: LinearLayout, title: String): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = round(Color.WHITE, 20)
            setPadding(dp(16), dp(14), dp(16), dp(16))
        }
        c.addView(TextView(this).apply {
            text = title
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ink)
            setPadding(0, 0, 0, dp(8))
        })
        parent.addView(c, lpm(16))
        return c
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    @Suppress("unused")
    private fun hide(v: View) { v.visibility = View.GONE }
}
