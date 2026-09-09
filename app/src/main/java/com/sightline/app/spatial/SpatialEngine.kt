package com.sightline.app.spatial

import com.sightline.app.DetectedObject
import com.sightline.app.Direction
import com.sightline.app.DistanceZone
import com.sightline.app.PathStatus
import com.sightline.app.SpatialWorldModel

/**
 * Converts raw detections into a structured world model.
 *
 * Direction: bbox centre-x mapped to LEFT / CENTER / RIGHT thirds.
 * Distance: bbox height as fraction of frame → NEAR / MID / FAR.
 * Path: objects in the centre third with NEAR zone = potential obstacle.
 *
 * If ARCore depth becomes available, plug it in here — this class
 * already accepts distanceMeters on DetectedObject and will use it
 * when present.
 */
class SpatialEngine {

    /**
     * Build a world model from raw detections.
     */
    fun buildWorldModel(
        detections: List<DetectedObject>,
        targetLabel: String? = null,
    ): SpatialWorldModel {
        // Find obstacles in the direct path (center third, near zone)
        val obstacles = detections.filter { obj ->
            obj.direction == Direction.CENTER && obj.distanceZone == DistanceZone.NEAR
        }

        val pathStatus = when {
            obstacles.isEmpty() -> PathStatus.CLEAR
            obstacles.any { it.label in OBSTACLE_LABELS } -> PathStatus.BLOCKED
            else -> PathStatus.CLEAR // non-obstacle objects don't block
        }

        // Find target lock for GUIDE mode
        val targetLock = targetLabel?.let { label ->
            detections
                .filter { it.label.lowercase().contains(label.lowercase()) }
                .minByOrNull { distanceScore(it) }
        }

        return SpatialWorldModel(
            timestampMs = System.currentTimeMillis(),
            objects = detections,
            targetLock = targetLock,
            obstaclesInPath = obstacles,
            pathStatus = pathStatus,
        )
    }

    /**
     * Score an object for "closeness" — prefer near, then centre.
     * Used for target selection in FIND/GUIDE.
     */
    private fun distanceScore(obj: DetectedObject): Float {
        val zoneScore = when (obj.distanceZone) {
            DistanceZone.NEAR -> 0f
            DistanceZone.MID -> 1f
            DistanceZone.FAR -> 2f
        }
        val dirScore = when (obj.direction) {
            Direction.CENTER -> 0f
            Direction.LEFT, Direction.RIGHT -> 0.5f
        }
        return zoneScore + dirScore
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
     * Distance word for natural speech.
     */
    fun distanceWord(z: DistanceZone): String = when (z) {
        DistanceZone.NEAR -> "right here"
        DistanceZone.MID -> "a few steps ahead"
        DistanceZone.FAR -> "across the room"
    }

    companion object {
        /** Labels that count as obstacles for path-blocking. */
        private val OBSTACLE_LABELS = setOf(
            "person", "chair", "bench", "couch", "bed",
            "dining table", "potted plant", "suitcase", "truck",
            "car", "motorcycle", "bicycle",
        )
    }
}
