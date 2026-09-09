package com.sightline.app.spatial

import com.sightline.app.ApproachState
import com.sightline.app.DetectedObject
import com.sightline.app.Direction
import com.sightline.app.DistanceZone
import com.sightline.app.PathStatus
import com.sightline.app.SpatialWorldModel
import com.sightline.app.TrafficLightColor
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Converts raw detections into a structured world model with:
 *
 *  - Temporal stabilization: per-track EMA smoothing, confirmation (2+ hits)
 *    and a sticky window (~1.6 s) so a momentary detection gap doesn't drop
 *    the object — this is what stops "slight movement" from flipping decisions.
 *  - Metric distance: known real-world object height × camera focal length
 *    over on-screen height → meters. Falls back to NEAR / MID / FAR zones
 *    when the object's height isn't known or the camera isn't calibrated.
 */
class SpatialEngine {

    // --- Camera calibration (set once after the camera binds) ---
    private var focalLengthPx: Float = 0f

    /** Call with the horizontal focal length in pixels of the analysis image. */
    fun setFocalLength(px: Float) {
        focalLengthPx = px
    }

    // --- Tracking state ---
    private data class Track(
        val label: String,
        var bbox: FloatArray,      // smoothed [left, top, right, bottom], normalised 0-1
        var confidence: Float,     // smoothed
        var framesSeen: Int,       // count of associated detections
        var framesSinceSeen: Int,  // missed frames since last association
        var confirmed: Boolean,    // true once seen enough to trust
        var heightHistory: MutableList<Float> = mutableListOf(),  // smoothed bbox heights, capped
        var trafficColor: TrafficLightColor? = null,              // latest classified color
    )

    private val tracks = mutableListOf<Track>()

    fun reset() {
        tracks.clear()
    }

    /**
     * Stabilize raw detections into a consistent per-track object list.
     * Multi-instance labels (e.g. two chairs) become two tracks.
     */
    private fun stabilize(detections: List<DetectedObject>, frameHeightPx: Int): List<DetectedObject> {
        val consumed = BooleanArray(detections.size)

        // 1) Associate existing tracks with same-label detections (nearest centre).
        for (track in tracks) {
            var bestIdx = -1
            var bestDist = Float.MAX_VALUE
            for (i in detections.indices) {
                if (consumed[i]) continue
                val d = detections[i]
                if (d.label != track.label) continue
                val dist = centerDistance(track.bbox, d.bbox)
                if (dist < bestDist) {
                    bestDist = dist
                    bestIdx = i
                }
            }
            if (bestIdx >= 0 && bestDist <= MAX_ASSOCIATION_DIST) {
                consumed[bestIdx] = true
                val d = detections[bestIdx]
                // EMA smoothing — damps frame-to-frame jitter.
                track.bbox = floatArrayOf(
                    track.bbox[0] + (d.bbox[0] - track.bbox[0]) * SMOOTH_ALPHA,
                    track.bbox[1] + (d.bbox[1] - track.bbox[1]) * SMOOTH_ALPHA,
                    track.bbox[2] + (d.bbox[2] - track.bbox[2]) * SMOOTH_ALPHA,
                    track.bbox[3] + (d.bbox[3] - track.bbox[3]) * SMOOTH_ALPHA,
                )
                track.confidence += (d.confidence - track.confidence) * SMOOTH_ALPHA
                track.framesSeen++
                track.framesSinceSeen = 0
                if (track.framesSeen >= CONFIRM_FRAMES) track.confirmed = true
                // Keep a bounded history of smoothed heights for approach detection.
                track.heightHistory.add(track.bbox[3] - track.bbox[1])
                if (track.heightHistory.size > APPROACH_HISTORY) {
                    track.heightHistory.removeAt(0)
                }
                if (d.trafficLightColor != null) track.trafficColor = d.trafficLightColor
            } else {
                track.framesSinceSeen++
            }
        }

        // 2) Create tracks for unmatched detections.
        for (i in detections.indices) {
            if (consumed[i]) continue
            val d = detections[i]
            if (tracks.size >= MAX_TRACKS) {
                // Evict the least trustworthy track.
                val victim = tracks.filter { !it.confirmed }.minByOrNull { it.framesSeen }
                    ?: tracks.minByOrNull { it.confidence }
                victim?.let { tracks.remove(it) }
                if (tracks.size >= MAX_TRACKS) continue
            }
            tracks.add(
                Track(
                    label = d.label,
                    bbox = d.bbox.copyOf(),
                    confidence = d.confidence,
                    framesSeen = 1,
                    framesSinceSeen = 0,
                    confirmed = false,
                )
            )
        }

        // 3) Emit confirmed tracks still inside the sticky window.
        val result = mutableListOf<DetectedObject>()
        for (track in tracks) {
            if (!track.confirmed) continue
            if (track.framesSinceSeen > STICKY_FRAMES) continue

            // Confidence decays while the track is unseen so stale tracks lose authority.
            val confidence = if (track.framesSinceSeen == 0) {
                track.confidence
            } else {
                track.confidence * max(0.35f, 1f - 0.15f * track.framesSinceSeen)
            }
            if (confidence < 0.15f) continue

            val left = track.bbox[0]
            val top = track.bbox[1]
            val right = track.bbox[2]
            val bottom = track.bbox[3]

            val centerX = (left + right) / 2f
            val direction = when {
                centerX < 0.33f -> Direction.LEFT
                centerX > 0.66f -> Direction.RIGHT
                else -> Direction.CENTER
            }

            val heightFraction = (bottom - top).coerceIn(0f, 1f)
            val distanceZone = when {
                heightFraction > 0.35f -> DistanceZone.NEAR
                heightFraction > 0.15f -> DistanceZone.MID
                else -> DistanceZone.FAR
            }

            result.add(
                DetectedObject(
                    label = track.label,
                    bbox = track.bbox,
                    confidence = confidence,
                    distanceMeters = estimateMeters(track.label, heightFraction, frameHeightPx),
                    distanceZone = distanceZone,
                    direction = direction,
                    approach = approachState(track),
                    trafficLightColor = track.trafficColor,
                )
            )
        }

        // 4) Prune dead tracks.
        tracks.removeAll { it.framesSinceSeen > STICKY_FRAMES }

        return result.sortedByDescending { it.confidence }
    }

