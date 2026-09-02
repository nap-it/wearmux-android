package com.example.peciwearables.integration.safety

import com.example.peciwearables.Detection


class VisualQa {

    /** Frases-gatilho reconhecidas (case-insensitive, com tolerância). */
    private val triggerPhrases = listOf(
        "o que está à minha frente",
        "o que está em frente",
        "o que está à frente",
        "o que está na minha mão",
        "what's in front",
        "what is in front",
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
        if (detections.isEmpty()) return "Não vejo nada com clareza à tua frente."
        // Pega na detecção com maior confiança e área (mais visível).
        val main = detections
            .filter { it.confidence >= 0.40f }
            .maxByOrNull { it.confidence * area(it) }
            ?: return "Nada de relevante detectado."

        val labelPt = translateLabelPt(main.label)
        val depth = depthByDetection?.get(main)
        return if (depth != null) {
            "À tua frente: $labelPt a cerca de ${"%.1f".format(depth)} metros."
        } else {
            "À tua frente: $labelPt."
        }
    }

    private fun area(d: Detection): Float = (d.right - d.left) * (d.bottom - d.top)

    private fun translateLabelPt(label: String): String = when (label.lowercase()) {
        "person" -> "uma pessoa"
        "car" -> "um carro"
        "bicycle" -> "uma bicicleta"
        "motorcycle", "motorbike" -> "uma mota"
        "bus" -> "um autocarro"
        "truck" -> "um camião"
        "chair" -> "uma cadeira"
        "couch", "sofa" -> "um sofá"
        "bed" -> "uma cama"
        "tv", "tv monitor", "tvmonitor" -> "uma televisão"
        "laptop" -> "um portátil"
        "mouse" -> "um rato (computador)"
        "keyboard" -> "um teclado"
        "cell phone" -> "um telemóvel"
        "book" -> "um livro"
        "bottle" -> "uma garrafa"
        "cup" -> "uma chávena"
        "dining table" -> "uma mesa"
        "door" -> "uma porta"
        "stop sign" -> "um sinal de stop"
        "traffic light" -> "um semáforo"
        else -> label
    }
}
