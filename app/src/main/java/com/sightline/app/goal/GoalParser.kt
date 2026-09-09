package com.sightline.app.goal

import com.sightline.app.GoalMode
import com.sightline.app.UserGoal

/**
 * Deterministic goal parser — keyword/phrase matching over a fixed vocabulary.
 *
 * No LLM, no ML, no network. Pure string matching. Fast, predictable,
 * zero hallucination risk. This is the right choice for a bounded demo
 * vocabulary where reliability > flexibility.
 */
class GoalParser {

    private data class IntentPattern(
        val mode: GoalMode,
        val triggers: List<String>,
    )

    private val intentPatterns = listOf(
        IntentPattern(
            GoalMode.FIND,
            listOf("find", "where is", "where's", "locate", "look for", "search for", "spot")
        ),
        IntentPattern(
            GoalMode.GUIDE,
            listOf("guide me", "take me", "help me get to", "navigate", "lead me", "walk me")
        ),
        IntentPattern(
            GoalMode.UNDERSTAND,
            listOf("what's around", "what is around", "describe", "what's happening",
                "what do you see", "tell me about", "what's in front")
        ),
        IntentPattern(
            GoalMode.REMEMBER,
            listOf("remember where", "remember this", "save location", "note where")
        ),
        IntentPattern(
            GoalMode.REMEMBER,  // recall is part of remember mode
            listOf("where did i put", "where's my", "where is my", "did you see my",
                "recall", "what did you see")
        ),
        IntentPattern(
            GoalMode.READ,
            listOf("read this", "read the", "read that", "read it", "read what",
                "read the sign", "read the text", "read the letter", "read the menu",
                "read the label", "read the paper", "read the board")
        ),
    )

    /** Common objects the system can find. Extend as needed. */
    private val knownTargets = listOf(
        "chair", "exit", "door", "desk", "table", "phone", "laptop", "bag",
        "backpack", "bottle", "cup", "book", "person", "screen", "window",
        "bed", "couch", "sofa", "plant", "lamp", "reception", "elevator",
        "stairs", "toilet", "sink", "tv", "remote", "keyboard", "mouse",
        "car", "bike", "bus", "train", "bench", "trash", "bin", "trashcan",
        "computer", "monitor", "printer", "stapler", "pen", "paper",
        "keys", "wallet", "glasses", "shoes", "jacket", "coat",
        "food", "water", "coffee", "tea", "snack",
        "wall", "floor", "ceiling", "carpet", "rug",
        "cabinet", "drawer", "shelf", "closet", "wardrobe",
        "light", "switch", "outlet", "plug",
        "fan", "ac", "air conditioner", "heater", "radiator",
        "picture", "frame", "poster", "mirror", "clock",
        "rug", "mat", "towel", "soap", "shampoo",
    )

    /**
     * Parse a raw utterance into a UserGoal.
     * Returns null if we can't match anything meaningful.
     */
    fun parse(utterance: String): UserGoal? {
        val input = utterance.lowercase().trim()
        if (input.isEmpty()) return null

        // Find intent
        var matchedMode: GoalMode? = null
        var bestTriggerLength = 0

        for (pattern in intentPatterns) {
            for (trigger in pattern.triggers) {
                if (input.contains(trigger) && trigger.length > bestTriggerLength) {
                    matchedMode = pattern.mode
                    bestTriggerLength = trigger.length
                }
            }
        }

        // Default to FIND if we detected a target but no explicit intent
        if (matchedMode == null) {
            matchedMode = GoalMode.FIND
        }

        // Find target — try longest match first
        var matchedTarget = ""
        for (target in knownTargets) {
            if (input.contains(target) && target.length > matchedTarget.length) {
                matchedTarget = target
            }
        }

        // Special case: "remember where I put X" — target after "put"
        if (matchedMode == GoalMode.REMEMBER && matchedTarget.isEmpty()) {
            val afterPut = input.indexOf("put")
            if (afterPut >= 0) {
                val remainder = input.substring(afterPut + 3).trim()
                matchedTarget = extractTargetFromRemainder(remainder)
            }
        }

        // For UNDERSTAND, no target needed
        if (matchedMode == GoalMode.UNDERSTAND && matchedTarget.isEmpty()) {
            matchedTarget = "surroundings"
        }

        // For READ, the "text" itself is the default target
        if (matchedMode == GoalMode.READ && matchedTarget.isEmpty()) {
            matchedTarget = "text"
        }

        if (matchedTarget.isEmpty()) return null

        return UserGoal(
            mode = matchedMode,
            target = matchedTarget,
            rawUtterance = utterance,
        )
    }

    private fun extractTargetFromRemainder(text: String): String {
        // Strip common filler words
        val cleaned = text.replace(Regex("\\b(the|a|an|my|your)\\b"), "").trim()
        for (target in knownTargets) {
            if (cleaned.contains(target)) return target
        }
        // Take the first meaningful word as a guess
        return cleaned.split("\\s+".toRegex()).firstOrNull() ?: ""
    }
}
