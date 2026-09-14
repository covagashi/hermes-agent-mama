package ai.hermes.mama

import android.content.Intent
import timber.log.Timber

/**
 * Punto de entrada del modo FakeGateway — **sólo activo en el flavor `dev`**.
 *
 * Runbook (ver README.md): cuando `MainActivity` arranca con el extra de intent
 * `fake_script`, la app debe usar como endpoint `ws://10.0.2.2:8399` — el FakeGateway
 * standalone de la tarea B5 (`./gradlew :testing:run`) — en lugar de las credenciales
 * guardadas en Conexión.
 *
 * En A1 no hay FakeGateway ni cliente todavía: este objeto sólo registra el override
 * para que C2 (pantalla Conexión) y B5/B2 (cliente WS) lo consuman cuando existan.
 * En `mama`, `BuildConfig.FAKE_GATEWAY_ENDPOINT` está vacío y el mecanismo es inerte.
 */
object DevGateway {
    const val EXTRA_FAKE_SCRIPT = "fake_script"

    /**
     * Endpoint WS que sustituye a las credenciales guardadas cuando hay un guion
     * `fake_script` activo. `null` en el flavor `mama` o sin el extra.
     */
    @Volatile
    var endpointOverride: String? = null
        private set

    /** Nombre del guion pedido vía [EXTRA_FAKE_SCRIPT] (lo interpreta el FakeGateway, B5). */
    @Volatile
    var requestedScript: String? = null
        private set

    /** Llamar con el intent de arranque; un arranque sin el extra limpia el override. */
    fun onNewIntent(intent: Intent?) {
        if (BuildConfig.FAKE_GATEWAY_ENDPOINT.isEmpty()) return
        requestedScript = intent?.getStringExtra(EXTRA_FAKE_SCRIPT)?.takeIf(String::isNotBlank)
        endpointOverride = requestedScript?.let { BuildConfig.FAKE_GATEWAY_ENDPOINT }
        if (endpointOverride != null) {
            Timber.d("fake_script=%s → endpoint dev %s", requestedScript, endpointOverride)
        }
    }
}
