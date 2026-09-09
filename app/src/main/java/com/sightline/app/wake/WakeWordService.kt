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
import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineManager
import androidx.core.content.ContextCompat
import com.sightline.app.MainActivity
import com.sightline.app.R
import java.io.IOException

/**
 * Always-on "Hey Sightline" listener.
 *
 * Runs as a microphone-typed foreground service. Porcupine (on-device wake
 * word engine, free Hobby tier) captures the mic and fires only on the wake
 * word. Capture is enabled when the app is NOT in the foreground (MainActivity
 * sends ACTION_START_CAPTURE / ACTION_STOP_CAPTURE) so the in-app speech
 * recognizer keeps exclusive mic access while the user is talking to the app.
 */
class WakeWordService : Service() {

    companion object {
        private const val TAG = "WakeWordSvc"

        private const val CHANNEL_ID = "sightline_wake"
        private const val NOTIFICATION_ID = 42

        private const val ACTION_START_CAPTURE = "com.sightline.freebuff.wake.START"
        private const val ACTION_STOP_CAPTURE = "com.sightline.freebuff.wake.STOP"

        /**
         * Access key from https://console.picovoice.ai (free Hobby tier).
         * Get one, paste it here, rebuild.
         */
        private const val ACCESS_KEY = ""

        /**
         * Custom wake word trained for "Hey Sightline" at the Picovoice
         * console. Download the Android .ppn into app/src/main/assets/, or
         * (temporary) let the service fall back to a built-in keyword.
         */
        private const val WAKE_WORD_PPN = "hey-sightline_en_android_v3_0_0.ppn"

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

    private var porcupineManager: PorcupineManager? = null
    private var capturing = false
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
        porcupineManager?.delete()
        porcupineManager = null
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
        val manager = porcupineManager ?: buildPorcupine()
        if (manager == null) {
            Log.w(TAG, "Porcupine unavailable - wake word disabled")
            return
        }
        try {
            manager.start()
            capturing = true
            Log.i(TAG, "Wake word capture STARTED")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start wake word capture", e)
        }
    }

    private fun stopWakeCapture() {
        if (!capturing) return
        try {
            porcupineManager?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Stop capture error", e)
        }
        capturing = false
        Log.i(TAG, "Wake word capture STOPPED")
    }

    private fun buildPorcupine(): PorcupineManager? {
        if (ACCESS_KEY.isBlank()) {
            Log.e(TAG, "PICOVOICE ACCESS KEY MISSING - set ACCESS_KEY in WakeWordService.kt " +
                "(free at console.picovoice.ai). Wake word disabled.")
            return null
        }
        return try {
            val builder = PorcupineManager.Builder().setAccessKey(ACCESS_KEY)
            if (hasAsset(WAKE_WORD_PPN)) {
                builder.setKeywordPaths(arrayOf(WAKE_WORD_PPN))
                Log.i(TAG, "Using custom wake word: $WAKE_WORD_PPN")
            } else {
                builder.setKeywords(arrayOf(Porcupine.BuiltInKeyword.PICOVOICE))
                Log.w(TAG, "Custom wake word '$WAKE_WORD_PPN' not in assets - " +
                    "falling back to built-in wake word 'Picovoice'")
            }
            builder.build(this) { keywordIndex ->
                Log.i(TAG, "WAKE WORD DETECTED (index=$keywordIndex)")
                onWakeWord()
            }.also { Log.i(TAG, "PorcupineManager built OK") }
        } catch (e: Exception) {
            Log.e(TAG, "Porcupine init failed", e)
            null
        }
    }

    private fun hasAsset(name: String): Boolean {
        return try {
            assets.open(name).close()
            true
        } catch (e: IOException) {
            false
        }
    }

    // --- Wake behaviour: buzz, beep, open the app ---

    private fun onWakeWord() {
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