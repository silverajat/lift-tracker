package com.silverajat.lifttracker.journal

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.silverajat.lifttracker.MainActivity
import com.silverajat.lifttracker.R
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Reminders from the Productivity tab: a plain notification that pops up at the set time.
 * No alarm, no full-screen, no vibration (the channel turns it off), just the popup.
 */
object Reminders {
    const val CHANNEL = "REMINDER"
    private const val ACTION_FIRE = "com.silverajat.lifttracker.REMIND"
    private const val ACTION_SNOOZE = "com.silverajat.lifttracker.REMIND_SNOOZE"
    private const val ACTION_NIGHT = "com.silverajat.lifttracker.REFLECT_TIME"
    private const val SNOOZE_MIN = 10L

    fun channel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Things you asked to be reminded about. Pops up, doesn't buzz."
            enableVibration(false)
        })
    }

    private fun intent(ctx: Context, action: String, id: String) =
        Intent(ctx, ReminderReceiver::class.java).setAction(action).putExtra("id", id)

    private fun pending(ctx: Context, action: String, id: String) = PendingIntent.getBroadcast(
        ctx, (action + id).hashCode(), intent(ctx, action, id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun millis(t: LocalDateTime) = t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    fun schedule(ctx: Context, r: Reminder) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val pi = pending(ctx, ACTION_FIRE, r.id)
        // exact when allowed (USE_EXACT_ALARM), otherwise Android may let it drift a few minutes
        runCatching {
            if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis(r.at), pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis(r.at), pi)
        }.onFailure { runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis(r.at), pi) } }
    }

    fun cancel(ctx: Context, id: String) {
        ctx.getSystemService(AlarmManager::class.java)?.cancel(pending(ctx, ACTION_FIRE, id))
        NotificationManagerCompat.from(ctx).cancel(id.hashCode())
    }

    /**
     * The 30-minute background check could leave the night "tick off and reflect" notification up to half an hour late,
     * so an exact alarm runs the check right at that time each night.
     */
    fun scheduleNight(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val now = LocalDateTime.now()
        var t = now.toLocalDate().atTime(com.silverajat.lifttracker.insights.InsightEngine.REFLECT_AT).plusSeconds(20)
        if (!t.isAfter(now)) t = t.plusDays(1)
        val pi = pending(ctx, ACTION_NIGHT, "night")
        runCatching {
            if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis(t), pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis(t), pi)
        }
    }

    private fun runChecksNow(ctx: Context) {
        val req = androidx.work.OneTimeWorkRequestBuilder<com.silverajat.lifttracker.insights.InsightWorker>()
            .setExpedited(androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST).build()
        androidx.work.WorkManager.getInstance(ctx).enqueueUniqueWork("reflect-now", androidx.work.ExistingWorkPolicy.REPLACE, req)
    }

    /** After a reboot, an update or opening the app: set every alarm again, and send any that were missed. */
    fun restore(ctx: Context) {
        scheduleNight(ctx)
        val store = JournalStore(ctx.filesDir)
        val now = LocalDateTime.now()
        val rs = store.reminders()
        var changed = false
        val out = rs.map { r ->
            when {
                r.fired -> r
                r.at.isAfter(now) -> { schedule(ctx, r); r }
                // missed while the phone was off: still worth showing if it's within a few hours
                else -> { if (r.at.isAfter(now.minusHours(6))) show(ctx, r); changed = true; r.copy(fired = true) }
            }
        }
        if (changed) store.saveReminders(out)
    }

    internal fun fire(ctx: Context, id: String) {
        val store = JournalStore(ctx.filesDir)
        val rs = store.reminders()
        val r = rs.firstOrNull { it.id == id } ?: return
        if (r.fired) return
        show(ctx, r)
        store.saveReminders(rs.map { if (it.id == id) it.copy(fired = true) else it })
    }

    internal fun snooze(ctx: Context, id: String) {
        val store = JournalStore(ctx.filesDir)
        val rs = store.reminders()
        val r = rs.firstOrNull { it.id == id } ?: return
        val again = r.copy(at = LocalDateTime.now().plusMinutes(SNOOZE_MIN).withSecond(0).withNano(0), fired = false)
        store.saveReminders(rs.map { if (it.id == id) again else it })
        NotificationManagerCompat.from(ctx).cancel(id.hashCode())
        schedule(ctx, again)
    }

    private val hm = DateTimeFormatter.ofPattern("H:mm")

    private fun show(ctx: Context, r: Reminder) {
        if (ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        channel(ctx)
        val open = PendingIntent.getActivity(ctx, 7,
            Intent(ctx, MainActivity::class.java).putExtra("tab", "DAY").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_lift)
            .setContentTitle(r.text)
            .setContentText("Reminder for ${r.at.format(hm)}")
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(0, "Snooze $SNOOZE_MIN min", pending(ctx, ACTION_SNOOZE, r.id))
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(r.id.hashCode(), n) }
    }

    internal fun handle(ctx: Context, i: Intent) {
        val id = i.getStringExtra("id")
        when (i.action) {
            ACTION_FIRE -> if (id != null) fire(ctx, id)
            ACTION_SNOOZE -> if (id != null) snooze(ctx, id)
            ACTION_NIGHT -> { runChecksNow(ctx); scheduleNight(ctx) }
            else -> restore(ctx)   // boot / app updated / time zone changed
        }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        val done = goAsync()
        Thread {
            try { Reminders.handle(ctx.applicationContext, i) } finally { done.finish() }
        }.start()
    }
}
