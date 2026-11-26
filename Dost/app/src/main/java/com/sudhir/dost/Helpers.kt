package com.sudhir.dost

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract

object Notify {
    const val CH_REM = "dost_rem"
    const val CH_FLOAT = "dost_float"

    fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CH_REM, "Reminders", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_FLOAT, "Floating Dost", NotificationManager.IMPORTANCE_LOW))
    }

    fun reminder(ctx: Context, id: Int, text: String) {
        channels(ctx)
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(ctx, CH_REM)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Dost reminder")
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        try { ctx.getSystemService(NotificationManager::class.java)?.notify(id, n) } catch (e: SecurityException) { }
    }
}

object Reminders {
    private fun pi(ctx: Context, id: Int, text: String): PendingIntent {
        val i = Intent(ctx, ReminderReceiver::class.java).putExtra("t", text).putExtra("id", id)
        return PendingIntent.getBroadcast(ctx, id, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun canBeExact(ctx: Context): Boolean {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return false
        return Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
    }

    fun add(ctx: Context, store: Store, text: String, at: Long) {
        val id = store.addReminder(text, at)
        schedule(ctx, id, text, at)
    }

    fun schedule(ctx: Context, id: Int, text: String, at: Long) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val p = pi(ctx, id, text)
        try {
            if (canBeExact(ctx)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
        }
    }

    fun cancel(ctx: Context, store: Store, id: Int, text: String) {
        ctx.getSystemService(AlarmManager::class.java)?.cancel(pi(ctx, id, text))
        store.removeReminder(id)
    }

    fun rescheduleAll(ctx: Context) {
        val store = Store(ctx)
        val now = System.currentTimeMillis()
        store.reminders().filter { !it.optBoolean("f") && it.optLong("at") > now }.forEach {
            schedule(ctx, it.optInt("id"), it.optString("t"), it.optLong("at"))
        }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val t = intent.getStringExtra("t") ?: return
        val id = intent.getIntExtra("id", 0)
        Store(ctx).markFired(id)
        Notify.reminder(ctx, id, t)
        val f = FloatService.instance
        if (f != null) f.remind(t) else MainActivity.instance?.remind(t)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) Reminders.rescheduleAll(ctx)
    }
}

object Phone {
    fun find(ctx: Context, name: String): Pair<String, String>? {
        val n = name.trim()
        if (n.isEmpty()) return null
        if (ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        val cols = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        try {
            ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI, cols,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?", arrayOf("%$n%"), null
            )?.use { c ->
                if (c.moveToFirst()) return (c.getString(0) ?: n) to (c.getString(1) ?: "")
            }
        } catch (e: Exception) { }
        val first = n.split(" ").first()
        if (first != n && first.length > 1) return find(ctx, first)
        return null
    }

    fun dial(ctx: Context, number: String) {
        val direct = ctx.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        val i = Intent(if (direct) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number)))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { ctx.startActivity(i) } catch (e: Exception) { }
    }
}
