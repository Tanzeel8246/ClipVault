package com.clipvault.mobile.keyboard

import android.graphics.RectF
import kotlin.math.abs

/**
 * SwipeTypingEngine — Encapsulates glide/gesture typing trajectory tracking,
 * key hit detection, and candidate word ranking.
 */
object SwipeTypingEngine {

    data class SwipePoint(val x: Float, val y: Float, val timestamp: Long)

    /**
     * Resolves the key containing the touch point, or null if outside all hitboxes.
     */
    fun resolveKeyAt(x: Float, y: Float, hitBoxes: Map<String, RectF>): String? {
        hitBoxes.forEach { (key, rect) ->
            if (rect.contains(x, y)) {
                return key
            }
        }
        return null
    }

    /**
     * Calculates the best swipe candidates from the recorded sequence of keys
     * and a provided vocabulary dictionary.
     */
    fun scoreCandidates(
        keySequence: List<String>,
        dictionary: List<String>
    ): List<String> {
        if (keySequence.isEmpty()) return emptyList()

        val startKey = keySequence.first().lowercase()
        val endKey = keySequence.last().lowercase()
        val keySet = keySequence.map { it.lowercase() }.toSet()

        // Filter dictionary by start key and reasonable length
        val candidates = dictionary.asSequence()
            .map { it.lowercase() }
            .filter { it.isNotEmpty() && it.startsWith(startKey) }
            .filter { word ->
                // Must contain end key or be very close
                word.endsWith(endKey) || word.contains(endKey)
            }
            .map { word ->
                var score = 0
                // Match start letter
                if (word.startsWith(startKey)) score += 30
                // Match end letter
                if (word.endsWith(endKey)) score += 25

                // Check letter coverage
                var matchedChars = 0
                word.forEach { c ->
                    if (c.toString() in keySet) matchedChars++
                }
                score += (matchedChars * 10) / word.length.coerceAtLeast(1)

                // Length penalty for huge deviations from gesture length
                val lenDiff = abs(word.length - keySequence.size)
                score -= lenDiff * 3

                word to score
            }
            .sortedByDescending { it.second }
            .map { it.first }
            .distinct()
            .take(3)
            .toList()

        return candidates
    }
}
