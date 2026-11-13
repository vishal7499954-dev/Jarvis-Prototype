package com.jarvis.prototype

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : ComponentActivity() {
    private lateinit var status: TextView
    private lateinit var sampleCountText: TextView
    private lateinit var historyText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var sampleButton: Button
    private lateinit var historyScroll: ScrollView

    @Volatile private var sampleRecording = false
    private val sampleStop = AtomicBoolean(false)
    private var sampleThread: Thread? = null
    private var isListening = false
    private val sampleDirectory by lazy { File(filesDir, "voice_samples").apply { mkdirs() } }

    private val historyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == WakeWordService.ACTION_HISTORY_UPDATE) {
                refreshHistory()
            }
        }
    }

    private val requestMicPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startListening()
            else Toast.makeText(this, "Microphone permission is required.", Toast.LENGTH_LONG).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(28), dp(22), dp(18))
        }
        root.addView(TextView(this).apply {
            text = "JARVIS PROTOTYPE"
            textSize = 25f
        })
        status = TextView(this).apply {
            text = "Ready. Start listening or record a verified Jarvis sample."
            textSize = 14f
            setPadding(0, dp(10), 0, dp(8))
        }
        root.addView(status)

        startButton = Button(this).apply { text = "Start offline listening" }
        stopButton = Button(this).apply { text = "Stop listening"; isEnabled = false }
        sampleButton = Button(this).apply { text = "Start voice sample (1/20)" }
        root.addView(startButton)
        root.addView(stopButton)
        root.addView(TextView(this).apply {
            text = "WAKE-WORD VOICE SAMPLES"
            textSize = 13f
            setPadding(0, dp(10), 0, 0)
        })
        sampleCountText = TextView(this).apply {
            textSize = 14f
            setPadding(0, dp(4), 0, dp(4))
        }
        root.addView(sampleCountText)
        root.addView(sampleButton)
        root.addView(TextView(this).apply {
            text = "Tap Start, say “Jarvis” once, and wait. The offline recognizer will verify the word, stop automatically, and save the sample. Repeat until 20/20."
            textSize = 12f
            setPadding(0, dp(2), 0, dp(8))
        })

        root.addView(TextView(this).apply {
            text = "CONVERSATION HISTORY"
            textSize = 14f
            setPadding(0, dp(8), 0, dp(6))
        })
        historyText = TextView(this).apply {
            textSize = 14f
            setPadding(dp(10), dp(8), dp(10), dp(8))
            text = "No conversations yet. Start listening and speak to Jarvis."
        }
        historyScroll = ScrollView(this).apply {
            isFillViewport = true
            addView(historyText)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFFF1F3F5.toInt())
                cornerRadius = dp(10).toFloat()
            }
        }
        root.addView(historyScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(190)
        ))
        setContentView(root)

        refreshSampleCount()
        refreshHistory()
        startButton.setOnClickListener { requestMicAndStart() }
        stopButton.setOnClickListener { stopListening() }
        sampleButton.setOnClickListener { startVerifiedSample() }
    }

    override fun onStart() {
        super.onStart()
        registerReceiver(historyReceiver, IntentFilter(WakeWordService.ACTION_HISTORY_UPDATE))
        refreshHistory()
    }

    override fun onStop() {
        try { unregisterReceiver(historyReceiver) } catch (_: Exception) { }
        super.onStop()
    }

    private fun requestMicAndStart() {
        if (sampleRecording) {
            Toast.makeText(this, "Finish the voice sample first.", Toast.LENGTH_SHORT).show()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
        } else startListening()
    }

    private fun startListening() {
        if (sampleRecording) return
        val intent = Intent(this, WakeWordService::class.java).apply { action = WakeWordService.ACTION_START }
        ContextCompat.startForegroundService(this, intent)
        isListening = true
        status.text = "Listening offline. Say Jarvis, wait for “Hello sir”, then say your command."
        startButton.isEnabled = false
        stopButton.isEnabled = true
        sampleButton.isEnabled = false
    }

    private fun stopListening() {
        startService(Intent(this, WakeWordService::class.java).apply { action = WakeWordService.ACTION_STOP })
        isListening = false
        status.text = "Listening stopped."
        startButton.isEnabled = true
        stopButton.isEnabled = false
        sampleButton.isEnabled = true
    }

    private fun startVerifiedSample() {
        if (isListening || sampleRecording) {
            Toast.makeText(this, "Stop background listening before recording a sample.", Toast.LENGTH_SHORT).show()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        val current = savedSampleCount()
        if (current >= MAX_SAMPLES) {
            status.text = "All 20 verified Jarvis samples are saved."
            return
        }
        sampleRecording = true
        sampleStop.set(false)
        sampleButton.isEnabled = false
        status.text = "Sample ${current + 1}/20: listening for you to say “Jarvis”…"
        sampleThread = Thread({ captureAndVerifySample() }, "JarvisSampleCapture").apply { start() }
    }

    private fun captureAndVerifySample() {
        var audio: AudioRecord? = null
        var model: Model? = null
        var recognizer: Recognizer? = null
        val pcm = ByteArrayOutputStream()
        var verified = false
        var failure: String? = null
        try {
            val modelPath = copyModelFromAssets()
            model = Model(modelPath)
            recognizer = Recognizer(model, SAMPLE_RATE.toFloat())
            val minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            check(minBuffer > 0) { "Microphone is unavailable" }
            audio = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer * 2, 8192)
            )
            check(audio.state == AudioRecord.STATE_INITIALIZED) { "Could not open microphone" }
            audio.startRecording()
            val buffer = ByteArray(4096)
            val startedAt = System.currentTimeMillis()
            while (!sampleStop.get() && System.currentTimeMillis() - startedAt < SAMPLE_TIMEOUT_MS) {
                val count = audio.read(buffer, 0, buffer.size)
                if (count <= 0) continue
                pcm.write(buffer, 0, count)
                if (recognizer.acceptWaveForm(buffer, count)) {
                    val text = JSONObject(recognizer.result).optString("text")
                    if (containsJarvis(text)) {
                        verified = true
                        break
                    }
                } else {
                    val partial = JSONObject(recognizer.partialResult).optString("partial")
                    if (containsJarvis(partial)) {
                        // Wait for the recognizer to finalize the phrase so the full sample is captured.
                    }
                }
            }
            if (!verified && !sampleStop.get()) {
                val finalText = JSONObject(recognizer.finalResult).optString("text")
                verified = containsJarvis(finalText)
            }
            if (verified && pcm.size() > SAMPLE_RATE) {
                val out = File(sampleDirectory, "jarvis_verified_${System.currentTimeMillis()}.wav")
                writeWav(out, pcm.toByteArray())
            } else if (!sampleStop.get()) {
                failure = "Jarvis was not clearly recognized. Nothing was saved; try again."
            }
        } catch (e: Exception) {
            failure = e.message ?: "Could not record sample"
        } finally {
            try { audio?.stop() } catch (_: Exception) { }
            audio?.release()
            recognizer?.close()
            model?.close()
            runOnUiThread {
                sampleRecording = false
                sampleButton.isEnabled = true
                refreshSampleCount()
                status.text = when {
                    sampleStop.get() -> "Sample recording cancelled."
                    verified -> "Verified! Sample ${savedSampleCount()}/20 saved."
                    else -> failure ?: "Sample not saved. Please try again."
                }
                updateSampleButton()
            }
        }
    }

    private fun containsJarvis(text: String): Boolean =
        Regex("\\bjarvis\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)

    private fun writeWav(file: File, pcm: ByteArray) {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + pcm.size)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1)
        header.putShort(1)
        header.putInt(SAMPLE_RATE)
        header.putInt(SAMPLE_RATE * 2)
        header.putShort(2)
        header.putShort(16)
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(pcm.size)
        FileOutputStream(file).use { it.write(header.array()); it.write(pcm) }
    }

    private fun copyModelFromAssets(): String {
        val target = File(filesDir, "vosk-model")
        if (File(target, "am/final.mdl").exists() && File(target, "graph").exists()) return target.absolutePath
        target.deleteRecursively()
        target.mkdirs()
        copyAssetFolder("model", target)
        check(File(target, "am/final.mdl").exists()) { "Offline English model is missing" }
        return target.absolutePath
    }

    private fun copyAssetFolder(path: String, dest: File) {
        for (entry in assets.list(path) ?: emptyArray()) {
            val childPath = "$path/$entry"
            val child = File(dest, entry)
            val children = assets.list(childPath)
            if (children != null && children.isNotEmpty()) {
                child.mkdirs()
                copyAssetFolder(childPath, child)
            } else {
                assets.open(childPath).use { input ->
                    FileOutputStream(child).use { output -> input.copyTo(output) }
                }
            }
        }
    }

    private fun savedSampleCount(): Int =
        sampleDirectory.listFiles()?.count { it.isFile && it.name.endsWith(".wav") } ?: 0

    private fun refreshSampleCount() {
        val count = savedSampleCount()
        sampleCountText.text = "Verified samples saved: $count / 20"
        updateSampleButton()
    }

    private fun updateSampleButton() {
        val count = savedSampleCount()
        sampleButton.text = if (count >= MAX_SAMPLES) "20/20 samples completed"
            else "Start voice sample (${count + 1}/20)"
        sampleButton.isEnabled = !sampleRecording && count < MAX_SAMPLES && !isListening
    }

    private fun refreshHistory() {
        val prefs = getSharedPreferences(WakeWordService.HISTORY_PREFS, MODE_PRIVATE)
        val history = prefs.getString(WakeWordService.HISTORY_KEY, "").orEmpty()
        historyText.text = if (history.isBlank()) {
            "No conversations yet. Start listening and speak to Jarvis."
        } else history
        historyScroll.post { historyScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        sampleStop.set(true)
        sampleThread?.interrupt()
        super.onDestroy()
    }

    companion object {
        private const val MAX_SAMPLES = 20
        private const val SAMPLE_RATE = 16000
        private const val SAMPLE_TIMEOUT_MS = 10000L
    }
}
