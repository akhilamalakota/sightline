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

    private var onResult: ((String) -> Unit)? = null
    private var retryCount = 0
    private var speechRecognizer: SpeechRecognizer? = null

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
                    // Error 13 = SERVICE_BUSY (TTS still speaking), retry after longer delay
                    if (error == 13 && retryCount < 3) {
                        retryCount++
                        Log.i(TAG, "Retrying STT in 2s (attempt $retryCount)...")
                        handler.postDelayed({ startListeningInternal() }, 2000)
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
                        // No speech detected — try again
                        Log.w(TAG, "STT empty result, retrying...")
                        handler.postDelayed({ startListeningInternal() }, 500)
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
        Log.i(TAG, "startListening: stopping TTS first")
        retryCount = 0
        stopSpeaking()
        // Wait longer for audio system to fully release
        handler.postDelayed({
            startListeningInternal()
        }, 1000)
    }

    private fun startListeningInternal() {
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
        }
    }

    fun stopListening() {
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
