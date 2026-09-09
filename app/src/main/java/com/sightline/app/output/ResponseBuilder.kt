package com.sightline.app.output

import com.sightline.app.*
import com.sightline.app.spatial.SpatialEngine

/**
 * Builds short, actionable spoken responses from the system's current state.
 *
 * Every response template is defined here — no improvisation at runtime.
 * This makes the demo predictable and the code reviewable.
 */
class ResponseBuilder {

    private val spatial = SpatialEngine()

    /**
     * FIND mode response — we have a goal and a world model.
     */
    fun buildFindResponse(
        goal: UserGoal,
        world: SpatialWorldModel,
    ): SpokenResponse {
        val matches = world.findAllMatches(goal.target)

        if (matches.isEmpty()) {
            return SpokenResponse(
                text = "I can't see ${goal.target} right now. Try turning slowly."
            )
        }

        if (matches.size == 1) {
            val obj = matches.first()
            val article = articleFor(obj.label)
            val distText = obj.distanceMeters?.let { spatial.metersWord(it) }
                ?: spatial.distanceWord(obj.distanceZone)
            return SpokenResponse(
                text = "$article ${obj.label} is $distText, " +
                       "${spatial.directionWord(obj.direction)}."
            )
        }

        // Multiple matches — describe the closest one
        val closest = matches.minByOrNull { closenessScore(it) }!!
        val distText = closest.distanceMeters?.let { spatial.metersWord(it) }
            ?: spatial.distanceWord(closest.distanceZone)
        return SpokenResponse(
            text = "There are ${matches.size} ${goal.target}s. " +
                   "One is $distText, " +
                   "${spatial.directionWord(closest.direction)}."
        )
    }

    /**
     * GUIDE mode response — tell user what to do next.
     */
    fun buildGuideResponse(
        goal: UserGoal,
        world: SpatialWorldModel,
        hasArrived: Boolean,
    ): SpokenResponse {
        if (hasArrived) {
            val obj = world.targetLock
            if (obj != null) return buildArrivalResponse(obj)
            return SpokenResponse(
                text = "${goal.target.replaceFirstChar { it.uppercase() }} is right ahead.",
                isHapticAlert = true,
            )
        }

        return when (world.pathStatus) {
            PathStatus.BLOCKED -> {
                val obstacle = world.obstaclesInPath.firstOrNull()
                val avoidDir = obstacle?.let {
                    if (it.direction == Direction.LEFT) "right" else "left"
                } ?: "side"
                SpokenResponse(
                    text = "Obstacle ahead. Move slightly $avoidDir.",
                    isHapticAlert = true,
                )
            }
            PathStatus.CLEAR -> SpokenResponse(text = "Continue forward.")
            PathStatus.UNKNOWN -> SpokenResponse(text = "Let me scan again.")
        }
    }

    /**
     * Arrival announcement — user has walked up to the tracked object.
     */
    fun buildArrivalResponse(obj: DetectedObject): SpokenResponse {
        return SpokenResponse(
            text = "You've reached the ${obj.label}.",
            isHapticAlert = true,
        )
    }

    /**
     * UNDERSTAND mode response — contextual summary.
     */
    fun buildUnderstandResponse(world: SpatialWorldModel): SpokenResponse {
        if (world.objects.isEmpty()) {
            return SpokenResponse(text = "The scene is unclear. Let me scan again.")
        }

        val summary = world.objects.take(5).joinToString(". ") { obj ->
            "${spatial.directionWord(obj.direction)} there ${articleFor(obj.label)} ${obj.label}"
        }

        return SpokenResponse(text = "I see: $summary.")
    }

    /**
     * REMEMBER mode — confirm storage.
     */
    fun buildRememberStoredResponse(target: String): SpokenResponse {
        return SpokenResponse(text = "I'll remember its last observed location.")
    }

    /**
     * REMEMBER mode — recall what was stored.
     */
    fun buildRecallResponse(entry: MemoryEntry): SpokenResponse {
        val timeAgo = formatTimeAgo(entry.timestampMs)
        return SpokenResponse(
            text = "${entry.label.replaceFirstChar { it.uppercase() }} was last seen " +
                   "${entry.locationDescription}. $timeAgo."
        )
    }

    /**
     * No-detection fallback.
     */
    fun buildNoDetectionResponse(): SpokenResponse {
        return SpokenResponse(text = "The scene is unclear. Let me scan again.")
    }

    /**
     * Low confidence response.
     */
    fun buildLowConfidenceResponse(bestGuess: String): SpokenResponse {
        return SpokenResponse(text = "I'm not certain, but I believe there's a $bestGuess.")
    }

    /**
     * Permission denied response.
     */
    fun buildPermissionDeniedResponse(): SpokenResponse {
        return SpokenResponse(
            text = "Sightline needs camera access to work. Please enable it in settings."
        )
    }

    // --- Helpers ---

    private fun articleFor(label: String): String {
        val vowels = setOf('a', 'e', 'i', 'o', 'u')
        return if (label.isNotEmpty() && label[0].lowercaseChar() in vowels) "an" else "a"
    }

    /** Closeness score — lower is closer. Prefers metric distance, then zone, then centre. */
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

    private fun formatTimeAgo(timestampMs: Long): String {
        val diff = System.currentTimeMillis() - timestampMs
        val minutes = diff / 60_000
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "$minutes minutes ago"
            else -> "${minutes / 60} hours ago"
        }
    }
}
