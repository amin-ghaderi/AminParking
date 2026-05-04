package com.amin.parking

import android.content.Context
import android.os.Build
import android.telephony.SmsManager
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class ParkingWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val prefs = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

        if (!prefs.getBoolean(KEY_RUNNING, false)) {
            Log.d(TAG, "Stopped — exiting worker")
            return Result.success()
        }

        val phone = prefs.getString(KEY_PHONE, "").orEmpty().trim()
        val text1 = prefs.getString(KEY_TEXT1, "").orEmpty()
        val text2 = prefs.getString(KEY_TEXT2, "").orEmpty()

        if (phone.isEmpty()) {
            Log.w(TAG, "No phone number saved")
            return Result.success()
        }

        val sendText1Next = prefs.getBoolean(KEY_SEND_TEXT1_NEXT, true)
        val body = if (sendText1Next) text1 else text2

        sendSms(phone, body)

        val nextSendText1: Boolean
        val delayMinutes: Long
        if (sendText1Next) {
            nextSendText1 = false
            delayMinutes = DELAY_AFTER_START_MESSAGE_MIN
            Log.d(TAG, "Sent start message; next work in ${delayMinutes}m")
        } else {
            nextSendText1 = true
            delayMinutes = DELAY_AFTER_STOP_MESSAGE_MIN
            Log.d(TAG, "Sent stop message; next work in ${delayMinutes}m")
        }

        prefs.edit()
            .putBoolean(KEY_SEND_TEXT1_NEXT, nextSendText1)
            .commit()

        if (!prefs.getBoolean(KEY_RUNNING, false)) {
            Log.d(TAG, "Running flag cleared — not rescheduling")
            return Result.success()
        }

        val next = OneTimeWorkRequestBuilder<ParkingWorker>()
            .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(ctx).enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            next,
        )

        return Result.success()
    }

    private fun sendSms(phone: String, message: String) {
        try {
            smsManager(applicationContext).sendTextMessage(phone, null, message, null, null)
            Log.d(TAG, "SMS queued to $phone (${message.length} chars)")
        } catch (e: Exception) {
            Log.e(TAG, "sendTextMessage failed", e)
        }
    }

    companion object {
        private const val TAG = "AminParking"

        const val WORK_NAME = "AminParkingWork"
        const val PREF_NAME = "AminParking"

        const val KEY_PHONE = "phone"
        const val KEY_TEXT1 = "text1"
        const val KEY_TEXT2 = "text2"
        const val KEY_RUNNING = "running"
        const val KEY_SEND_TEXT1_NEXT = "send_text1_next"

        private const val DELAY_AFTER_START_MESSAGE_MIN = 55L
        private const val DELAY_AFTER_STOP_MESSAGE_MIN = 1L

        fun enqueueFirst(context: Context) {
            val request = OneTimeWorkRequestBuilder<ParkingWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request,
            )
            Log.d(TAG, "First worker enqueued (immediate)")
        }

        fun cancelAll(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.d(TAG, "Cancelled unique work")
        }

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
