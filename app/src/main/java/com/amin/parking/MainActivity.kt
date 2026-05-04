package com.amin.parking

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var editPhone: EditText
    private lateinit var editText1: EditText
    private lateinit var editText2: EditText

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

        loadFieldsFromPrefs()

        findViewById<Button>(R.id.buttonStart).setOnClickListener {
            if (hasSendSmsPermission()) {
                startParkingLoop()
            } else {
                requestSmsPermission.launch(Manifest.permission.SEND_SMS)
            }
        }

        findViewById<Button>(R.id.buttonStop).setOnClickListener {
            stopParkingLoop()
        }
    }

    private fun loadFieldsFromPrefs() {
        val prefs = getSharedPreferences(ParkingWorker.PREF_NAME, MODE_PRIVATE)
        editPhone.setText(prefs.getString(ParkingWorker.KEY_PHONE, ""))
        editText1.setText(prefs.getString(ParkingWorker.KEY_TEXT1, ""))
        editText2.setText(prefs.getString(ParkingWorker.KEY_TEXT2, ""))
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

        if (phone.isEmpty()) {
            Toast.makeText(this, R.string.toast_need_phone, Toast.LENGTH_SHORT).show()
            return
        }

        getSharedPreferences(ParkingWorker.PREF_NAME, MODE_PRIVATE)
            .edit()
            .putString(ParkingWorker.KEY_PHONE, phone)
            .putString(ParkingWorker.KEY_TEXT1, text1)
            .putString(ParkingWorker.KEY_TEXT2, text2)
            .putBoolean(ParkingWorker.KEY_RUNNING, true)
            .putBoolean(ParkingWorker.KEY_SEND_TEXT1_NEXT, true)
            .apply()

        ParkingWorker.enqueueFirst(this)
        Toast.makeText(this, R.string.toast_started, Toast.LENGTH_SHORT).show()
        Log.d(TAG, "Parking loop started for $phone")
    }

    private fun stopParkingLoop() {
        getSharedPreferences(ParkingWorker.PREF_NAME, MODE_PRIVATE)
            .edit()
            .putBoolean(ParkingWorker.KEY_RUNNING, false)
            .apply()

        ParkingWorker.cancelAll(this)
        Toast.makeText(this, R.string.toast_stopped, Toast.LENGTH_SHORT).show()
        Log.d(TAG, "Parking loop stopped")
    }

    companion object {
        private const val TAG = "AminParking"
    }
}
