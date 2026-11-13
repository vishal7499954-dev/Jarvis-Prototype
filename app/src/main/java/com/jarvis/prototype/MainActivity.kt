package com.jarvis.prototype

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import java.io.File

class MainActivity : ComponentActivity() {
    private lateinit var status: TextView
    private lateinit var sampleCountText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var recordSampleButton: Button
    private lateinit var finishSampleButton: Button

    private var voiceRecorder: MediaRecorder? = null
    private var currentSampleFile: File? = null
    private val sampleDirectory by lazy { File(filesDir, "voice_samples").apply { mkdirs() } }
    private var isListening = false

    private val requestMicPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startListening()
            else Toast.makeText(this, "Microphone permission is required.", Toast.LENGTH_LONG).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        status = TextView(this).apply {
            text = "Ready. Record 10–20 samples of yourself saying Jarvis, or start listening."
            textSize = 16f
            setPadding(0, 20, 0, 16)
        }
        sampleCountText = TextView(this).apply {
            textSize = 15f
            setPadding(0, 8, 0, 12)
        }
        startButton = Button(this).apply { text = "Start offline listening" }
        stopButton = Button(this).apply { text = "Stop listening"; isEnabled = false }
        recordSampleButton = Button(this).apply { text = "Record my voice saying Jarvis" }
        finishSampleButton = Button(this).apply { text = "Stop and save sample"; isEnabled = false }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 40, 32, 32)
            addView(TextView(this@MainActivity).apply {
                text = "JARVIS PROTOTYPE"
                textSize = 25f
            })
            addView(status)
            addView(sampleCountText)
            addView(recordSampleButton)
            addView(finishSampleButton)
            addView(TextView(this@MainActivity).apply {
                text = "Record one clear 'Jarvis' sample at a time. Aim for 10–20 samples. They stay on this phone and are saved for future wake-word training; recording them does not train Vosk automatically."
                textSize = 13f
                setPadding(0, 4, 0, 12)
            })
            addView(startButton)
            addView(stopButton)
        }
        setContentView(layout)
        refreshSampleCount()

        startButton.setOnClickListener { requestMicAndStart() }
        stopButton.setOnClickListener { stopListening() }
        recordSampleButton.setOnClickListener { startVoiceSample() }
        finishSampleButton.setOnClickListener { finishVoiceSample() }
    }

    private fun requestMicAndStart() {
        if (voiceRecorder != null) {
            Toast.makeText(this, "Finish saving the voice sample first.", Toast.LENGTH_SHORT).show()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
        } else startListening()
    }

    private fun startListening() {
        val intent = Intent(this, WakeWordService::class.java).apply { action = WakeWordService.ACTION_START }
        ContextCompat.startForegroundService(this, intent)
        isListening = true
        status.text = "Listening offline. Say Jarvis, wait for 'Hello sir', then say your command."
        startButton.isEnabled = false
        stopButton.isEnabled = true
        recordSampleButton.isEnabled = false
    }

    private fun stopListening() {
        startService(Intent(this, WakeWordService::class.java).apply { action = WakeWordService.ACTION_STOP })
        isListening = false
        status.text = "Listening stopped."
        startButton.isEnabled = true
        stopButton.isEnabled = false
        recordSampleButton.isEnabled = true
    }

    private fun startVoiceSample() {
        if (isListening) {
            Toast.makeText(this, "Stop listening before recording samples.", Toast.LENGTH_SHORT).show()
            return
        }
        val count = savedSampleCount()
        if (count >= MAX_SAMPLES) {
            Toast.makeText(this, "You already have 20 samples. That's enough for this collection.", Toast.LENGTH_LONG).show()
            return
        }

        val file = File(sampleDirectory, "jarvis_sample_${System.currentTimeMillis()}.m4a")
        try {
            val recorder = MediaRecorder()
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setOutputFile(file.absolutePath)
            recorder.prepare()
            recorder.start()
            voiceRecorder = recorder
            currentSampleFile = file
            status.text = "Recording sample ${count + 1}/20. Say 'Jarvis' once, clearly."
            recordSampleButton.isEnabled = false
            finishSampleButton.isEnabled = true
        } catch (e: Exception) {
            file.delete()
            releaseVoiceRecorder()
            status.text = "Could not record sample: ${e.message ?: "microphone error"}"
        }
    }

    private fun finishVoiceSample() {
        val recorder = voiceRecorder ?: return
        try {
            recorder.stop()
            currentSampleFile?.takeIf { it.exists() && it.length() > 1000L }
                ?: currentSampleFile?.delete()
            status.text = "Voice sample saved."
        } catch (_: RuntimeException) {
            currentSampleFile?.delete()
            status.text = "Sample was too short. Please record it again."
        } finally {
            releaseVoiceRecorder()
            recordSampleButton.isEnabled = true
            finishSampleButton.isEnabled = false
            refreshSampleCount()
        }
    }

    private fun releaseVoiceRecorder() {
        try { voiceRecorder?.reset() } catch (_: Exception) { }
        try { voiceRecorder?.release() } catch (_: Exception) { }
        voiceRecorder = null
        currentSampleFile = null
    }

    private fun savedSampleCount(): Int =
        sampleDirectory.listFiles()?.count { it.isFile && it.name.endsWith(".m4a") } ?: 0

    private fun refreshSampleCount() {
        sampleCountText.text = "Saved voice samples: ${savedSampleCount()} / 20"
    }

    override fun onDestroy() {
        if (voiceRecorder != null) {
            try { voiceRecorder?.stop() } catch (_: Exception) { }
            currentSampleFile?.delete()
            releaseVoiceRecorder()
        }
        super.onDestroy()
    }

    companion object {
        private const val MAX_SAMPLES = 20
    }
}
