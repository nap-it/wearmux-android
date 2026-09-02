package com.example.peciwearables.integration.safety

object VoiceCommandMatcher {

    private val CROSSING_TOKENS = setOf(
        "cross", "crossing", "crossed", "crosses",
        "cros", "crosing",
        "crosse", "crosseing", "crys", "crost", "crust",
        "atravessar", "cruzar",
        "go", "going",   // paridade com cloud _CROSSING_KEYWORDS
    )

    private val STOP_TOKENS = setOf(
        "stop", "stopped", "stops",
        "stopp", "stoop",
        "pare", "para", "parar",
    )

    // Strip apostrophes before splitting so "don't" becomes "dont", which is in this set.
    private val NEGATION_PREFIXES = setOf(
        "not", "dont", "no", "never",
        "não", "nunca",
    )

    fun matchesCrossing(transcript: String): Boolean =
        matchesAnyToken(transcript, CROSSING_TOKENS)

    fun matchesStop(transcript: String): Boolean =
        matchesAnyToken(transcript, STOP_TOKENS)

    private fun matchesAnyToken(text: String, tokens: Set<String>): Boolean {
        val words = text.lowercase().replace("'", "").split(Regex("\\W+")).filter { it.isNotBlank() }
        for ((i, word) in words.withIndex()) {
            if (word !in tokens) continue
            val prev = if (i > 0) words[i - 1] else ""
            if (prev in NEGATION_PREFIXES) continue
            return true
        }
        return false
    }
}