    /**
     * Build a world model from raw detections (stabilised internally).
     */
    fun buildWorldModel(
        detections: List<DetectedObject>,
        targetLabel: String? = null,
        frameHeightPx: Int = 0,
    ): SpatialWorldModel {
        val stable = stabilize(detections, frameHeightPx)

        // Obstacles in the direct path (center third, near zone)
        val obstacles = stable.filter { obj ->
            obj.direction == Direction.CENTER && obj.distanceZone == DistanceZone.NEAR
        }

        val pathStatus = when {
            obstacles.isEmpty() -> PathStatus.CLEAR
            obstacles.any { it.label in OBSTACLE_LABELS } -> PathStatus.BLOCKED
            else -> PathStatus.CLEAR // non-obstacle objects don't block
        }

        // Target lock for GUIDE/FIND — closest object (meters-aware).
        val targetLock = targetLabel?.let { label ->
            stable
                .filter { it.label.lowercase().contains(label.lowercase()) }
                .minByOrNull { closenessScore(it) }
        }

        return SpatialWorldModel(
            timestampMs = System.currentTimeMillis(),
            objects = stable,
            targetLock = targetLock,
            obstaclesInPath = obstacles,
            pathStatus = pathStatus,
        )
    }

    /**
     * Score an object for "closeness" — lower is closer. Prefers metric
     * distance when available, else zone distance; then centre.
     */
    private fun closenessScore(obj: DetectedObject): Float {
        val meters = obj.distanceMeters ?: when (obj.distanceZone) {
            DistanceZone.NEAR -> 0.7f
            DistanceZone.MID -> 3.0f
            DistanceZone.FAR -> 7.0f
        }
        val dir = when (obj.direction) {
            Direction.CENTER -> 0f
            Direction.LEFT, Direction.RIGHT -> 0.5f
        }
        return meters + dir
    }

    /**
     * Estimate metric distance from the object's on-screen height:
     *   distance = knownHeight * focalPx / bboxHeightPx
     * Returns null when the label's height is unknown, the camera isn't
     * calibrated, or the bbox is too small to be reliable.
     */
    private fun estimateMeters(label: String, heightFraction: Float, frameHeightPx: Int): Float? {
        val knownHeight = KNOWN_HEIGHTS[label] ?: return null
        if (focalLengthPx <= 0f || frameHeightPx <= 0) return null
        val bboxPx = heightFraction * frameHeightPx
        if (bboxPx < 6f) return null
        return knownHeight * focalLengthPx / bboxPx
    }

    /** Distance between two bbox centres (normalised space). */
    private fun centerDistance(a: FloatArray, b: FloatArray): Float {
        val ax = (a[0] + a[2]) / 2f
        val ay = (a[1] + a[3]) / 2f
        val bx = (b[0] + b[2]) / 2f
        val by = (b[1] + b[3]) / 2f
        return abs(ax - bx) + abs(ay - by)
    }

