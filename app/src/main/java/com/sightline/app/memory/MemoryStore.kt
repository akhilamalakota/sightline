package com.sightline.app.memory

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.sightline.app.DetectedObject
import com.sightline.app.MemoryEntry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "sightline_memory")

/**
 * Simple memory store — remembers where objects were last seen.
 *
 * Persisted to DataStore (Android's modern SharedPreferences replacement).
 * Keyed by object label, stores a location description + timestamp.
 */
class MemoryStore(private val context: Context) {

    /**
     * Store the location of an object based on current detections.
     * Finds the best match for the target label and records its position
     * relative to nearby landmarks.
     */
    suspend fun rememberObject(
        targetLabel: String,
        detections: List<DetectedObject>,
    ): MemoryEntry? {
        val target = detections.find {
            it.label.lowercase().contains(targetLabel.lowercase())
        } ?: return null

        // Build a natural-language description from nearby objects
        val nearby = detections
            .filter { it.label != target.label }
            .sortedBy { distance(it, target) }
            .take(3)
            .map { it.describe() }

        val locationDesc = buildString {
            append(target.direction.name.lowercase())
            append(", ")
            append(target.distanceZone.name.lowercase())
            if (nearby.isNotEmpty()) {
                append(" near ")
                append(nearby.joinToString(" and ") { it.split("—").first().trim() })
            }
        }

        val entry = MemoryEntry(
            label = target.label,
            locationDescription = locationDesc,
            nearbyObjects = nearby,
            timestampMs = System.currentTimeMillis(),
        )

        // Persist
        val labelKey = stringPreferencesKey("mem_${target.label}")
        val locationKey = stringPreferencesKey("mem_${target.label}_loc")
        val nearbyKey = stringPreferencesKey("mem_${target.label}_nearby")
        val timeKey = longPreferencesKey("mem_${target.label}_time")

        context.dataStore.edit { prefs ->
            prefs[labelKey] = target.label
            prefs[locationKey] = locationDesc
            prefs[nearbyKey] = nearby.joinToString("|")
            prefs[timeKey] = System.currentTimeMillis()
        }

        return entry
    }

    /**
     * Recall where an object was last seen.
     */
    suspend fun recallObject(label: String): MemoryEntry? {
        val lKey = stringPreferencesKey("mem_$label")
        val locKey = stringPreferencesKey("mem_${label}_loc")
        val nearKey = stringPreferencesKey("mem_${label}_nearby")
        val timeKey = longPreferencesKey("mem_${label}_time")

        val prefs = context.dataStore.data.first()

        val storedLabel = prefs[lKey] ?: return null
        val location = prefs[locKey] ?: return null
        val nearby = prefs[nearKey]?.split("|") ?: emptyList()
        val time = prefs[timeKey] ?: 0L

        return MemoryEntry(
            label = storedLabel,
            locationDescription = location,
            nearbyObjects = nearby,
            timestampMs = time,
        )
    }

    /**
     * Check if we have a memory for this label.
     */
    suspend fun hasMemory(label: String): Boolean {
        val key = stringPreferencesKey("mem_$label")
        return context.dataStore.data.map { it[key] != null }.first()
    }

    private fun distance(a: DetectedObject, b: DetectedObject): Float {
        val cx_a = (a.bbox[0] + a.bbox[2]) / 2f
        val cx_b = (b.bbox[0] + b.bbox[2]) / 2f
        val cy_a = (a.bbox[1] + a.bbox[3]) / 2f
        val cy_b = (b.bbox[1] + b.bbox[3]) / 2f
        return Math.sqrt(((cx_a - cx_b).toDouble().pow(2) + (cy_a - cy_b).toDouble().pow(2))).toFloat()
    }

    private fun Double.pow(n: Int): Double = Math.pow(this, n.toDouble())
}
