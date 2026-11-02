package com.clipvault.mobile.keyboard

import android.graphics.RectF

/**
 * DynamicTouchDisambiguator — Gboard-grade Dynamic Touch Target Expansion Engine.
 * Dynamically expands the hidden clickable hit area of the next predicted letters
 * by 12-15% based on language context and word prefix probabilities, preventing
 * mistypes during rapid typing.
 */
object DynamicTouchDisambiguator {

    /**
     * Resolves the most probable intended key for a given touch coordinate (touchX, touchY).
     * If the physically hit key is already a high-probability continuation, it is preserved.
     * If an adjacent letter has a significantly higher probability given the prefix,
     * the hit target expands to capture the intended key.
     */
    fun resolveKey(
        hitKey: String,
        touchX: Float,
        touchY: Float,
        typedPrefix: String,
        keyHitBoxes: Map<String, RectF>,
        dictionaryBucket: List<String>
    ): String {
        // Control keys or non-single-letter characters are never remapped
        if (hitKey.length != 1 || !hitKey[0].isLetter() || typedPrefix.isEmpty()) {
            return hitKey
        }

        val prefixLower = typedPrefix.lowercase()
        val hitChar = hitKey.lowercase()[0]

        // 1. Calculate probability/frequency of next characters following typedPrefix
        val nextCharFreq = mutableMapOf<Char, Int>()
        var totalMatches = 0

        for (word in dictionaryBucket) {
            val wLower = word.lowercase()
            if (wLower.startsWith(prefixLower) && wLower.length > prefixLower.length) {
                val nextCh = wLower[prefixLower.length]
                nextCharFreq[nextCh] = (nextCharFreq[nextCh] ?: 0) + 1
                totalMatches++
                if (totalMatches >= 40) break
            }
        }

        // If no predictions found, keep the original key
        if (nextCharFreq.isEmpty()) return hitKey

        val hitKeyProb = nextCharFreq[hitChar] ?: 0

        // If the key hit by the user is already among top predictions, trust the user
        val maxProb = nextCharFreq.values.maxOrNull() ?: 0
        if (hitKeyProb >= maxProb * 0.7 && hitKeyProb > 0) {
            return hitKey
        }

        // 2. Inspect adjacent candidate keys with high probability
        var bestAlternative = hitKey
        var bestScore = Float.MAX_VALUE

        // Expansion margin: up to 18% of key dimensions
        for ((candidateKey, rect) in keyHitBoxes) {
            if (candidateKey == hitKey || candidateKey.length != 1 || !candidateKey[0].isLetter()) continue

            val candidateChar = candidateKey.lowercase()[0]
            val freq = nextCharFreq[candidateChar] ?: continue
            if (freq <= hitKeyProb) continue // only consider if strictly more probable

            // Expand candidate key's rect by 15%
            val dx = rect.width() * 0.15f
            val dy = rect.height() * 0.15f
            val expandedRect = RectF(
                rect.left - dx,
                rect.top - dy,
                rect.right + dx,
                rect.bottom + dy
            )

            if (expandedRect.contains(touchX, touchY)) {
                val cx = rect.centerX()
                val cy = rect.centerY()
                val dist = Math.hypot((touchX - cx).toDouble(), (touchY - cy).toDouble()).toFloat()

                // Score balances distance to center and probability weight
                val probWeight = 1.0f - (freq.toFloat() / maxProb.toFloat().coerceAtLeast(1f)) * 0.35f
                val weightedDist = dist * probWeight

                if (weightedDist < bestScore) {
                    bestScore = weightedDist
                    bestAlternative = candidateKey
                }
            }
        }

        return bestAlternative
    }
}
