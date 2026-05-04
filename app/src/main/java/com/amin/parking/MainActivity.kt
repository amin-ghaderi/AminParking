package com.amin.parking

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var editPhone: EditText
    private lateinit var editText1: EditText
    private lateinit var editText2: EditText
    private lateinit var editInterval: EditText
    private lateinit var textStatus: TextView
    private lateinit var textCountdown: TextView
    private lateinit var buttonStart: Button
    private lateinit var buttonStop: Button

    private val handler = Handler(Looper.getMainLooper())
    private val countdownRunnable = object : Runnable {
        override fun run() {
            updateCountdownUI()
            handler.postDelayed(this, 1000)
        }
    }

    private val requestSmsPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            startParkingLoop()
        } else {
            Toast.makeText(this, R.string.toast_sms_denied, Toast.LENGTH_LONG).show()
            Log.w(TAG, "SEND_SMS permission denied")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        editPhone = findViewById(R.id.editPhone)
        editText1 = findViewById(R.id.editText1)
        editText2 = findViewById(R.id.editText2)
        editInterval = findViewById(R.id.editInterval)
        textStatus = findViewById(R.id.textStatus)
        textCountdown = findViewById(R.id.textCountdown)
        buttonStart = findViewById(R.id.buttonStart)
        buttonStop = findViewById(R.id.buttonStop)

        loadFieldsFromPrefs()

        buttonStart.setOnClickListener {
            if (hasSendSmsPermission()) {
                startParkingLoop()
            } else {
                requestSmsPermission.launch(Manifest.permission.SEND_SMS)
            }
        }

        buttonStop.setOnClickListener {
            stopParkingLoop()
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatusFromPrefs()
        handler.post(countdownRunnable)
    }

    override fun onPause() {
        handler.removeCallbacks(countdownRunnable)
        super.onPause()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_POST_NOTIFICATIONS) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startParkingLoop()
            } else {
                Toast.makeText(
                    this,
                    "Notification permission needed to show parking status",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun loadFieldsFromPrefs() {
        val prefs = getSharedPreferences(ParkingService.PREF_NAME, MODE_PRIVATE)
        editPhone.setText(prefs.getString(ParkingService.KEY_PHONE, ""))
        editText1.setText(prefs.getString(ParkingService.KEY_TEXT1, ""))
        editText2.setText(prefs.getString(ParkingService.KEY_TEXT2, ""))
        editInterval.setText(prefs.getInt(ParkingService.KEY_INTERVAL_MINUTES, 55).toString())
    }

    private fun updateStatusFromPrefs() {
        val running = getSharedPreferences(ParkingService.PREF_NAME, MODE_PRIVATE)
            .getBoolean(ParkingService.KEY_RUNNING, false)
        textStatus.text = if (running) "🟢 Parking Active" else "🔴 Stopped"
        buttonStart.isEnabled = !running
        buttonStop.isEnabled = running
    }

    private fun updateCountdownUI() {
        val prefs = getSharedPreferences(ParkingService.PREF_NAME, MODE_PRIVATE)
        val running = prefs.getBoolean(ParkingService.KEY_RUNNING, false)
        val nextTime = prefs.getLong(ParkingService.KEY_NEXT_TRIGGER_TIME, 0L)

        if (!running || nextTime == 0L) {
            textCountdown.text = "--:--"
            return
        }

        val remaining = nextTime - System.currentTimeMillis()

        if (remaining <= 0) {
            textCountdown.text = "00:00"
        } else {
            val totalSeconds = remaining / 1000
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            textCountdown.text = "%02d:%02d".format(minutes, seconds)
        }
    }

    private fun hasSendSmsPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.SEND_SMS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun startParkingLoop() {
        val phone = editPhone.text.toString().trim()
        val text1 = editText1.text.toString()
        val text2 = editText2.text.toString()
        val intervalMinutes = editInterval.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: 55

        if (phone.isEmpty()) {
            Toast.makeText(this, R.string.toast_need_phone, Toast.LENGTH_SHORT).show()
            return
        }

        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                    REQUEST_POST_NOTIFICATIONS,
                )
                return
            }
        }

        getSharedPreferences(ParkingService.PREF_NAME, MODE_PRIVATE)
            .edit()
            .putString(ParkingService.KEY_PHONE, phone)
            .putString(ParkingService.KEY_TEXT1, text1)
            .putString(ParkingService.KEY_TEXT2, text2)
            .putInt(ParkingService.KEY_INTERVAL_MINUTES, intervalMinutes)
            .putBoolean(ParkingService.KEY_RUNNING, true)
            .putBoolean(ParkingService.KEY_IS_PARKING_ACTIVE, false)
            .apply()

        val serviceIntent = Intent(this, ParkingService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
        textStatus.text = "🟢 Parking Active"
        buttonStart.isEnabled = false
        buttonStop.isEnabled = true
        Toast.makeText(this, R.string.toast_started, Toast.LENGTH_SHORT).show()
        Log.d(TAG, "Parking loop started for $phone")
    }

    private fun stopParkingLoop() {
        val stopIntent = Intent(this, ParkingService::class.java).apply {
            action = ParkingService.ACTION_STOP
        }
        ContextCompat.startForegroundService(this, stopIntent)
        textStatus.text = "🔴 Stopped"
        buttonStart.isEnabled = true
        buttonStop.isEnabled = false
        Toast.makeText(this, R.string.toast_stopped, Toast.LENGTH_SHORT).show()
        Log.d(TAG, "Parking loop stopped")
    }

    companion object {
        private const val TAG = "AminParking"
        private const val REQUEST_POST_NOTIFICATIONS = 1001
    }
}
