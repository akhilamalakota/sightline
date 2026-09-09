package com.sightline.app.ui

import android.app.Application
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sightline.app.*
import com.sightline.app.camera.FrameConverter
import com.sightline.app.detection.ObjectDetector
import com.sightline.app.goal.GoalParser
import com.sightline.app.memory.MemoryStore
import com.sightline.app.output.ResponseBuilder
import com.sightline.app.output.VoiceManager
import com.sightline.app.spatial.SpatialEngine
import com.sightline.app.telemetry.TelemetryClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val TAG = "SightlineVM"

class SightlineViewModel(application: Application) : AndroidViewModel(application) {

    // --- Subsystems ---
    private val detector = ObjectDetector(application)
    private val spatial = SpatialEngine()
    private val goalParser = GoalParser()
    private val responseBuilder = ResponseBuilder()
    val voice = VoiceManager(application)
    private val memory = MemoryStore(application)
    private val telemetry = TelemetryClient()

    // --- UI State ---
    data class UiState(
        val phase: GoalMode = GoalMode.IDLE,
        val currentGoal: UserGoal? = null,
        val lastResponse: String = "",
        val isListening: Boolean = false,
        val isSpeaking: Boolean = false,
        val detectedObjects: List<DetectedObject> = emptyList(),
        val targetLock: DetectedObject? = null,
        val pathStatus: PathStatus = PathStatus.UNKNOWN,
        val showDebug: Boolean = false,
        val statusMessage: String = "What do you need?",
        val objectCount: Int = 0,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            voice.isListening.collect { listening ->
                _uiState.value = _uiState.value.copy(isListening = listening)
            }
        }
        viewModelScope.launch {
            voice.isSpeaking.collect { speaking ->
                _uiState.value = _uiState.value.copy(isSpeaking = speaking)
            }
        }
    }

    // --- Camera Frame Processing ---
    private var lastFrameTimeMs = 0L
    private val frameIntervalMs = 200L

    fun onCameraFrame(imageProxy: androidx.camera.core.ImageProxy) {
        val now = System.currentTimeMillis()
        if (now - lastFrameTimeMs < frameIntervalMs) {
            imageProxy.close()
            return
        }
        lastFrameTimeMs = now

        val bitmap = FrameConverter.toBitmap(imageProxy)
        imageProxy.close()

        if (bitmap == null) return

        try {
            val detections = detector.detect(bitmap)
            bitmap.recycle()

            val targetLabel = _uiState.value.currentGoal?.target
            val world = spatial.buildWorldModel(detections, targetLabel)

            _uiState.value = _uiState.value.copy(
                detectedObjects = world.objects,
                objectCount = world.objects.size,
                targetLock = world.targetLock,
                pathStatus = world.pathStatus,
            )

            // Process active goal
            val state = _uiState.value
            if (state.phase != GoalMode.IDLE && state.currentGoal != null) {
                processActiveGoal(state.currentGoal, world)
            }

            // Telemetry
            sendTelemetry(state, world)
        } catch (e: Exception) {
            Log.e(TAG, "Frame processing error", e)
        }
    }

    private fun processActiveGoal(goal: UserGoal, world: SpatialWorldModel) {
        val response = when (goal.mode) {
            GoalMode.FIND -> {
                val matches = world.findAllMatches(goal.target)
                if (matches.isEmpty()) {
                    responseBuilder.buildNoDetectionResponse()
                } else {
                    responseBuilder.buildFindResponse(goal, world)
                }
            }
            GoalMode.GUIDE -> {
                val arrived = world.targetLock != null &&
                        world.targetLock.distanceZone == DistanceZone.NEAR &&
                        world.targetLock.direction == Direction.CENTER
                responseBuilder.buildGuideResponse(goal, world, arrived)
            }
            GoalMode.UNDERSTAND -> responseBuilder.buildUnderstandResponse(world)
            GoalMode.REMEMBER -> responseBuilder.buildNoDetectionResponse()
            GoalMode.IDLE -> return
        }

        speakAndDisplay(response)
    }

    // --- Init ---

    suspend fun initDetectorSync(): Boolean {
        return try {
            val success = withContext(Dispatchers.IO) { detector.init() }
            Log.i(TAG, "Detector init: $success")
            withContext(Dispatchers.Main) {
                Toast.makeText(getApplication(), "Detector: $success", Toast.LENGTH_SHORT).show()
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "Detector init CRASHED", e)
            false
        }
    }

    fun initVoice() {
        Log.i(TAG, "initVoice called")
        try {
            voice.initStt { transcript ->
                Log.i(TAG, "Voice transcript received: '$transcript'")
                handleVoiceInput(transcript)
            }
            voice.initTts {
                Log.i(TAG, "TTS ready, speaking greeting")
                voice.speak("Sightline ready. Tap the microphone, then say what you need. Like, find a chair.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "initVoice CRASHED", e)
        }
    }

    private fun handleVoiceInput(transcript: String) {
        Log.i(TAG, "handleVoiceInput: '$transcript'")
        if (transcript.isBlank()) {
            Log.w(TAG, "Empty transcript received")
            return
        }
        val goal = goalParser.parse(transcript)

        if (goal == null) {
            Log.w(TAG, "Could not parse goal from: '$transcript'")
            speakAndDisplay(SpokenResponse(
                text = "I didn't understand. Try: find a chair, or, what's around me."
            ))
            return
        }

        Log.i(TAG, "Parsed goal: mode=${goal.mode}, target=${goal.target}")

        _uiState.value = _uiState.value.copy(
            currentGoal = goal,
            phase = goal.mode,
            statusMessage = when (goal.mode) {
                GoalMode.FIND -> "FINDING: ${goal.target.uppercase()}"
                GoalMode.GUIDE -> "GUIDING: ${goal.target.uppercase()}"
                GoalMode.UNDERSTAND -> "SCANNING"
                GoalMode.REMEMBER -> "REMEMBERING: ${goal.target.uppercase()}"
                GoalMode.IDLE -> "What do you need?"
            }
        )

        if (goal.mode == GoalMode.REMEMBER) {
            handleRememberGoal(goal)
            return
        }

        val confirmText = when (goal.mode) {
            GoalMode.FIND -> "Looking for ${goal.target}."
            GoalMode.GUIDE -> "Guiding you to ${goal.target}."
            GoalMode.UNDERSTAND -> "Scanning your surroundings."
            else -> ""
        }
        if (confirmText.isNotEmpty()) {
            speakAndDisplay(SpokenResponse(confirmText))
        }
    }

    private fun handleRememberGoal(goal: UserGoal) {
        viewModelScope.launch(Dispatchers.IO) {
            val detections = _uiState.value.detectedObjects
            val isRecall = goal.rawUtterance.lowercase().let {
                it.contains("where") || it.contains("recall") || it.contains("did you see")
            }

            if (isRecall) {
                val entry = memory.recallObject(goal.target)
                if (entry != null) {
                    speakAndDisplay(responseBuilder.buildRecallResponse(entry))
                } else {
                    speakAndDisplay(SpokenResponse("I don't remember seeing ${goal.target}."))
                    _uiState.value = _uiState.value.copy(
                        currentGoal = goal.copy(mode = GoalMode.FIND),
                        phase = GoalMode.FIND,
                    )
                }
            } else {
                val entry = memory.rememberObject(goal.target, detections)
                if (entry != null) {
                    speakAndDisplay(responseBuilder.buildRememberStoredResponse(goal.target))
                } else {
                    speakAndDisplay(SpokenResponse("I can't see ${goal.target} right now."))
                }
                returnToIdle()
            }
        }
    }

    // --- Actions ---

    fun startListening() {
        Log.i(TAG, "startListening called")
        voice.startListening()
        _uiState.value = _uiState.value.copy(statusMessage = "Listening…")
    }

    /**
     * Process a typed text command directly — bypasses STT.
     */
    fun processTextCommand(text: String) {
        Log.i(TAG, "processTextCommand: '$text'")
        handleVoiceInput(text)
    }

    fun stopListening() {
        voice.stopListening()
    }

    fun returnToIdle() {
        _uiState.value = _uiState.value.copy(
            phase = GoalMode.IDLE,
            currentGoal = null,
            targetLock = null,
            pathStatus = PathStatus.UNKNOWN,
            statusMessage = "What do you need?",
        )
        voice.stopSpeaking()
        voice.stopListening()
    }

    fun toggleDebug() {
        _uiState.value = _uiState.value.copy(showDebug = !_uiState.value.showDebug)
    }

    // --- Helpers ---

    private var lastSpokenText = ""
    private var lastSpokenTimeMs = 0L

    private fun speakAndDisplay(response: SpokenResponse) {
        val now = System.currentTimeMillis()
        // Don't repeat the same response within 3 seconds
        if (response.text == lastSpokenText && now - lastSpokenTimeMs < 3000) {
            _uiState.value = _uiState.value.copy(lastResponse = response.text)
            return
        }
        Log.i(TAG, "speakAndDisplay: '${response.text}'")
        lastSpokenText = response.text
        lastSpokenTimeMs = now
        _uiState.value = _uiState.value.copy(lastResponse = response.text)
        voice.speak(response.text)
        if (response.isHapticAlert) triggerHaptic()
    }

    private fun triggerHaptic() {
        try {
            @Suppress("DEPRECATION")
            val v = getApplication<Application>()
                .getSystemService(android.content.Context.VIBRATOR_SERVICE) as android.os.Vibrator
            v.vibrate(150)
        } catch (_: Exception) { }
    }

    private fun sendTelemetry(state: UiState, world: SpatialWorldModel) {
        try {
            val objectsJson = world.objects.map { obj ->
                JSONObject().apply {
                    put("label", obj.label)
                    put("x", (obj.bbox[0] + obj.bbox[2]) / 2.0)
                    put("y", (obj.bbox[1] + obj.bbox[3]) / 2.0)
                    put("confidence", obj.confidence.toDouble())
                    put("direction", obj.direction.name)
                    put("distance", obj.distanceZone.name)
                }
            }

            val payload = telemetry.buildPayload(
                goal = state.phase.name,
                targetLabel = state.currentGoal?.target ?: "",
                confidence = (state.targetLock?.confidence ?: 0f).toDouble(),
                pathStatus = state.pathStatus.name,
                objects = objectsJson,
                lastResponse = state.lastResponse,
            )
            telemetry.send(payload)
        } catch (e: Exception) {
            Log.w(TAG, "Telemetry send failed", e)
        }
    }

    fun connectTelemetry() {
        try {
            telemetry.connect()
        } catch (e: Exception) {
            Log.w(TAG, "Telemetry connect failed", e)
        }
    }

    override fun onCleared() {
        super.onCleared()
        voice.destroy()
        detector.close()
        telemetry.disconnect()
    }
}
