# apps/android/ — "Hermes para mamá" (Android nativo)

Aplica encima del `AGENTS.md` raíz. Este directorio contiene la app Android nativa: un cliente
minimalista tipo WhatsApp para un `hermes serve` remoto, diseñado para una persona mayor sin
conocimientos técnicos. **El plan de trabajo completo está en `ROADMAP.md`; léelo entero antes de
tocar nada.** Cada tarea del roadmap tiene ID, dependencias, criterios de aceptación y tests.

## Reglas no negociables

- **Repositorio público.** Nunca subas URLs reales de servidores, tokens, cookies, contraseñas,
  keystores, nombres/correos de personas reales ni capturas con datos personales. Usa
  `hermes.example.invalid` y `usuario` en ejemplos y tests. La firma de release entra por
  secretos de CI (`ANDROID_KEYSTORE_B64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`,
  `ANDROID_KEY_PASSWORD`); `*.jks`, `*.keystore`, `keystore.properties`, `local.properties` y
  `google-services.json` están en `.gitignore` y deben seguir estándolo.
- **La app no reimplementa a Hermes.** No hay lógica de agente, ni prompts, ni llamadas a modelos
  en el cliente. Todo pasa por el JSON-RPC de `hermes serve` (`/api/ws`). Si falta algo en el
  backend, se anota en `ROADMAP.md` §9 (riesgos/preguntas) — no se parchea el core desde aquí.
- **Simplicidad para la usuaria por encima de todo.** Sin slash commands, sin selector de modelo,
  sin ajustes visibles más allá de la pantalla de conexión. Texto ≥ 18 sp en el chat, objetivos
  táctiles ≥ 56 dp, contraste AA, todo con `contentDescription`. Español (`values/`) es el idioma
  base; `values-en/` es opcional.
- **Contrato del backend = `apps/shared/src/gateway-contract.openrpc.json`.** Los tipos Kotlin se
  generan con `scripts/gen_android_contract.py` (tarea A3); no se escriben a mano DTOs que ya
  estén en el contrato. Un test falla si el fichero generado está desactualizado.
- **Tests con cada tarea.** Ninguna tarea se cierra sin los tests que lista el roadmap en verde:
  `./gradlew :app:testDevDebugUnitTest` (JVM) y, cuando aplica, `connectedDevDebugAndroidTest`
  (instrumentados, emulador API 34 en CI).
- **Estilo Kotlin.** `ktlint` + `detekt` (configs en `config/`). Sin `!!`, sin `GlobalScope`, sin
  `runBlocking` en producción. Coroutines + `StateFlow`; una única `Activity`; Compose M3.
- **Commits pequeños, un ID de tarea por PR** (`android(A7): …`). El PR describe qué tests cubren
  el cambio. No se mezclan tareas de hitos distintos.
- **No toques nada fuera de `apps/android/` y `scripts/gen_android_contract.py`** salvo que el
  roadmap lo pida explícitamente (p. ej. el workflow en `.github/workflows/android.yml`).
