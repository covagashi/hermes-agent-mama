package ai.hermes.mama.feature.chat.clarify

/**
 * Estado de la tarjeta de pregunta (clarify) tal como la pinta la UI (C7,
 * mockup Pregunta.dc.html). Es inmutable y self-contained: la capa de Compose
 * no conoce `ClarifyRequest` ni el contrato del gateway.
 */
data class ClarifyCardState(
    /**
     * Id opaco de la petición en cola (el `id` de frame `srq-…`, estable entre
     * re-entregas). La UI lo devuelve tal cual a `ClarifyController.answer`/
     * `dismiss`: un `request.cancel` que cambie la cabeza entre el render y el
     * tap no puede responder una tarjeta que la usuaria no vio.
     */
    val key: String,
    /** Texto de la pregunta que se muestra ahora (en un lote, la [progress.current]-ésima). */
    val question: String,
    /** Qué se le pide a la usuaria: una opción, varias, o texto libre. */
    val input: ClarifyInput,
    /**
     * "N de M" del lote (`questions` de §2.5); `null` en pregunta única.
     * También sirve de número de pregunta para [questionNumber].
     */
    val progress: ClarifyProgress? = null,
    /** Ciclo de vida: pendiente → respondida / fallo de envío. */
    val status: ClarifyStatus = ClarifyStatus.Pending,
) {
    /**
     * Número 1-based de la pregunta visible: la UI lo devuelve a
     * `ClarifyController.answer` para que un tap tardío sobre la pregunta
     * anterior (mismo [key], doble tap rápido) no responda la siguiente sin
     * leerla.
     */
    val questionNumber: Int
        get() = progress?.current ?: SINGLE_QUESTION_NUMBER

    companion object {
        /** [questionNumber] cuando no hay lote (pregunta única). */
        const val SINGLE_QUESTION_NUMBER = 1
    }
}

/** Qué se le pide a la usuaria en la pregunta visible (ROADMAP C7 + mockup). */
sealed interface ClarifyInput {
    /**
     * `choices` sin `multi_select` (§2.5): botones grandes, un tap = respuesta.
     * El texto libre queda como alternativa ("o responde con tu voz").
     */
    data class Choices(
        val options: List<ClarifyOption>,
    ) : ClarifyInput

    /**
     * `choices` con `multi_select`: chips seleccionables + "Listo". La
     * respuesta viaja como string JSON de la lista de etiquetas (§2.5; lo que
     * el backend decodifica con `_clean_answer`).
     */
    data class MultiSelect(
        val options: List<ClarifyOption>,
    ) : ClarifyInput

    /** Sin `choices`: campo de texto + micrófono. */
    data object FreeText : ClarifyInput
}

/**
 * Una opción ofrecida por el backend. [display] es lo que se pinta (sin el
 * sufijo "(Recommended)" que `mark_recommended` añade a la primera opción);
 * [wire] es la etiqueta exacta del wire, que es lo que se devuelve en
 * `{answer}` — el servidor le quita el sufijo él mismo (`strip_recommended`),
 * así que responder verbatim es siempre correcto.
 */
data class ClarifyOption(
    val display: String,
    val wire: String,
    /** `true` si el wire traía el marcador "(Recommended)" → se pinta la pista "Recomendada". */
    val recommended: Boolean = false,
)

/** "N de M" del lote de preguntas (mockup: esquina superior derecha). */
data class ClarifyProgress(
    val current: Int,
    val total: Int,
)

/** Ciclo de vida de la tarjeta (pendiente → respondida, error visual). */
enum class ClarifyStatus {
    /** Esperando a la usuaria (inputs activos). */
    Pending,

    /** Respuesta enviada — la tarjeta muestra la confirmación un momento. */
    Answered,

    /**
     * La respuesta no salió al cable (socket muerto): aviso visual y los
     * inputs siguen activos — la re-entrega tras reconexión la reenvía.
     */
    SendFailed,
}

/**
 * Etiqueta "(Recommended)" que el backend añade a la primera `choice`
 * (`tools/clarify_tool.py: mark_recommended` / `RECOMMENDED_LABEL`). Es
 * decoración de presentación: se quita para pintar y el servidor la ignora al
 * recibirla (`strip_recommended` case-insensitive).
 */
private const val RECOMMENDED_SUFFIX = "(Recommended)"

/**
 * `choices` del wire → [ClarifyOption]: el sufijo "(Recommended)" no se pinta
 * (es inglés y ruido para la usuaria); marca [ClarifyOption.recommended] para
 * que la UI lo diga en español.
 */
fun clarifyOptionFor(choice: String): ClarifyOption {
    val stripped = stripRecommendedSuffix(choice)
    return ClarifyOption(
        display = stripped,
        wire = choice,
        recommended = stripped.length != choice.trim().length,
    )
}

/** Espejo de `strip_recommended` del backend: quita el sufijo si lo hay (case-insensitive). */
private fun stripRecommendedSuffix(text: String): String {
    val trimmed = text.trim()
    return if (trimmed.endsWith(RECOMMENDED_SUFFIX, ignoreCase = true)) {
        trimmed.dropLast(RECOMMENDED_SUFFIX.length).trim()
    } else {
        trimmed
    }
}