    /**
     * Classify a track's approach state from its recent height trend.
     * Growing bboxes mean the object is getting closer to the camera.
     */
    private fun approachState(track: Track): ApproachState {
        val history = track.heightHistory
        if (history.size < APPROACH_MIN_SAMPLES) return ApproachState.STATIC
        if (track.framesSinceSeen != 0) return ApproachState.STATIC
        val deltaPerFrame = (history.last() - history.first()) / (history.size - 1)
        return when {
            deltaPerFrame > APPROACH_THRESHOLD -> ApproachState.APPROACHING
            deltaPerFrame < -APPROACH_THRESHOLD -> ApproachState.RECEDING
            else -> ApproachState.STATIC
        }
    }

    /**
     * Direction word for natural speech.
     */
    fun directionWord(d: Direction): String = when (d) {
        Direction.LEFT -> "slightly left"
        Direction.CENTER -> "straight ahead"
        Direction.RIGHT -> "slightly right"
    }

    /**
     * Distance word for natural speech (zone fallback).
     */
    fun distanceWord(z: DistanceZone): String = when (z) {
        DistanceZone.NEAR -> "right here"
        DistanceZone.MID -> "a few steps ahead"
        DistanceZone.FAR -> "across the room"
    }

    /**
     * Natural phrase for an estimated metric distance.
     */
    fun metersWord(m: Float): String = when {
        m < 1f -> "less than a meter away"
        m < 2f -> "about a meter ahead"
        else -> "about ${m.roundToInt()} meters ahead"
    }

    companion object {
        private const val SMOOTH_ALPHA = 0.5f
        private const val CONFIRM_FRAMES = 2
        private const val STICKY_FRAMES = 8 // ~1.6 s at 200 ms / frame
        private const val MAX_ASSOCIATION_DIST = 0.3f
        private const val MAX_TRACKS = 12
        private const val APPROACH_HISTORY = 8
        private const val APPROACH_MIN_SAMPLES = 4
        private const val APPROACH_THRESHOLD = 0.008f

        /** Labels that count as obstacles for path-blocking. */
        private val OBSTACLE_LABELS = setOf(
            "person", "chair", "bench", "couch", "bed",
            "dining table", "potted plant", "suitcase", "truck",
            "car", "motorcycle", "bicycle",
        )

        /**
         * Typical real-world heights (meters) for the COCO classes where a
         * single number is sensible. Used for monocular distance estimation.
         */
        private val KNOWN_HEIGHTS = mapOf(
            "person" to 1.70f,
            "bicycle" to 1.10f,
            "car" to 1.50f,
            "motorcycle" to 1.20f,
            "bus" to 3.20f,
            "truck" to 3.50f,
            "train" to 3.50f,
            "airplane" to 4.00f,
            "boat" to 1.50f,
            "traffic light" to 1.00f,
            "fire hydrant" to 0.75f,
            "stop sign" to 0.70f,
            "parking meter" to 1.10f,
            "bench" to 0.90f,
            "bird" to 0.15f,
            "cat" to 0.30f,
            "dog" to 0.55f,
            "horse" to 1.80f,
            "sheep" to 0.90f,
            "cow" to 1.60f,
            "elephant" to 3.20f,
            "bear" to 1.60f,
            "zebra" to 1.60f,
            "giraffe" to 5.00f,
            "backpack" to 0.50f,
            "umbrella" to 1.00f,
            "handbag" to 0.30f,
            "suitcase" to 0.65f,
            "sports ball" to 0.22f,
            "skateboard" to 0.20f,
            "surfboard" to 0.60f,
            "tennis racket" to 0.70f,
            "bottle" to 0.28f,
            "wine glass" to 0.22f,
            "cup" to 0.10f,
            "bowl" to 0.10f,
            "banana" to 0.25f,
            "apple" to 0.09f,
            "chair" to 0.95f,
            "couch" to 0.90f,
            "potted plant" to 1.00f,
            "bed" to 0.55f,
            "dining table" to 0.75f,
            "toilet" to 0.70f,
            "tv" to 0.65f,
            "laptop" to 0.30f,
            "mouse" to 0.05f,
            "remote" to 0.05f,
            "keyboard" to 0.05f,
            "cell phone" to 0.15f,
            "microwave" to 0.35f,
            "oven" to 0.90f,
            "toaster" to 0.20f,
            "sink" to 0.40f,
            "refrigerator" to 1.80f,
            "book" to 0.25f,
            "clock" to 0.30f,
            "vase" to 0.35f,
            "teddy bear" to 0.50f,
        )
    }
}