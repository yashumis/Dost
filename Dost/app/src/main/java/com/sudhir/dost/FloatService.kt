package com.sudhir.dost

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

/** Keeps Dost's face floating over every app. Tap = talk / stop, drag = move, long-press = open the app. */
class FloatService : Service() {

    companion object {
        var instance: FloatService? = null
    }

    private var wm: WindowManager? = null
    private var root: LinearLayout? = null
    private lateinit var face: FaceView
    private lateinit var bubble: TextView
    private lateinit var lp: WindowManager.LayoutParams
    private var conv: Conversation? = null
    private val hideBubble = Runnable { bubble.visibility = View.GONE }

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
        val d = resources.displayMetrics.density
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
            addView(face, LinearLayout.LayoutParams((112 * d).toInt(), (112 * d).toInt()))
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
            x = (resources.displayMetrics.widthPixels - 128 * d).toInt()
            y = (resources.displayMetrics.heightPixels * 0.5f).toInt()
        }
        try {
            w.addView(box, lp)
        } catch (e: Exception) {
            Toast.makeText(this, "Please allow 'Display over other apps' for Dost", Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }
        conv = Conversation(this, face, { say(it) }, { MainActivity.instance?.refreshLists() })
        touch()
        face.setEmotion("love", 2500)
        conv?.greet()
    }

    private fun say(text: String) {
        bubble.text = text
        bubble.visibility = View.VISIBLE
        bubble.removeCallbacks(hideBubble)
        bubble.postDelayed(hideBubble, (4000L + text.length * 70L).coerceAtMost(15000L))
    }

    private fun touch() {
        var sx = 0f
        var sy = 0f
        var ox = 0
        var oy = 0
        var moved = false
        val longPress = Runnable {
            if (!moved) {
                moved = true
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        face.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    sx = e.rawX; sy = e.rawY; ox = lp.x; oy = lp.y; moved = false
                    face.postDelayed(longPress, 650)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - sx
                    val dy = e.rawY - sy
                    if (!moved && abs(dx) + abs(dy) > 14) { moved = true; face.removeCallbacks(longPress) }
                    if (moved) {
                        lp.x = ox + dx.toInt()
                        lp.y = oy + dy.toInt()
                        try { wm?.updateViewLayout(root, lp) } catch (ex: Exception) { }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    face.removeCallbacks(longPress)
                    if (!moved && e.action == MotionEvent.ACTION_UP) {
                        val c = conv
                        if (c != null) {
                            if (c.active) say("…") else face.setEmotion("happy")
                            c.toggle()
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    fun remind(t: String) { conv?.remind(t) }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        instance = null
        conv?.shutdown()
        try { root?.let { wm?.removeView(it) } } catch (e: Exception) { }
        super.onDestroy()
    }
}
