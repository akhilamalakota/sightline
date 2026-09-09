package com.sightline.app

/**
 * Core data types for SIGHTLINE.
 * These are the shared vocabulary across every module.
 */

/** Where an object is horizontally in the camera frame. */
enum class Direction { LEFT, CENTER, RIGHT }

/** How far an object is — either from real depth or bbox heuristic. */
enum class DistanceZone { NEAR, MID, FAR }

/** What the system is currently trying to do. */
enum class GoalMode { IDLE, FIND, GUIDE, UNDERSTAND, REMEMBER }

/** Status of the path ahead. */
enum class PathStatus { CLEAR, BLOCKED, UNKNOWN }

/**
 * A single detected object from the vision pipeline.
 */
data class DetectedObject(
    val label: String,
    val bbox: FloatArray,       // [left, top, right, bottom] normalised 0-1
    val confidence: Float,
    val distanceMeters: Float?, // real metres if ARCore depth available, null otherwise
    val distanceZone: DistanceZone,
    val direction: Direction,
) {
    /** Human-readable position, e.g. "chair — mid — left" */
    fun describe(): String =
        "$label — ${distanceZone.name.lowercase()} — ${direction.name.lowercase()}"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DetectedObject) return false
        return label == other.label &&
                bbox.contentEquals(other.bbox) &&
                confidence == other.confidence
    }

    override fun hashCode(): Int {
        var result = label.hashCode()
        result = 31 * result + bbox.contentHashCode()
        result = 31 * result + confidence.hashCode()
        return result
    }
}

/**
 * The full snapshot of what the system "sees" right now.
 * Produced every frame (or every Nth frame) by the spatial layer.
 */
data class SpatialWorldModel(
    val timestampMs: Long,
    val objects: List<DetectedObject>,
    val targetLock: DetectedObject?,       // the object we're currently tracking for GUIDE
    val obstaclesInPath: List<DetectedObject>,
    val pathStatus: PathStatus,
) {
    fun findBestMatch(target: String): DetectedObject? {
        val lower = target.lowercase()
        return objects
            .filter { it.label.lowercase().contains(lower) || lower.contains(it.label.lowercase()) }
            .maxByOrNull { it.confidence }
    }

    fun findAllMatches(target: String): List<DetectedObject> {
        val lower = target.lowercase()
        return objects.filter {
            it.label.lowercase().contains(lower) || lower.contains(it.label.lowercase())
        }
    }
}

/**
 * A parsed user goal — what they want to do and what they want to do it to.
 */
data class UserGoal(
    val mode: GoalMode,
    val target: String,         // e.g. "chair", "exit", "phone"
    val rawUtterance: String,   // what they actually said
)

/**
 * What the system says back to the user.
 */
data class SpokenResponse(
    val text: String,
    val isHapticAlert: Boolean = false,
)

/**
 * Memory entry — where an object was last seen.
 */
data class MemoryEntry(
    val label: String,
    val locationDescription: String,
    val nearbyObjects: List<String>,
    val timestampMs: Long,
)
