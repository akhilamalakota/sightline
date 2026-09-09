package com.sightline.app.ui

import android.app.Application
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.sightline.app.*
import com.sightline.app.camera.FrameConverter
import com.sightline.app.detection.ObjectDetector
import com.sightline.app.detection.TrafficLightClassifier
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
import kotlin.math.abs

private const val TAG = "SightlineVM"

private const val ARRIVAL_METERS = 1.2f
private const val ARRIVAL_REPEAT_MS = 8_000L
private val ARRIVAL_MODES = setOf(GoalMode.FIND, GoalMode.GUIDE)
private const val APPROACH_REPEAT_MS = 10_000L
private const val LOCATE_HAPTIC_MIN_MS = 700L

class SightlineViewModel(application: Application) : AndroidViewModel(application) {

    // --- Subsystems ---
    private val detector = ObjectDetector(application)
    private val spatial = SpatialEngine()
    private val goalParser = GoalParser()
    private val responseBuilder = ResponseBuilder()
    val voice = VoiceManager(application)
    private val memory = MemoryStore(application)
    private val telemetry = TelemetryClient()

    // --- Distance / arrival state ---
    @Volatile private var pendingCalibration: Pair<Float, Float>? = null
    private var lastArrivalAnnounceMs = 0L
    private var lastSpokenMeterFloor = -1
    private var lastSpokenZone: DistanceZone? = null

    // --- Always-on radar state ---
    private val lastApproachAnnounceMs = HashMap<String, Long>()
    private var lastTrafficColor: TrafficLightColor? = null

    // --- OCR (READ mode) ---
    @Volatile private var ocrFired = false
    private val textRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    // --- Haptic locating ---
    private var lastLocateHapticMs = 0L

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
            pendingCalibration?.let { (focalMm, sensorWidthMm) ->
                pendingCalibration = null
                if (focalMm > 0f && sensorWidthMm > 0f && bitmap.width > 0) {
                    val focalPx = focalMm * (bitmap.width / sensorWidthMm)
                    spatial.setFocalLength(focalPx)
                    Log.i(TAG, "Camera calibrated: focal=${focalMm}mm sensorW=${sensorWidthMm}mm -> focalPx=$focalPx")
                }
            }

            val frameHeightPx = bitmap.height
            val detections = detector.detect(bitmap)

            val annotated = if (detections.any { it.label == "traffic light" }) {
                detections.map { d ->
                    if (d.label == "traffic light") {
                        d.copy(trafficLightColor = TrafficLightClassifier.classify(bitmap, d.bbox))
                    } else d
                }
            } else detections

            val targetLabel = _uiState.value.currentGoal?.target
            val world = spatial.buildWorldModel(annotated, targetLabel, frameHeightPx)

            _uiState.value = _uiState.value.copy(
                detectedObjects = world.objects,
                objectCount = world.objects.size,
                targetLock = world.targetLock,
                pathStatus = world.pathStatus,
            )

            val state = _uiState.value

            var bitmapOwnedByOcr = false
            if (state.phase == GoalMode.READ && !ocrFired) {
                ocrFired = true
                bitmapOwnedByOcr = true
                runOcr(bitmap)
            }
            if (!bitmapOwnedByOcr) bitmap.recycle()

            // Process active goal
            if (state.phase != GoalMode.IDLE && state.currentGoal != null) {
                processActiveGoal(state.currentGoal, world)
            }

            // Always-on radar (independent of any goal)
            checkApproachWarnings(world, state)
            checkTrafficLight(world)

