package com.jarvis.prototype

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private lateinit var status: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button

    private val requestMicPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startListening()
            else Toast.makeText(this, "Microphone permission is required.", Toast.LENGTH_LONG).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply {
            text = "Ready. Start listening. Jarvis will calibrate the microphone first."
            textSize = 17f
            setPadding(0, 24, 0, 24)
        }
        startButton = Button(this).apply { text = "Start and calibrate microphone" }
        stopButton = Button(this).apply { text = "Stop listening"; isEnabled = false }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
            addView(TextView(this@MainActivity).apply { text = "JARVIS PROTOTYPE"; textSize = 25f })
            addView(status)
            addView(startButton)
            addView(stopButton)
        }
        setContentView(layout)
        startButton.setOnClickListener { requestMicAndStart() }
        stopButton.setOnClickListener { stopListening() }
    }

    private fun requestMicAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
        } else startListening()
    }

    private fun startListening() {
        val intent = Intent(this, WakeWordService::class.java).apply { action = WakeWordService.ACTION_START }
        ContextCompat.startForegroundService(this, intent)
        status.text = "Calibrating microphone, then listening offline. Say Jarvis, then your command."
        startButton.isEnabled = false
        stopButton.isEnabled = true
    }

    private fun stopListening() {
        startService(Intent(this, WakeWordService::class.java).apply { action = WakeWordService.ACTION_STOP })
        status.text = "Stopped."
        startButton.isEnabled = true
        stopButton.isEnabled = false
    }
}
