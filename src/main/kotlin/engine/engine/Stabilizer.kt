package engine.engine

import engine.Config

class Stabilizer {

    private val window = ArrayDeque<String>(Config.dedupWindow + 1)
    private val windowSet = LinkedHashSet<String>(Config.dedupWindow * 2)
    private val lastConfidence = HashMap<String, Double>()

    sealed class EmitDecision {
        data class NewDetection(val key: String) : EmitDecision()
        data class UpdatedDetection(val key: String, val oldConfidence: Double) : EmitDecision()
        object Suppress : EmitDecision()
    }

    fun evaluate(key: String, confidence: Double): EmitDecision {
        if (confidence < Config.minConfidenceEmit) return EmitDecision.Suppress

        return if (key !in windowSet) {
            addToWindow(key)
            lastConfidence[key] = confidence
            EmitDecision.NewDetection(key)
        } else {
            val prev = lastConfidence[key] ?: 0.0
            if (kotlin.math.abs(confidence - prev) >= 0.05) {
                lastConfidence[key] = confidence
                EmitDecision.UpdatedDetection(key, prev)
            } else {
                EmitDecision.Suppress
            }
        }
    }

    private fun addToWindow(key: String) {
        if (windowSet.size >= Config.dedupWindow) {
            val oldest = window.removeFirst()
            windowSet.remove(oldest)
        }
        window.addLast(key)
        windowSet.add(key)
    }

    fun reset() {
        window.clear()
        windowSet.clear()
        lastConfidence.clear()
    }
}
