package com.sightline.app.output

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

private const val TAG = "VoiceMgr"

class VoiceManager(private val context: Context) {

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val handler = Handler(Looper.getMainLooper())

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening

    private val _lastTranscript = MutableStateFlow("")
    val lastTranscript: StateFlow<String> = _lastTranscript

    /** True once TTS and STT are both initialized. Lets callers wait for warm-up before re-arming the mic. */
    val isVoiceReady: Boolean get() = ttsReady && speechRecognizer != null

    private var onResult: ((String) -> Unit)? = null
    private var retryCount = 0
    private var speechRecognizer: SpeechRecognizer? = null

    /**
     * True while the app wants STT to be active. Guards against double-starts
     * (which OPPO rejects with RECOGNIZER_BUSY) and lets stale retries die as
     * soon as the app leaves the screen.
     */
    @Volatile private var sttActive = false

    /** Currently scheduled auto-restart, so it can be cancelled on stop. */
    private var pendingRetry: Runnable? = null

    // --- TTS ---

    fun initTts(onReady: () -> Unit = {}) {
        Log.i(TAG, "initTts: start")
        try {
            tts = TextToSpeech(context) { status ->
                Log.i(TAG, "TTS status: $status")
                if (status == TextToSpeech.SUCCESS) {
                    tts?.let { engine ->
                        engine.language = Locale.getDefault()
                        engine.setSpeechRate(1.15f)
                        engine.setPitch(1.0f)
                        ttsReady = true
                        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                            override fun onStart(id: String?) {
                                Log.i(TAG, "TTS onStart: $id")
                                _isSpeaking.value = true
                            }
                            override fun onDone(id: String?) {
                                Log.i(TAG, "TTS onDone: $id")
                                _isSpeaking.value = false
                            }
                            @Deprecated("Deprecated") override fun onError(id: String?) {
                                Log.e(TAG, "TTS onError: $id")
                                _isSpeaking.value = false
                            }
                        })
                        Log.i(TAG, "TTS init OK")
                        onReady()
                    }
                } else {
                    Log.e(TAG, "TTS FAILED: $status")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "TTS init crashed", e)
        }
    }

    fun speak(text: String, utteranceId: String? = null) {
        if (!ttsReady || text.isBlank()) {
            Log.w(TAG, "speak SKIP: ready=$ttsReady blank=${text.isBlank()}")
            return
        }
        val id = utteranceId ?: "spk_${System.currentTimeMillis()}"
        Log.i(TAG, "speak: '$text' id=$id")
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    fun stopSpeaking() {
        Log.i(TAG, "stopSpeaking")
        tts?.stop()
        _isSpeaking.value = false
    }

    // --- STT ---

    fun initStt(resultCallback: (String) -> Unit) {
        Log.i(TAG, "initStt: start")
        onResult = resultCallback
        try {
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                Log.w(TAG, "STT not available on device")
                return
            }
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    Log.i(TAG, "STT ready for speech")
                    _isListening.value = true
                    retryCount = 0
                }
                override fun onBeginningOfSpeech() {
                    Log.i(TAG, "STT hearing you...")
                }
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    Log.i(TAG, "STT end of speech")
                    _isListening.value = false
                }
                override fun onError(error: Int) {
                    Log.e(TAG, "STT error: $error")
                    _isListening.value = false
                    // Error 13 = SERVICE_BUSY (TTS still speaking); 8 = BUSY (double-start).
                    // Retry while STT is still wanted, so a hiccup doesn't kill hands-free mode.
                    if ((error == 13 || error == 8) && retryCount < 3) {
                        scheduleRetry("STT error $error")
                    }
                }
                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val transcript = matches?.firstOrNull() ?: ""
                    Log.i(TAG, "STT result: '$transcript'")
                    _lastTranscript.value = transcript
                    if (transcript.isNotBlank()) {
                        onResult?.invoke(transcript)
                    } else {
                        // No speech detected — try again while STT is still wanted
                        Log.w(TAG, "STT empty result, retrying...")
                        scheduleRetry("empty result")
                    }
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            Log.i(TAG, "STT init OK")
        } catch (e: Exception) {
            Log.e(TAG, "STT init crashed", e)
            speechRecognizer = null
        }
    }

    /**
     * Stop TTS, wait for audio to release, then start STT.
     * This is the key fix for OPPO/TTS+STT audio conflict.
     */
    fun startListening() {
        if (sttActive) {
            Log.i(TAG, "startListening: already active, skipping")
            return
        }
        Log.i(TAG, "startListening: stopping TTS first")
        retryCount = 0
        sttActive = true
        stopSpeaking()
        // Wait longer for audio system to fully release
        handler.postDelayed({
            startListeningInternal()
        }, 1000)
    }

    private fun startListeningInternal() {
        if (!sttActive) {
            Log.w(TAG, "startListeningInternal: STT no longer wanted, skipping")
            return
        }
        if (speechRecognizer == null) {
            Log.w(TAG, "startListeningInternal: speechRecognizer is null")
            return
        }
        Log.i(TAG, "startListeningInternal: starting STT")
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        try {
            speechRecognizer?.startListening(intent)
            _isListening.value = true
            Log.i(TAG, "STT started OK")
        } catch (e: Exception) {
            Log.e(TAG, "STT start crashed", e)
            _isListening.value = false
            scheduleRetry("STT start crashed")
        }
    }

    private fun scheduleRetry(why: String) {
        if (!sttActive) {
            Log.i(TAG, "scheduleRetry: STT not wanted ($why), dropping retry")
            return
        }
        pendingRetry?.let { handler.removeCallbacks(it) }
        retryCount++
        val delayMs = if (retryCount < 3) 2000L else 4000L
        Log.i(TAG, "Retrying STT in ${delayMs}ms ($why, attempt $retryCount)")
        val runnable = Runnable { startListeningInternal() }
        pendingRetry = runnable
        handler.postDelayed(runnable, delayMs)
    }

    fun stopListening() {
        sttActive = false
        pendingRetry?.let { handler.removeCallbacks(it) }
        pendingRetry = null
        try { speechRecognizer?.stopListening() } catch (_: Exception) {}
        _isListening.value = false
    }

    /**
     * Process a text command directly (for text input fallback).
     */
    fun processTextCommand(text: String) {
        Log.i(TAG, "processTextCommand: '$text'")
        _lastTranscript.value = text
        onResult?.invoke(text)
    }

    fun destroy() {
        try {
            tts?.stop()
            tts?.shutdown()
            speechRecognizer?.destroy()
        } catch (_: Exception) {}
    }
}
