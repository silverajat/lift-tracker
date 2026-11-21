package com.silverajat.lifttracker.activity

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.LocationListener
import android.location.LocationManager
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import com.silverajat.lifttracker.MainActivity
import com.silverajat.lifttracker.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

/** Keeps a live run or session going with the screen off: GPS, a notification, km splits read out. */
class RunService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var tts: TextToSpeech? = null
    private val listener = LocationListener { RunTracker.onLocation(it) }
    /** Keeps the CPU awake with the screen off, so GPS fixes keep coming on phones that throttle background apps. */
    private var wake: android.os.PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Live run or session", NotificationManager.IMPORTANCE_LOW))
        tts = TextToSpeech(this) { ok -> if (ok == TextToSpeech.SUCCESS) tts?.language = Locale.UK }
        RunTracker.onSplit = { km, split, total ->
            vibrate()
            tts?.speak("$km kilometre${if (km > 1) "s" else ""}. Split ${spoken(split)}. Total ${spoken(total)}.",
                TextToSpeech.QUEUE_FLUSH, null, "split$km")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val gps = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!gps) { stopSelf(); return START_NOT_STICKY }
        // restarted after the phone killed the app: carry on with the run it was tracking
        if (!RunTracker.state.value.active) RunTracker.restore(this, fromService = true)
        startForeground(ID, note(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        if (!RunTracker.state.value.active) { stopSelf(); return START_NOT_STICKY }
        if (wake == null) wake = getSystemService(android.os.PowerManager::class.java)
            .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "lifttracker:run").apply { acquire(6 * 60 * 60 * 1000L) }
        runCatching {
            getSystemService(LocationManager::class.java).requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
        }
        scope.coroutineContext[kotlinx.coroutines.Job]?.children?.forEach { it.cancel() }
        scope.launch {
            var n = 0
            while (isActive) {
                delay(5000)
                if (!RunTracker.state.value.active) { stopSelf(); break }
                getSystemService(NotificationManager::class.java).notify(ID, note())
                if (++n % 3 == 0) RunTracker.snapshot(this@RunService)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { getSystemService(LocationManager::class.java).removeUpdates(listener) }
        runCatching { wake?.takeIf { it.isHeld }?.release() }
        wake = null
        RunTracker.onSplit = null
        tts?.shutdown()
        scope.cancel()
        super.onDestroy()
    }

    private fun note(): Notification {
        val s = RunTracker.state.value
        val t = (s.movingMs(System.currentTimeMillis()) / 1000).toInt()
        val text = buildString {
            append(ActivityLog.clock(t))
            if (s.usesGps) append(", %.2f km".format(s.distanceM / 1000))
            s.paceSPerKm?.let { append(", " + ActivityLog.pace(it)) }
            if (s.paused) append(" (paused)")
        }
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java).putExtra("tab", "SPORTS")
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_lift)
            .setContentTitle("${s.sport.emoji} ${s.sport.label} in progress")
            .setContentText(text)
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open)
            .build()
    }

    private fun vibrate() = runCatching {
        getSystemService(Vibrator::class.java).vibrate(VibrationEffect.createWaveform(longArrayOf(0, 250, 120, 250), -1))
    }

    private fun spoken(s: Int): String {
        val h = s / 3600; val m = s / 60 % 60; val sec = s % 60
        return (if (h > 0) "$h hour${if (h > 1) "s" else ""} " else "") + "$m minute${if (m != 1) "s" else ""} $sec"
    }

    companion object {
        const val CHANNEL = "live"
        const val ID = 4242
    }
}
