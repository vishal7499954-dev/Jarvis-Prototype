package com.jarvis.prototype

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean

class WakeWordService : Service(), TextToSpeech.OnInitListener {
    private val running = AtomicBoolean(false)
    private val speaking = AtomicBoolean(false)
    private var recorder: AudioRecord? = null
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var tts: TextToSpeech? = null
    private var worker: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var ttsReady = false
    @Volatile private var commandMode = false
    @Volatile private var commandStartedAt = 0L

    override fun onCreate() {
        super.onCreate()
        createChannel()
        tts = TextToSpeech(this, this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopListening()
            ACTION_START, null -> {
                // A sticky service can be recreated by Android with a null intent.
                // Re-enter foreground immediately before reopening the microphone.
                startForeground(NOTIFICATION_ID, notification("Restarting offline listening…"))
                startListening()
            }
        }
        return START_STICKY
    }

    private fun startListening() {
        if (!running.compareAndSet(false, true)) return
        worker = Thread {
            try {
                model = Model(copyModelFromAssets())
                switchToWakeWordMode()
                val minBuffer = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                )
                check(minBuffer > 0) { "Audio recorder is unavailable" }
                val audio = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuffer * 2, 8192)
                )
                check(audio.state == AudioRecord.STATE_INITIALIZED) { "Could not start microphone" }
                recorder = audio
                audio.startRecording()
                acquireWakeLock()
                updateNotification("Listening offline — say Jarvis")

