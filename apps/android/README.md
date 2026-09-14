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
implementa el subconjunto del JSON-RPC de `hermes serve` que usa la app. El proceso
standalone llega con **B5** (`./gradlew :testing:run`, escucha en `0.0.0.0:8399`);
mientras tanto el mecanismo de override ya está cableado:

```bash
# 1. (cuando exista B5) arrancar el FakeGateway en el host:
./gradlew :testing:run --args="--script <guion>"

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