            // Telemetry
            sendTelemetry(state, world)
        } catch (e: Exception) {
            Log.e(TAG, "Frame processing error", e)
        }
    }

    private fun processActiveGoal(goal: UserGoal, world: SpatialWorldModel) {
        val target = world.targetLock
        val dist = target?.distanceMeters
        val arrived = target != null &&
            ((dist != null && dist <= ARRIVAL_METERS) ||
                (dist == null && target.distanceZone == DistanceZone.NEAR)) &&
            target.direction == Direction.CENTER

        if (arrived && goal.mode in ARRIVAL_MODES &&
            System.currentTimeMillis() - lastArrivalAnnounceMs < ARRIVAL_REPEAT_MS
        ) {
            // Already announced arrival recently — keep the caption fresh, don't re-speak.
            _uiState.value = _uiState.value.copy(
                lastResponse = responseBuilder.buildArrivalResponse(target).text
            )
            return
        }

        val response = when (goal.mode) {
            GoalMode.FIND -> {
                val matches = world.findAllMatches(goal.target)
                when {
                    matches.isEmpty() -> responseBuilder.buildNoDetectionResponse()
                    arrived -> {
                        lastArrivalAnnounceMs = System.currentTimeMillis()
                        responseBuilder.buildArrivalResponse(target)
                    }
                    else -> responseBuilder.buildFindResponse(goal, world)
                }
            }
            GoalMode.GUIDE -> responseBuilder.buildGuideResponse(goal, world, arrived).also {
                if (arrived) lastArrivalAnnounceMs = System.currentTimeMillis()
            }
            GoalMode.UNDERSTAND -> responseBuilder.buildUnderstandResponse(world)
            GoalMode.REMEMBER -> responseBuilder.buildNoDetectionResponse()
            GoalMode.READ -> return
            GoalMode.IDLE -> return
        }

        speakAndDisplay(response)

        // While approaching (not yet arrived) keep the user updated on distance.
        if (goal.mode in ARRIVAL_MODES && !arrived) {
            speakApproachProgress(goal, target, dist)
            if (target != null) hapticLocate(target)
        }
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

        // Fresh goal → reset approach-progress trackers so the first meter
        // boundary is announced again.
        lastSpokenMeterFloor = -1
        lastSpokenZone = null
        lastArrivalAnnounceMs = 0L
        lastApproachAnnounceMs.clear()
        if (goal.mode == GoalMode.READ) ocrFired = false

        _uiState.value = _uiState.value.copy(
            currentGoal = goal,
            phase = goal.mode,
            statusMessage = when (goal.mode) {
                GoalMode.FIND -> "FINDING: ${goal.target.uppercase()}"
                GoalMode.GUIDE -> "GUIDING: ${goal.target.uppercase()}"
                GoalMode.UNDERSTAND -> "SCANNING"
                GoalMode.REMEMBER -> "REMEMBERING: ${goal.target.uppercase()}"
                GoalMode.READ -> "READING TEXT"
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
            GoalMode.READ -> "Reading what's in front of you."
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

    /**
     * Called by the UI once the camera binds: lens focal length (mm) and
     * sensor physical width (mm) from CameraCharacteristics. Converted to
     * focal-length-in-pixels on the first usable frame.
     */
    fun setCameraCalibration(focalMm: Float, sensorWidthMm: Float) {
        pendingCalibration = focalMm to sensorWidthMm
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

    /**
     * Always-on approach radar: announces when any non-target object starts
     * moving toward the user, throttled per label.
     */
    private fun checkApproachWarnings(world: SpatialWorldModel, state: UiState) {
        if (System.currentTimeMillis() - lastSpokenTimeMs < 2500) return
        val target = state.targetLock
        val candidate = world.objects
            .filter {
                it.approach == ApproachState.APPROACHING &&
                    it.label != "traffic light" &&
                    it !== target &&
                    (it.distanceZone != DistanceZone.FAR || (it.distanceMeters ?: 99f) < 4f)
            }
            .minByOrNull { it.distanceMeters ?: Float.MAX_VALUE }
            ?: return
        val now = System.currentTimeMillis()
        if (now - (lastApproachAnnounceMs[candidate.label] ?: 0L) < APPROACH_REPEAT_MS) return
        lastApproachAnnounceMs[candidate.label] = now
        speakAndDisplay(responseBuilder.buildApproachWarning(candidate))
    }

    /**
     * Traffic-light awareness: announces once each time the dominant light
     * color changes.
     */
    private fun checkTrafficLight(world: SpatialWorldModel) {
        val light = world.objects.firstOrNull {
            it.label == "traffic light" &&
                it.trafficLightColor != null &&
                it.trafficLightColor != TrafficLightColor.UNKNOWN
        } ?: return
        val color = light.trafficLightColor!!
        if (color == lastTrafficColor) return
        lastTrafficColor = color
        val response = responseBuilder.buildTrafficLightResponse(color)
        if (response.text.isNotEmpty()) speakAndDisplay(response)
    }

    /**
     * One-shot OCR of the current frame for READ mode. Owns the bitmap —
     * recycles it on the IO thread after recognition completes.
     */
    private fun runOcr(bitmap: android.graphics.Bitmap) {
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) {
                try {
                    val image = InputImage.fromBitmap(bitmap, 0)
                    val result = Tasks.await(textRecognizer.process(image))
                    bitmap.recycle()
                    result.text
                } catch (e: Exception) {
                    Log.e(TAG, "OCR failed", e)
                    bitmap.recycle()
                    ""
                }
            }
            speakAndDisplay(responseBuilder.buildOcrResponse(text))
            returnToIdle()
        }
    }

    /**
     * Haptic locating: short pulses that encode how centered and how close
     * the tracked target is. Stronger buzz = more aligned + closer.
     */
    private fun hapticLocate(target: DetectedObject) {
        val now = System.currentTimeMillis()
        if (now - lastLocateHapticMs < LOCATE_HAPTIC_MIN_MS) return
        val centerX = (target.bbox[0] + target.bbox[2]) / 2f
        val alignment = 1f - abs(centerX - 0.5f) * 2f
        if (alignment < 0.5f) return
        val closeness = target.distanceMeters?.let { (1f - it / 2.5f).coerceIn(0.1f, 1f) }
            ?: when (target.distanceZone) {
                DistanceZone.NEAR -> 0.9f
                DistanceZone.MID -> 0.5f
                DistanceZone.FAR -> 0.3f
            }
        lastLocateHapticMs = now
        val durationMs = (60L + 140L * closeness).toLong()
        try {
            val v = getApplication<Application>()
                .getSystemService(android.content.Context.VIBRATOR_SERVICE) as android.os.Vibrator
            v.vibrate(
                android.os.VibrationEffect.createOneShot(
                    durationMs,
                    android.os.VibrationEffect.DEFAULT_AMPLITUDE
                )
            )
        } catch (_: Exception) { }
    }

    /**
     * Re-speaks target direction/distance as the user approaches: on whole-meter
     * crossings when metric distance is available, else on zone changes.
     */
    private fun speakApproachProgress(goal: UserGoal, target: DetectedObject?, dist: Float?) {
        if (target == null) return
        if (System.currentTimeMillis() - lastSpokenTimeMs < 2500) return
        val name = target.label.replaceFirstChar { it.uppercase() }
        val text = if (dist != null) {
            val floor = dist.toInt()
            if (floor == lastSpokenMeterFloor && lastSpokenMeterFloor >= 0) return
            lastSpokenMeterFloor = floor
            lastSpokenZone = null
            "$name, ${spatial.metersWord(dist)}."
        } else {
            if (target.distanceZone == lastSpokenZone) return
            lastSpokenZone = target.distanceZone
            lastSpokenMeterFloor = -1
            "$name is ${spatial.distanceWord(target.distanceZone)}, " +
                "${spatial.directionWord(target.direction)}."
        }
        speakAndDisplay(SpokenResponse(text))
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
