package com.amin.parking

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.telephony.SmsManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ParkingService : Service() {

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var loopJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = buildNotification("Parking Active")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            handleStop()
            return START_NOT_STICKY
        }
        loopJob?.cancel()
        loopJob = serviceScope.launch {
            runParkingLoop()
        }
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        loopJob?.cancel()
        serviceJob.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun runParkingLoop() {
        val prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_PHONE, "").orEmpty().trim().isEmpty()) {
            prefs.edit().putBoolean(KEY_RUNNING, false).apply()
            stopSelf()
            return
        }

        while (serviceScope.isActive) {
            if (!prefs.getBoolean(KEY_RUNNING, false)) {
                stopSelf()
                return
            }

            val phone = prefs.getString(KEY_PHONE, "").orEmpty().trim()
            val text1 = prefs.getString(KEY_TEXT1, "").orEmpty()
            val text2 = prefs.getString(KEY_TEXT2, "").orEmpty()
            val intervalMinutes = prefs.getInt(KEY_INTERVAL_MINUTES, 55).coerceAtLeast(1)

            if (phone.isEmpty()) {
                prefs.edit().putBoolean(KEY_RUNNING, false).apply()
                stopSelf()
                return
            }

            acquireWakeLock()
            try {
                sendSms(phone, text1)
                prefs.edit().putBoolean(KEY_IS_PARKING_ACTIVE, true).apply()
                val nextAfterText1 = System.currentTimeMillis() + intervalMinutes * 60L * 1000L
                prefs.edit().putLong(KEY_NEXT_TRIGGER_TIME, nextAfterText1).commit()
                countdownDelay(nextAfterText1)
                if (!serviceScope.isActive) return

                if (!prefs.getBoolean(KEY_RUNNING, false)) {
                    stopSelf()
                    return
                }

                sendSms(phone, text2)
                prefs.edit().putBoolean(KEY_IS_PARKING_ACTIVE, false).apply()
                val nextAfterText2 = System.currentTimeMillis() + 60_000L
                prefs.edit().putLong(KEY_NEXT_TRIGGER_TIME, nextAfterText2).commit()
                countdownDelay(nextAfterText2)
                if (!serviceScope.isActive) return
            } finally {
                releaseWakeLock()
            }
        }
    }

    private suspend fun countdownDelay(targetTime: Long) {
        Log.d("AminParking", "Next trigger at: $targetTime")
        var lastUpdate = 0L

        while (true) {
            val remaining = targetTime - System.currentTimeMillis()
            if (remaining <= 0) break

            val now = System.currentTimeMillis()
            if (now - lastUpdate >= 5000) {
                updateNotification(formatTime(remaining))
                lastUpdate = now
            }

            delay(1000)
        }
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "Next in %02d:%02d".format(minutes, seconds)
    }

    private fun handleStop() {
        val prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val isParkingActive = prefs.getBoolean(KEY_IS_PARKING_ACTIVE, false)
        val phone = prefs.getString(KEY_PHONE, "").orEmpty().trim()
        val text2 = prefs.getString(KEY_TEXT2, "").orEmpty()

        if (isParkingActive && phone.isNotEmpty()) {
            sendSms(phone, text2)
        }

        prefs.edit()
            .putBoolean(KEY_IS_PARKING_ACTIVE, false)
            .putBoolean(KEY_RUNNING, false)
            .putLong(KEY_NEXT_TRIGGER_TIME, 0L)
            .commit()

        loopJob?.cancel()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun sendSms(phone: String, message: String) {
        if (!hasSendSmsPermission()) {
            Log.w(TAG, "SEND_SMS permission missing")
            return
        }
        try {
            val manager = smsManager(this)
            val parts = manager.divideMessage(message.ifEmpty { " " })
            manager.sendMultipartTextMessage(phone, null, parts, null, null)
            Log.d(TAG, "SMS queued to $phone (${message.length} chars)")
        } catch (e: Exception) {
            Log.e(TAG, "sendMultipartTextMessage failed", e)
        }
    }

    private fun hasSendSmsPermission(): Boolean {
        return checkSelfPermission(android.Manifest.permission.SEND_SMS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun updateNotification(status: String) {
        val notification = buildNotification(status)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(status: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AminParking")
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        // User must disable battery optimization for full reliability
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (wakeLock == null) {
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "AminParking::WakeLock",
            ).apply {
                setReferenceCounted(false)
            }
        }
        if (wakeLock?.isHeld != true) {
            wakeLock?.acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "AminParking",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Parking service"
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "AminParking"
        private const val CHANNEL_ID = "amin_parking_channel"
        private const val NOTIFICATION_ID = 1001

        const val PREF_NAME = "AminParking"
        const val KEY_PHONE = "phone"
        const val KEY_TEXT1 = "text1"
        const val KEY_TEXT2 = "text2"
        const val KEY_RUNNING = "running"
        const val KEY_INTERVAL_MINUTES = "interval_minutes"
        const val KEY_IS_PARKING_ACTIVE = "is_parking_active"
        const val KEY_NEXT_TRIGGER_TIME = "next_trigger_time"
        const val ACTION_STOP = "ACTION_STOP"

        private fun smsManager(context: Context): SmsManager {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
        }
    }
}
