package ai.hermes.mama.feature.browser

import ai.hermes.mama.core.controller.ControllerState

/**
 * Estado de la pantalla Navegador tal como la pinta la UI (ROADMAP §5/F4,
 * mockup Navegador.dc.html). Inmutable y self-contained: la capa de Compose no
 * conoce `GatewayEvent` ni `ControllerState` — todo llega ya traducido.
 */
data class BrowserPaneState(
    /** Ciclo de vida del registro del controlador (traducción de [ControllerState]). */
    val phase: BrowserPhase = BrowserPhase.Connecting,
    /**
     * Último `browser.progress.message` no vacío recibido para la sesión. La
     * barra superior lo muestra tal cual; `null` → texto por defecto
     * ("Hermes está navegando…"). También alimenta el detalle del velo
     * «Un momento…» ("voy a pulsar «Descargar factura»").
     */
    val progressMessage: String? = null,
    /** Comando §2.6 registrado/ejecutándose ahora mismo → velo «Un momento…». */
    val commandInFlight: Boolean = false,
    /**
     * Aviso "Hermes va a usar el navegador" (§5/F3): la pantalla se abrió por
     * [ai.hermes.mama.core.controller.ControllerSignal.NavigateToBrowser]. El
     * controlador lo apaga solo pasado unos segundos.
     */
    val showAutoOpenNotice: Boolean = false,
)

/** Fases de la pantalla (traducción UI de [ControllerState]). */
enum class BrowserPhase {
    /** Idle/Registering: el registro va en vuelo (o aún no se pidió). */
    Connecting,

    /** Attached: comandos, resultados y `browser.progress` fluyen. */
    Browsing,

    /**
     * El servidor respondió 4403 al registro (flag apagado, protocolo distinto
     * o identidad no autenticada) → texto `browser_server_not_enabled`.
     */
    ServerNotEnabled,

    /** Otro fallo de registro (red, -32000…) → texto `browser_registration_failed`. */
    RegistrationFailed,
}

/** Traducción [ControllerState] → [BrowserPhase] (la barra decide texto e icono). */
fun browserPhaseFor(state: ControllerState): BrowserPhase =
    when (state) {
        ControllerState.Idle, ControllerState.Registering -> BrowserPhase.Connecting
        ControllerState.Attached -> BrowserPhase.Browsing
        ControllerState.ServerNotEnabled -> BrowserPhase.ServerNotEnabled
        is ControllerState.RegistrationFailed -> BrowserPhase.RegistrationFailed
    }