                val buffer = ByteArray(4096)
                while (running.get()) {
                    if (speaking.get()) {
                        Thread.sleep(40)
                        continue
                    }
                    val count = audio.read(buffer, 0, buffer.size)
                    if (count <= 0) continue

                    val current = recognizer ?: continue
                    if (current.acceptWaveForm(buffer, count)) {
                        val result = JSONObject(current.result)
                        if (!commandMode) {
                            val wakeText = result.optString("text")
                            if (wakeText.contains("jarvis", ignoreCase = true)) {
                                addHistoryEntry("You", "Jarvis")
                                beginCommandListening()
                            }
                        } else {
                            val transcript = bestTranscript(result)
                            if (transcript.isNotBlank()) {
                                addHistoryEntry("You", transcript)
                                executeCommand(transcript)
                                switchToWakeWordMode()
                            }
                        }
                    }

                    if (commandMode && System.currentTimeMillis() - commandStartedAt > COMMAND_TIMEOUT_MS) {
                        addHistoryEntry("Jarvis", "I did not hear a command.")
                        updateNotification("No command heard. Listening for Jarvis again")
                        switchToWakeWordMode()
                    }
                }
            } catch (e: Exception) {
                updateNotification("Listener stopped: ${e.message ?: "error"}")
                running.set(false)
                stopSelf()
            } finally {
                releaseAudio()
            }
        }.apply { name = "JarvisOfflineWakeWord"; start() }
    }

    private fun bestTranscript(result: JSONObject): String {
        val alternatives = result.optJSONArray("alternatives")
        if (alternatives != null && alternatives.length() > 0) {
            var bestText = ""
            var bestConfidence = -1.0
            for (i in 0 until alternatives.length()) {
                val item = alternatives.optJSONObject(i) ?: continue
                val confidence = item.optDouble("confidence", 0.0)
                if (confidence > bestConfidence) {
                    bestConfidence = confidence
                    bestText = item.optString("text")
                }
            }
            if (bestText.isNotBlank()) return bestText.trim()
        }
        return result.optString("text").trim()
    }

    private fun executeCommand(transcript: String) {
        val command = transcript.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        when (command) {
            "open settings", "open the settings" -> {
                if (JarvisAccessibilityService.openSettings()) {
                    addHistoryEntry("Jarvis", "Opening Settings.")
                    speakResponse("Opening Settings.")
                    updateNotification("Opening phone Settings")
                } else {
                    addHistoryEntry("Jarvis", "Please enable Jarvis Accessibility Control in Android Settings first.")
                    speakResponse("Please enable Jarvis Accessibility Control first.")
                    updateNotification("Enable Jarvis Accessibility Control to open Settings by voice")
                }
            }
            command.startsWith("open ") -> {
                val response = AppCatalog.open(this, command.removePrefix("open ").trim())
                addHistoryEntry("Jarvis", response)
                speakResponse(response)
                updateNotification(response)
            }
            else -> {
                addHistoryEntry("Jarvis", "I heard: $transcript. That action is not available yet.")
                speakResponse("I heard $transcript. That command is not available yet.")
                updateNotification("Recognized: $transcript — action not added yet")
            }
        }
    }

    private fun beginCommandListening() {
        if (commandMode) return
        commandMode = true
        commandStartedAt = System.currentTimeMillis()
        recognizer?.close()
        recognizer = Recognizer(
            model ?: error("Offline model is not loaded"),
            SAMPLE_RATE.toFloat(),
            AppCatalog.commandGrammar(this)
        ).apply {
            setMaxAlternatives(5)
        }
        addHistoryEntry("Jarvis", "Hello sir")
        speakResponse("Hello sir")
        updateNotification("Hello sir. Listening for your command…")
    }

    private fun switchToWakeWordMode() {
        commandMode = false
        recognizer?.close()
        recognizer = Recognizer(
            model ?: error("Offline model is not loaded"),
            SAMPLE_RATE.toFloat(),
            """["jarvis", "[unk]"]"""
        )
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val languageResult = tts?.setLanguage(Locale.US)
            ttsReady = languageResult != TextToSpeech.LANG_MISSING_DATA &&
                languageResult != TextToSpeech.LANG_NOT_SUPPORTED
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) = Unit
                override fun onDone(id: String?) {
                    speaking.set(false)
                }
                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) { speaking.set(false) }
            })
        }
    }

    private fun speakResponse(message: String) {
        speaking.set(true)
        android.os.Handler(mainLooper).post {
            if (ttsReady && tts != null) {
                val result = tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "jarvis-response-${System.currentTimeMillis()}")
                if (result == TextToSpeech.ERROR) speaking.set(false)
            } else {
                speaking.set(false)
            }
        }
    }

    private fun copyModelFromAssets(): String {
        val target = File(filesDir, "vosk-model")
        if (File(target, "am/final.mdl").exists() && File(target, "graph").exists()) {
            return target.absolutePath
        }
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

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "JarvisPrototype:Mic").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseAudio() {
        try { recorder?.stop() } catch (_: Exception) { }
        recorder?.release()
        recorder = null
        recognizer?.close()
        recognizer = null
        model?.close()
        model = null
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock = null
    }

    private fun stopListening() {
        running.set(false)
        worker?.interrupt()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun addHistoryEntry(speaker: String, message: String) {
        val prefs = getSharedPreferences(HISTORY_PREFS, MODE_PRIVATE)
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val previous = prefs.getString(HISTORY_KEY, "").orEmpty()
        val entry = "[$time] $speaker: $message"
        val combined = if (previous.isBlank()) entry else "$previous\n$entry"
        val lines = combined.lines().takeLast(60).joinToString("\n")
        prefs.edit().putString(HISTORY_KEY, lines).apply()
        sendBroadcast(Intent(ACTION_HISTORY_UPDATE).setPackage(packageName))
    }

    private fun updateNotification(message: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, notification(message))
    }

    private fun notification(message: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Jarvis Prototype")
            .setContentText(message)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Jarvis background listening", NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        running.set(false)
        worker?.interrupt()
        releaseAudio()
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.jarvis.prototype.START"
        const val ACTION_STOP = "com.jarvis.prototype.STOP"
        const val ACTION_HISTORY_UPDATE = "com.jarvis.prototype.HISTORY_UPDATE"
        const val HISTORY_PREFS = "jarvis_conversation_history"
        const val HISTORY_KEY = "entries"
        private const val CHANNEL_ID = "jarvis_listener"
        private const val NOTIFICATION_ID = 41
        private const val SAMPLE_RATE = 16000
        private const val COMMAND_TIMEOUT_MS = 15000L
        private const val GREETING_ID = "jarvis-greeting"
        private const val COMMAND_GRAMMAR = """["open settings","open the settings","open camera","open the camera","open whatsapp","open youtube","open chrome","open gallery","open photos","open calculator","open phone","open messages","open contacts","open clock","open play store","open bluetooth settings","open wi-fi settings","go back","go home","scroll down","scroll up","turn on wi-fi","turn off wi-fi","turn on bluetooth","turn off bluetooth","volume up","volume down","take a screenshot","what time is it","stop listening","[unk]"]"""
    }
}
