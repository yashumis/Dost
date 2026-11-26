package com.sudhir.dost

import android.animation.ValueAnimator
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Dost floating over every app.
 * Tap = talk/stop · drag = move · long-press = open app · tilt phone = slides with "oooo"
 * Rub face = sleeps & snores · double-tap (punch) = angry · scratch ear = "no no"
 * Tickle top of head = laughs · drag in circles = dizzy · poke an eye = "ouch"
 * 3 min without talking = "say something"; 1 more min = shrinks to a corner and sleeps.
 */
class FloatService : Service(), SensorEventListener {

    companion object {
        var instance: FloatService? = null
    }

    private val main = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var root: LinearLayout? = null
    private lateinit var face: FaceView
    private lateinit var bubble: TextView
    private lateinit var lp: WindowManager.LayoutParams
    private var conv: Conversation? = null
    private var d = 1f
    private val hideBubble = Runnable { bubble.visibility = View.GONE }

    // states
    private var mini = false
    private var asleep = false
    private var dragging = false
    private var lastActive = now()
    private var idleStage = 0
    private var stageAt = 0L

    // tilt sliding
    private var sensors: SensorManager? = null
    private var posX = 0f
    private var vx = 0f
    private var sliding = false
    private var lastOoo = 0L

    private fun now() = System.currentTimeMillis()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notify.channels(this)
        val stopPi = PendingIntent.getService(
            this, 1, Intent(this, FloatService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE
        )
        val openPi = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, Notify.CH_FLOAT)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Dost is floating")
            .setContentText("Tap the face to talk. Long-press to open the app.")
            .setContentIntent(openPi)
            .addAction(Notification.Action.Builder(null as Icon?, "Hide Dost", stopPi).build())
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(7, n)
        } catch (e: Exception) {
            Toast.makeText(this, "Please allow the microphone for Dost first", Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }
        instance = this
        build()
    }

    private fun build() {
        val w = getSystemService(WINDOW_SERVICE) as WindowManager
        wm = w
        d = resources.displayMetrics.density
        face = FaceView(this)
        bubble = TextView(this).apply {
            setTextColor(Color.parseColor("#10302F"))
            textSize = 15f
            setPadding((12 * d).toInt(), (8 * d).toInt(), (12 * d).toInt(), (8 * d).toInt())
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = 16 * d }
            maxWidth = (240 * d).toInt()
            elevation = 6 * d
            visibility = View.GONE
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(face, LinearLayout.LayoutParams(dp(112), dp(112)))
            addView(bubble, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }
        root = box
        lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = resources.displayMetrics.widthPixels - dp(128)
            y = (resources.displayMetrics.heightPixels * 0.5f).toInt()
        }
        posX = lp.x.toFloat()
        try {
            w.addView(box, lp)
        } catch (e: Exception) {
            Toast.makeText(this, "Please allow 'Display over other apps' for Dost", Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }
        val c = Conversation(this, face, { say(it) }, { MainActivity.instance?.refreshLists() })
        c.onUserSpoke = { markActive() }
        conv = c
        touch()
        sensors = getSystemService(SENSOR_SERVICE) as SensorManager
        sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensors?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        main.postDelayed(idleCheck, 10_000)
        face.setEmotion("love", 2500)
        c.greet()
    }

    private fun dp(v: Int) = (v * d).toInt()

    private fun relayout() {
        try { wm?.updateViewLayout(root, lp) } catch (e: Exception) { }
    }

    private fun say(text: String) {
        if (mini) return
        bubble.text = text
        bubble.visibility = View.VISIBLE
        bubble.removeCallbacks(hideBubble)
        bubble.postDelayed(hideBubble, (4000L + text.length * 70L).coerceAtMost(15000L))
    }

    // ---------------------------------------------------------------- lines
    private fun line(k: String): String = conv?.store?.let { Lines.get(it, k) } ?: ""

    private fun markActive() {
        lastActive = now()
        idleStage = 0
    }

    // ---------------------------------------------------------------- touches
    private var pendingTap: Runnable? = null
    private var lastTapAt = 0L

    private fun touch() {
        var sx = 0f
        var sy = 0f
        var ox = 0
        var oy = 0
        var mode = 0            // 0 = not sure yet, 1 = dragging the window, 2 = rubbing on the face
        var longDone = false
        var lastMx = 0f
        var lastMy = 0f
        var lastDir = 0
        var reversals = 0
        var angleSum = 0.0
        var prevAng = Double.NaN
        var segX = 0f
        var segY = 0f
        var dizzyDone = false
        var rubDone = false
        var downAt = 0L
        var fx = 100f
        var fy = 100f
        val longPress = Runnable {
            if (mode == 0 && !longDone) {
                longDone = true
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        face.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    markActive()
                    sx = e.rawX; sy = e.rawY; ox = lp.x; oy = lp.y
                    mode = 0; longDone = false
                    lastMx = e.rawX; lastMy = e.rawY; lastDir = 0; reversals = 0
                    angleSum = 0.0; prevAng = Double.NaN; segX = e.rawX; segY = e.rawY
                    dizzyDone = false; rubDone = false; downAt = now()
                    val f = face.toFace(e.x, e.y)
                    fx = f[0]; fy = f[1]
                    face.lookAtView(e.x, e.y)
                    face.postDelayed(longPress, 700)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - sx
                    val dy = e.rawY - sy
                    if (mode != 1) face.lookAtView(e.x, e.y)
                    // count back-and-forth strokes
                    val mx = e.rawX - lastMx
                    val my = e.rawY - lastMy
                    if (abs(mx) + abs(my) > 6 * d) {
                        val dir = if (abs(mx) >= abs(my)) (if (mx > 0) 1 else -1) else (if (my > 0) 2 else -2)
                        if (lastDir != 0 && dir == -lastDir) reversals++
                        lastDir = dir
                        lastMx = e.rawX; lastMy = e.rawY
                        face.removeCallbacks(longPress)
                    }
                    if (mode == 0 && hypot(dx, dy) > 45 * d && reversals < 2) {
                        mode = 1
                        dragging = true
                    }
                    if (mode == 0 && reversals >= 2) mode = 2

                    if (mode == 1) {
                        lp.x = ox + dx.toInt()
                        lp.y = oy + dy.toInt()
                        posX = lp.x.toFloat(); vx = 0f
                        relayout()
                        // circles: add up how much the finger's direction turns
                        val gx = e.rawX - segX
                        val gy = e.rawY - segY
                        if (hypot(gx, gy) > 10 * d) {
                            val a = atan2(gy.toDouble(), gx.toDouble())
                            if (!prevAng.isNaN()) {
                                var da = a - prevAng
                                while (da > PI) da -= 2 * PI
                                while (da < -PI) da += 2 * PI
                                angleSum += da
                            }
                            prevAng = a; segX = e.rawX; segY = e.rawY
                        }
                        if (!dizzyDone && abs(angleSum) > 2 * PI * 1.5) { dizzyDone = true; dizzy() }
                    }

                    if (mode == 2 && !rubDone && reversals >= 4) {
                        when {
                            fy < 52f -> { rubDone = true; itchHead() }
                            fx < 36f || fx > 164f -> { rubDone = true; scratchEar() }
                            now() - downAt > 1200 -> { rubDone = true; softRub() }
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    face.removeCallbacks(longPress)
                    dragging = false
                    if (e.actionMasked == MotionEvent.ACTION_UP && mode == 0 && !longDone) tap(fx, fy)
                    true
                }
                else -> false
            }
        }
    }

    private fun tap(fx: Float, fy: Float) {
        if (asleep || mini) { wake(); return }
        val inEye = hypot(fx - 72f, fy - 92f) < 20f || hypot(fx - 128f, fy - 92f) < 20f
        if (inEye) {
            pendingTap?.let { main.removeCallbacks(it) }
            pendingTap = null
            ouch()
            return
        }
        val t = now()
        val p = pendingTap
        if (p != null && t - lastTapAt < 320) {   // two quick taps = a punch
            main.removeCallbacks(p)
            pendingTap = null
            punch()
            return
        }
        lastTapAt = t
        val r = Runnable { pendingTap = null; singleTap() }
        pendingTap = r
        main.postDelayed(r, 320)
    }

    private fun singleTap() {
        val c = conv ?: return
        if (c.active) say("…") else face.setEmotion("happy")
        c.toggle()
    }

    // ---------------------------------------------------------------- reactions
    private fun react(key: String, emo: String, hold: Long, listenAfter: Boolean = false, after: () -> Unit = {}) {
        stopSnore()
        asleep = false
        conv?.react(line(key), emo, hold, listenAfter, after)
    }

    private fun ouch() = react("ouch", "ouch", 1800)

    private fun punch() {
        face.shake(500)
        Sfx.grunt()
        react("angry", "angry", 5000)
    }

    private fun scratchEar() {
        face.shake(1400)
        react("nono", "sad", 2500)
    }

    private fun itchHead() {
        Sfx.giggle()
        react("laugh", "laugh", 3500)
    }

    private fun dizzy() {
        Sfx.dizzy()
        react("dizzy", "dizzy", 3500)
    }

    private fun softRub() {
        conv?.stop()
        react("sleep", "sleepy", 0) { goSleep(snore = true) }
    }

    // ---------------------------------------------------------------- sleep
    private val snoreLoop = object : Runnable {
        override fun run() {
            if (!face.snoring) return
            Sfx.snore()
            main.postDelayed(this, 2800)
        }
    }

    private fun goSleep(snore: Boolean) {
        conv?.stop()
        asleep = true
        face.setEmotion("asleep")
        face.snoring = snore
        bubble.visibility = View.GONE
        if (snore) { main.removeCallbacks(snoreLoop); main.postDelayed(snoreLoop, 600) }
    }

    private fun stopSnore() {
        face.snoring = false
        main.removeCallbacks(snoreLoop)
    }

    private fun wake() {
        stopSnore()
        asleep = false
        if (mini) exitMini()
        markActive()
        react("wake", "surprised", 1500)
    }

    // ---------------------------------------------------------------- idle: ask, then shrink to a corner
    private val idleCheck = object : Runnable {
        override fun run() {
            val c = conv
            val busy = (c != null && (c.active || c.busy)) || dragging
            val idle = now() - lastActive
            if (!busy && !asleep && !mini) {
                if (idleStage == 0 && idle > 3 * 60_000L) {
                    idleStage = 1
                    stageAt = now()
                    react("quiet", "sad", 5000, listenAfter = true)
                } else if (idleStage == 1 && now() - stageAt > 60_000L) {
                    idleStage = 2
                    enterMini()
                }
            }
            main.postDelayed(this, 10_000)
        }
    }

    private fun animateTo(tx: Int, ty: Int) {
        val fx0 = lp.x
        val fy0 = lp.y
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 700
            addUpdateListener {
                val f = it.animatedValue as Float
                lp.x = (fx0 + (tx - fx0) * f).toInt()
                lp.y = (fy0 + (ty - fy0) * f).toInt()
                posX = lp.x.toFloat()
                relayout()
            }
            start()
        }
    }

    private fun resizeFace(sizeDp: Int) {
        face.layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        root?.requestLayout()
        relayout()
    }

    private fun enterMini() {
        conv?.stop()
        mini = true
        goSleep(snore = false)
        resizeFace(56)
        val m = resources.displayMetrics
        animateTo(m.widthPixels - dp(62), m.heightPixels - dp(200))
    }

    private fun exitMini() {
        mini = false
        resizeFace(112)
        val m = resources.displayMetrics
        animateTo(m.widthPixels - dp(128), (m.heightPixels * 0.5f).toInt())
    }

    // ---------------------------------------------------------------- tilt: slide to the lower side
    override fun onSensorChanged(e: SensorEvent) {
        if (dragging || mini || asleep || root == null) return
        val ax = -e.values[0]                 // positive when the right side of the phone is lower
        val w = root?.width?.takeIf { it > 0 } ?: dp(112)
        val maxX = (resources.displayMetrics.widthPixels - w).toFloat().coerceAtLeast(0f)
        if (abs(ax) > 2.8f) vx += ax * 0.35f * d
        vx *= 0.90f
        if (abs(vx) < 0.3f) {
            vx = 0f
            sliding = false
            return
        }
        posX = (posX + vx).coerceIn(0f, maxX)
        if (posX <= 0f || posX >= maxX) vx = 0f
        lp.x = posX.toInt()
        relayout()
        face.lookDir(if (vx > 0) 1f else -1f, 0.3f)
        if (!sliding && abs(vx) > 1.5f * d) {
            sliding = true
            if (now() - lastOoo > 2500) {
                lastOoo = now()
                Sfx.ooo()
                face.setEmotion("surprised", 1500)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ---------------------------------------------------------------- misc
    fun remind(t: String) {
        if (asleep || mini) { stopSnore(); asleep = false; if (mini) exitMini() }
        markActive()
        conv?.remind(t)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        instance = null
        main.removeCallbacksAndMessages(null)
        try { sensors?.unregisterListener(this) } catch (e: Exception) { }
        conv?.shutdown()
        try { root?.let { wm?.removeView(it) } } catch (e: Exception) { }
        super.onDestroy()
    }
}
