package ai.hermes.mama.core.controller

/**
 * Carga `hermes_snapshot.js` (el IIFE que define `window.__hermes`, ROADMAP §2.6,
 * tarea F1). El fichero vive en `src/main/assets/` y Gradle lo publica en la raíz
 * del classpath del módulo, así funciona igual en la app (evaluateJavascript),
 * en tests JVM y en tests instrumentados.
 */
public object SnapshotScript {
    private const val RESOURCE_PATH = "/hermes_snapshot.js"

    @Volatile
    private var cached: String? = null

    /** Devuelve el source JS listo para `WebView.evaluateJavascript`. */
    public fun load(): String = cached ?: loadFromClasspath().also { cached = it }

    private fun loadFromClasspath(): String =
        SnapshotScript::class.java.getResourceAsStream(RESOURCE_PATH)?.use { stream ->
            stream.bufferedReader(Charsets.UTF_8).readText()
        } ?: error("hermes_snapshot.js no está en el classpath ($RESOURCE_PATH)")
}
