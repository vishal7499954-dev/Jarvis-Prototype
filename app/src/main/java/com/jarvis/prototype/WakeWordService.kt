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
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
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

    override fun onCreate() {
        super.onCreate()
        createChannel()
        tts = TextToSpeech(this, this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopListening()
            ACTION_START -> {
                startForeground(NOTIFICATION_ID, notification("Loading offline voice model…"))
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
                recognizer = Recognizer(model, SAMPLE_RATE.toFloat(), """["jarvis", "[unk]"]""")
                val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                check(minBuffer > 0) { "Audio recorder is unavailable" }
                val audio = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer * 2, 8192))
                check(audio.state == AudioRecord.STATE_INITIALIZED) { "Could not start microphone" }
                recorder = audio
                audio.startRecording()
                acquireWakeLock()
                updateNotification("Listening offline — say Jarvis")
                val buffer = ByteArray(4096)
                while (running.get()) {
                    if (speaking.get()) { Thread.sleep(80); continue }
                    val count = audio.read(buffer, 0, buffer.size)
                    if (count > 0) {
                        val result = recognizer?.let { r -> if (r.acceptWaveForm(buffer, count)) r.result else "" } ?: ""
                        if (result.contains("jarvis", ignoreCase = true)) greet()
                    }
                }
            } catch (e: Exception) {
                updateNotification("Listener stopped: ${e.message ?: "error"}")
                running.set(false)
                stopSelf()
            } finally { releaseAudio() }
        }.apply { name = "JarvisOfflineWakeWord"; start() }
    }

    private fun greet() {
        if (!speaking.compareAndSet(false, true)) return
        android.os.Handler(mainLooper).post {
            if (ttsReady) tts?.speak("Hello sir", TextToSpeech.QUEUE_FLUSH, null, "jarvis-greeting")
            else speaking.set(false)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val languageResult = tts?.setLanguage(Locale.US)
            ttsReady = languageResult != TextToSpeech.LANG_MISSING_DATA &&
                languageResult != TextToSpeech.LANG_NOT_SUPPORTED
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) = Unit
                override fun onDone(id: String?) { speaking.set(false) }
                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) { speaking.set(false) }
            })
        }
    }

    private fun copyModelFromAssets(): String {
        val target = File(filesDir, "vosk-model")
        if (File(target, "am/final.mdl").exists()) return target.absolutePath
        target.deleteRecursively(); target.mkdirs()
        copyAssetFolder("model", target)
        check(File(target, "am/final.mdl").exists()) { "Offline model is missing" }
        return target.absolutePath
    }

    private fun copyAssetFolder(path: String, dest: File) {
        for (entry in assets.list(path) ?: emptyArray()) {
            val childPath = "$path/$entry"
            val child = File(dest, entry)
            val children = assets.list(childPath)
            if (children != null && children.isNotEmpty()) {
                child.mkdirs(); copyAssetFolder(childPath, child)
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
            setReferenceCounted(false); acquire()
        }
    }

    private fun releaseAudio() {
        try { recorder?.stop() } catch (_: Exception) { }
        recorder?.release(); recorder = null
        recognizer?.close(); recognizer = null
        model?.close(); model = null
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock = null
    }

    private fun stopListening() {
        running.set(false); worker?.interrupt()
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }

    private fun updateNotification(message: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification(message))
    }

    private fun notification(message: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Jarvis Prototype").setContentText(message)
            .setOngoing(true).setCategory(NotificationCompat.CATEGORY_SERVICE).build()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Jarvis background listening", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        running.set(false); worker?.interrupt(); releaseAudio()
        tts?.stop(); tts?.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.jarvis.prototype.START"
        const val ACTION_STOP = "com.jarvis.prototype.STOP"
        private const val CHANNEL_ID = "jarvis_listener"
        private const val NOTIFICATION_ID = 41
        private const val SAMPLE_RATE = 16000
    }
}
