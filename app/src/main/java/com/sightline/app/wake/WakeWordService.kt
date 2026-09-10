package com.sightline.app.wake

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.core.content.ContextCompat
import com.sightline.app.MainActivity
import com.sightline.app.R
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService

/**
 * Always-on "Hey Sightline" listener.
 *
 * Runs as a microphone-typed foreground service. Vosk (open-source offline
 * speech recognition, no API key, no account) captures the mic and fires only
 * on the wake phrase using a restricted grammar: ["hey sight line", ...].
 * Capture is enabled when the app is NOT in the foreground (MainActivity sends
 * ACTION_START_CAPTURE / ACTION_STOP_CAPTURE) so the in-app speech recognizer
 * keeps exclusive mic access while the user is talking to the app.
 *
 * 100% offline and free forever - no key, no subscription, no network.
 */
class WakeWordService : Service() {

    companion object {
        private const val TAG = "WakeWordSvc"

        private const val CHANNEL_ID = "sightline_wake"
        private const val NOTIFICATION_ID = 42

        private const val ACTION_START_CAPTURE = "com.sightline.freebuff.wake.START"
        private const val ACTION_STOP_CAPTURE = "com.sightline.freebuff.wake.STOP"

        /**
         * Offline model bundled in app/src/main/assets/model-en/
         * (vosk-model-small-en-us-0.15). Grammar mode restricts recognition to
         * the wake phrase only, which makes detection near-instant and very
         * resistant to false positives.
         */
        private const val MODEL_ASSET = "model-en"
        private const val MODEL_TARGET = "models"

        private const val SAMPLE_RATE = 16000.0f

        private const val GRAMMAR_WAKE =
            """["hey sight line", "hey sightline", "sight line", "sightline", "[unk]"]"""

        fun startCapture(context: Context) =
            try {
                context.startForegroundService(Intent(context, WakeWordService::class.java).setAction(ACTION_START_CAPTURE))
            } catch (e: Exception) {
                Log.e(TAG, "startCapture failed", e)
            }

        fun stopCapture(context: Context) =
            try {
                context.startForegroundService(Intent(context, WakeWordService::class.java).setAction(ACTION_STOP_CAPTURE))
            } catch (e: Exception) {
                Log.w(TAG, "stopCapture failed", e)
            }
    }

    private var model: Model? = null
    private var modelLoadPending = false
    private var startRequestedWhileLoading = false
    private var speechService: SpeechService? = null
    private var recognizer: Recognizer? = null
    private var capturing = false
    private var consumed = false
    private var toneGen: ToneGenerator? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate")
        createChannel()
        startForegroundCompat()
        toneGen = try {
            ToneGenerator(AudioManager.STREAM_SYSTEM, 80)
        } catch (e: Exception) {
            Log.w(TAG, "ToneGenerator unavailable", e)
            null
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_CAPTURE -> startWakeCapture()
            ACTION_STOP_CAPTURE -> stopWakeCapture()
            else -> {
                Log.i(TAG, "Started (no action). Capturing=${capturing}")
                if (Build.VERSION.SDK_INT >= 26) startForegroundCompat()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        stopWakeCapture()
        speechService?.shutdown()
        speechService = null
        try { recognizer?.close() } catch (_: Exception) {}
        recognizer = null
        try { model?.close() } catch (_: Exception) {}
        model = null
        try { toneGen?.release() } catch (_: Exception) {}
        toneGen = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // --- Wake word capture ---

    private fun startWakeCapture() {
        if (capturing) return
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "No RECORD_AUDIO permission yet - capture deferred")
            return
        }
        val m = model
        if (m == null) {
            Log.i(TAG, "Vosk model not loaded yet - queuing start")
            startRequestedWhileLoading = true
            loadModelIfNeeded()
            return
        }
        val svc = speechService
        if (svc != null && capturing) return
        try {
            val rec = Recognizer(m, SAMPLE_RATE, GRAMMAR_WAKE)
            recognizer = rec
            val ss = SpeechService(rec, SAMPLE_RATE)
            speechService = ss
            consumed = false
            ss.startListening(listener)
            capturing = true
            Log.i(TAG, "Wake word capture STARTED (grammar mode)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start wake word capture", e)
            stopWakeCapture()
        }
    }

