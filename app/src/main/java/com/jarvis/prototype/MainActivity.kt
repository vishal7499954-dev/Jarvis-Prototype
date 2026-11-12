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
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private lateinit var status: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply {
            text = "Ready. Start listening, then say: Jarvis"
            textSize = 18f
            setPadding(0, 24, 0, 24)
        }
        startButton = Button(this).apply { text = "Start background listening" }
        stopButton = Button(this).apply { text = "Stop listening"; isEnabled = false }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
            addView(TextView(this@MainActivity).apply {
                text = "JARVIS PROTOTYPE"; textSize = 25f
            })
            addView(status); addView(startButton); addView(stopButton)
        }
        setContentView(layout)
        startButton.setOnClickListener { requestMicAndStart() }
        stopButton.setOnClickListener { stopListening() }
    }

    private fun requestMicAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
        } else startListening()
    }

    private fun startListening() {
        val intent = Intent(this, WakeWordService::class.java).apply {
            action = WakeWordService.ACTION_START
        }
        ContextCompat.startForegroundService(this, intent)
        status.text = "Listening offline. Say: Jarvis"
        startButton.isEnabled = false
        stopButton.isEnabled = true
    }

    private fun stopListening() {
        startService(Intent(this, WakeWordService::class.java).apply {
            action = WakeWordService.ACTION_STOP
        })
        status.text = "Stopped."
        startButton.isEnabled = true
        stopButton.isEnabled = false
    }

    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code == REQUEST_MIC && results.firstOrNull() == PackageManager.PERMISSION_GRANTED) startListening()
        else if (code == REQUEST_MIC) Toast.makeText(this, "Microphone permission is required.", Toast.LENGTH_LONG).show()
    }

    companion object { private const val REQUEST_MIC = 100 }
}
