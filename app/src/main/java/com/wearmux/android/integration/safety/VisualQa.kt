package com.wearmux.android.integration.safety

import com.wearmux.android.Detection


class VisualQa {

    /** Frases-gatilho reconhecidas (case-insensitive, com tolerância). */
    private val triggerPhrases = listOf(
        "what's in front of me",
        "what is in front of me",
        "what's ahead",
        "what's in my hand",
        "what's in front",
        "what is in front",
        "o que está à minha frente",
        "o que está na minha mão",
        "describe what you see",
    )

    fun isQuestion(transcript: String): Boolean {
        val t = transcript.lowercase()
        return triggerPhrases.any { t.contains(it) }
    }

    /**
     * Descreve a cena a partir das detecções YOLO. Se `depthByDetection`
     * for fornecido, anexa a distância. Caso contrário (estado actual),
     * descreve só o objecto principal.
     *
     * Devolve uma frase curta pronta a passar a TTS / reproduzir nos
     * earphones.
     */
    fun describe(
        detections: List<Detection>,
        depthByDetection: Map<Detection, Float>? = null,
    ): String {
        if (detections.isEmpty()) return "I can't clearly see anything in front of you."
        // Pega na detecção com maior confiança e área (mais visível).
        val main = detections
            .filter { it.confidence >= 0.40f }
            .maxByOrNull { it.confidence * area(it) }
            ?: return "Nothing relevant detected."

        val labelText = translateLabel(main.label)
        val depth = depthByDetection?.get(main)
        return if (depth != null) {
            "In front of you: $labelText about ${"%.1f".format(depth)} meters away."
        } else {
            "In front of you: $labelText."
        }
    }

    private fun area(d: Detection): Float = (d.right - d.left) * (d.bottom - d.top)

    private fun translateLabel(label: String): String = when (label.lowercase()) {
        "person" -> "a person"
        "car" -> "a car"
        "bicycle" -> "a bicycle"
        "motorcycle", "motorbike" -> "a motorcycle"
        "bus" -> "a bus"
        "truck" -> "a truck"
        "chair" -> "a chair"
        "couch", "sofa" -> "a couch"
        "bed" -> "a bed"
        "tv", "tv monitor", "tvmonitor" -> "a TV"
        "laptop" -> "a laptop"
        "mouse" -> "a computer mouse"
        "keyboard" -> "a keyboard"
        "cell phone" -> "a phone"
        "book" -> "a book"
        "bottle" -> "a bottle"
        "cup" -> "a cup"
        "dining table" -> "a table"
        "door" -> "a door"
        "stop sign" -> "a stop sign"
        "traffic light" -> "a traffic light"
        else -> label
    }
}