    private fun stopWakeCapture() {
        capturing = false
        startRequestedWhileLoading = false
        if (speechService == null) return
        try {
            speechService?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Stop capture error", e)
        }
        try {
            speechService?.shutdown()
        } catch (e: Exception) {
            Log.w(TAG, "Shutdown capture error", e)
        }
        speechService = null
        try { recognizer?.close() } catch (_: Exception) {}
        recognizer = null
        Log.i(TAG, "Wake word capture STOPPED")
    }

    private fun loadModelIfNeeded() {
        if (model != null || modelLoadPending) return
        modelLoadPending = true
        Log.i(TAG, "Unpacking Vosk model from assets...")
        StorageService.unpack(
            this, MODEL_ASSET, MODEL_TARGET,
            { m ->
                modelLoadPending = false
                model = m
                Log.i(TAG, "Vosk model loaded OK")
                if (startRequestedWhileLoading) {
                    startRequestedWhileLoading = false
                    startWakeCapture()
                }
            },
            { e ->
                modelLoadPending = false
                Log.e(TAG, "Vosk model load FAILED: ${e.message}", e)
            }
        )
    }

    private val listener = object : RecognitionListener {
        override fun onPartialResult(hypothesis: String) {
            handleResult(hypothesis, isFinal = false)
        }

        override fun onResult(hypothesis: String) {
            handleResult(hypothesis, isFinal = true)
        }

        override fun onFinalResult(hypothesis: String) {
            handleResult(hypothesis, isFinal = true)
        }

        override fun onTimeout() {
            // No timeout mode used; nothing to do.
        }

        override fun onError(e: Exception) {
            Log.w(TAG, "Recognition error: ${e.message}")
        }
    }

    private fun handleResult(json: String, isFinal: Boolean) {
        if (!capturing || consumed) return
        val text = try {
            JSONObject(json).optString(if (isFinal) "text" else "partial", "")
        } catch (e: Exception) {
            ""
        }
        if (text.isEmpty()) return
        Log.i(TAG, "Vosk${if (isFinal) " final" else " partial"}: $text")
        if (isWakePhrase(text)) onWakeWord()
    }

    /**
     * Grammar mode guarantees results only ever contain wake-phrase tokens or
     * [unk], so a match here means the user actually said (part of) the wake
     * phrase - no open dictation is recognised.
     */
    private fun isWakePhrase(raw: String): Boolean {
        val t = raw.lowercase()
            .replace("[unk]", "")
            .trim()
            .split(Regex("\\s+"))
            .joinToString(" ")
        return t.contains("sightline") || t.contains("sight line")
    }

    // --- Wake behaviour: buzz, beep, open the app ---

    private fun onWakeWord() {
        if (consumed) return
        consumed = true
        Log.i(TAG, "WAKE WORD DETECTED")
        buzz()
        beep()
        stopWakeCapture()
        launchApp()
    }

    private fun buzz() {
        try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= 26) {
                vibrator.vibrate(VibrationEffect.createOneShot(400, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(400)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Vibrate failed", e)
        }
    }

    private fun beep() {
        try {
            toneGen?.startTone(ToneGenerator.TONE_PROP_BEEP2, 250)
        } catch (e: Exception) {
            Log.w(TAG, "Beep failed", e)
        }
    }

    private fun launchApp() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }
        try {
            startActivity(intent)
            Log.i(TAG, "Launched MainActivity")
        } catch (e: Exception) {
            Log.e(TAG, "Background activity launch blocked - tap the notification instead", e)
            val pending = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, buildNotification(pending))
        }
    }

    // --- Foreground notification ---

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Sightline wake word",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Listening for 'Hey Sightline'"
            setShowBadge(false)
        }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(contentIntent: PendingIntent? = null): Notification {
        val pending = contentIntent ?: PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Sightline")
            .setContentText("Listening for 'Hey Sightline'")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }

    private fun startForegroundCompat() {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
        }
    }
}