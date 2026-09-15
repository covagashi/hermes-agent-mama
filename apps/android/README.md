# Hermes para mamá — app Android

Cliente Android minimalista (tipo WhatsApp) para hablar con un `hermes serve` remoto.
Diseñado para una persona mayor: pocas pantallas, letra grande, voz, botones Sí/No.

El plan de trabajo completo está en [`ROADMAP.md`](ROADMAP.md) y las reglas del área
en [`AGENTS.md`](AGENTS.md). Léelos antes de tocar nada.

## Requisitos

- JDK 17+ y Android SDK con la plataforma 35 instalada.
- `local.properties` en este directorio apuntando al SDK (no se commitea):

  ```properties
  sdk.dir=/ruta/a/Android/sdk
  ```

## Compilar en 3 comandos

```bash
cd apps/android
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties   # ajusta a tu SDK
./gradlew assembleDevDebug
```

El APK queda en `app/build/outputs/apk/dev/debug/`.

## Arrancar contra el FakeGateway (desarrollo / CI)

El FakeGateway es un servidor WebSocket de pruebas (módulo `:testing`, tarea B5) que
implementa el subconjunto del JSON-RPC de `hermes serve` que usa la app: login HTTP,
`/api/ws` autenticado por ticket, llamadas cliente→servidor, eventos, peticiones
servidor→cliente (`srq-*`, approval/clarify), `open_requests` y el controlador de
navegador (§2.1–§2.6 del ROADMAP). Todo lo que responde lo dicta un **guion** JSON
en `testing/scripts/`; no hay lógica de agente ni modelos.

```bash
# 1. arrancar el FakeGateway en el host (bind 0.0.0.0:8399 por defecto):
./gradlew :testing:run                              # guion por defecto: hola_mundo
./gradlew :testing:run --args="--script <guion>"    # otro guion
./gradlew :testing:run -Pscript=approval -Pport=8399 -Phost=0.0.0.0
FAKE_SCRIPT=clarify ./gradlew :testing:run          # equivalente por env

# 2. instalar el flavor dev en el emulador:
./gradlew :app:installDevDebug

# 3. arrancar la app contra el FakeGateway (10.0.2.2 = host visto desde el emulador).
# OJO: hay que usar el FQCN — ".MainActivity" se expandiría contra el
# applicationId "ai.hermes.mama.dev" y la clase vive en "ai.hermes.mama".
adb shell am start -n ai.hermes.mama.dev/ai.hermes.mama.MainActivity --es fake_script <guion>
```

Con el extra `fake_script` presente, `DevGateway` (`app/…/DevGateway.kt`) registra
`ws://10.0.2.2:8399` como endpoint en lugar de las credenciales guardadas en la
pantalla Conexión. En el flavor `mama` el mecanismo está inerte.

### Guiones disponibles (`testing/scripts/`)

| Guion | Escenario |
|---|---|
| `hola_mundo` | Chat semilla; `hola` → message.start + 3 deltas + complete (defecto) |
| `deltas` | Streaming con pausas, `status.update` y un tool.start/complete |
| `approval` | Petición servidor→cliente `approval` (`srq-*`) que espera Sí/No |
| `clarify` | Pregunta por lotes (dos `qid`) y pregunta simple `{answer}` |
| `clarify3` | Lote de tres `qid` (choices + multi_select + texto libre), C7 |
| `browser` | Controlador de navegador: navigate + snapshot + click con `browser.controller.result` |
| `browser_off` | `browser.enabled=false`: todo `browser.controller.*` → 4403 (flag apagado) |
| `browser_cancel` | `browser_command` sin esperar + `browser.controller.cancel` del servidor |
| `browser_find_invoice` | F4: `tool.start` → registro del controlador → navigate+snapshot+click |
| `error` | `message.complete` con error; un prompt `boom` además recibe error JSON-RPC |
| `lento` | Turno largo con pausas: para Parar (`session.interrupt`) y `close_socket` |
| `rate_limited` | Todo login → 429 con `Retry-After` (pantalla de rate limit) |
| `renombra` | El servidor renombra el chat a mitad de turno (`session.title` + `sessions.changed`) |
| `request_cancel` | `request.cancel` por timeout de la `srq` o por `cancel_request` del guion |
| `sesiones` | Seeds viva (delete → 4023) y stored-only (borrable hasta `session.resume`) |
| `ticket_requerido` | `require_ws_ticket`: `/api/ws` sin ticket → cierre 4401 tras el upgrade |

Credenciales sintéticas por defecto: `usuario` / `mama` (cada guion puede
redefinirlas en `auth`). El login devuelve las cookies `hermes_session_at` /
`hermes_session_provider` y un `ticket` de un solo uso (30 s) abre el WS — en
modo dev el socket también entra sin ticket (identidad NO autenticada:
`browser.controller.*` → 4403) salvo que el guion ponga `require_ws_ticket`.

En tests JVM el gateway se empotra directamente — ver `FakeGatewayTest`:

```kotlin
val gateway = FakeGateway(FakeGatewayScript.load("hola_mundo"), requestedPort = 0)
gateway.start()   // puerto efímero en gateway.port
```

## Módulos (ROADMAP §1.1)

| Módulo | Tipo | Contenido |
|---|---|---|
| `:app` | Android app | `MainActivity`, flavors `dev`/`mama` |
| `:core-contract` | JVM puro | tipos generados del contrato OpenRPC (A3) |
| `:core-gateway` | JVM puro | JsonRpcChannel, GatewayClient, auth (M1) |
| `:core-controller` | JVM puro | lógica del controlador de navegador (M5) |
| `:feature-chat` | Android + Compose | Chats, Chat, aprobaciones, clarify |
| `:feature-browser` | Android + Compose | navegador visible (WebView + overlay) |
| `:feature-voice` | Android + Compose | STT/TTS |
| `:feature-settings` | Android + Compose | pantalla Conexión |
| `:testing` | JVM | FakeGateway, fixtures, builders |

## Checks

```bash
./gradlew assembleDevDebug          # compila
./gradlew testDevDebugUnitTest      # tests JVM (Robolectric incl.)
./gradlew ktlintCheck detekt lint   # estilo + análisis estático
```

- ktlint: `.editorconfig` en la raíz del área.
- detekt: `config/detekt.yml` (sobre la config por defecto).
- Android Lint: `config/lint.xml` compartido, `warningsAsErrors`.

## JS del snapshot (F1)

`core-controller/src/main/assets/hermes_snapshot.js` es el IIFE que la app
inyecta en el WebView (`window.__hermes`, formato §2.6 del ROADMAP). Como
`:core-controller` es JVM puro, el fichero se publica también en el classpath del
jar (`SnapshotScript.load()`). Se testea con Node+jsdom contra las fixtures de
`testing/fixtures/html/`:

```bash
cd core-controller/js && npm ci && npm test     # compara con *.expected.txt
npm run update                                  # regenera los .expected.txt
```

Los mismos fixtures se verifican en un WebView real con
`./gradlew :feature-browser:connectedDebugAndroidTest` (emulador).

## Flavors (ROADMAP §1.2)

| Flavor | `applicationId` | Notas |
|---|---|---|
| `dev` | `ai.hermes.mama.dev` | `fake_script`, cleartext a `10.0.2.2`, logs de red |
| `mama` | `ai.hermes.mama` | la usuaria; sin override ni cleartext |

## Release e instalación (J1)

**Para la usuaria** la guía paso a paso está en [`INSTALACION.md`](INSTALACION.md):
descargar `app-mama-release.apk` de la GitHub Release, permitir «instalar apps
desconocidas» en el navegador y abrir.

**Para quien mantiene el repo**, publicar una release es empujar un tag:

```bash
git tag android-v0.9.<n> && git push origin android-v0.9.<n>
```

El tag `android-v*` dispara `.github/workflows/release.yml`, que:

1. compila `:app:assembleMamaRelease` + `:app:bundleMamaRelease`
   (`versionCode` = `GITHUB_RUN_NUMBER`, `versionName` = `0.9.<run>` — §4);
2. verifica el APK con `apksigner verify` y el AAB con `bundletool validate`;
3. intenta subir el AAB a la **pista interna de Google Play** — sólo si existe
   el secreto `PLAY_SERVICE_ACCOUNT_JSON` y la firma release está configurada;
   `continue-on-error`: si Play rechaza la app, el resto sigue (§9.5);
4. adjunta **siempre** `app-mama-release.apk` y `app-mama-release.aab` a la
   GitHub Release del tag (fallback sideload).

### Firma (secretos de CI — NUNCA en el repo)

`signingConfigs.release` de `app/build.gradle.kts` lee variables de entorno:

| Variable | Contenido |
|---|---|
| `ANDROID_KEYSTORE_B64` | el `.jks` en base64 (`base64 -i mama-release.jks`) — CI lo materializa en `$RUNNER_TEMP` |
| `ANDROID_KEYSTORE_FILE` | alternativa: ruta a un `.jks` ya materializado (la usa CI; en local admite un keystore de prueba fuera del repo) |
| `ANDROID_KEYSTORE_PASSWORD` | contraseña del keystore |
| `ANDROID_KEY_ALIAS` | alias de la clave |
| `ANDROID_KEY_PASSWORD` | contraseña de la clave |
| `PLAY_SERVICE_ACCOUNT_JSON` | JSON de la cuenta de servicio de Play (opcional; sin él se omite la subida a Play) |

Si falta cualquiera de las cuatro de firma, `release` se firma con la clave de
**debug** (fallback documentado): el APK queda instalable por sideload pero no
es apto para Play. El keystore se genera una vez, fuera del repo:

```bash
keytool -genkeypair -v -keystore /tmp/mama-release.jks -alias mama \
  -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=Hermes Mama"
```

El `-dname` fijo evita el prompt interactivo (nombre real, organización…):
el CN del certificado sale en los logs públicos de CI
(`apksigner verify --print-certs`).
