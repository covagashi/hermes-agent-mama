# ROADMAP — "Hermes para mamá" (app Android nativa)

> Cliente Android minimalista, tipo WhatsApp, para hablar con un `hermes serve` remoto.
> Pensado para una persona mayor: pocas pantallas, letra grande, voz, botones Sí/No, y la
> posibilidad de **ver en su propia pantalla cómo Hermes navega** y consigue lo que ha pedido
> (p. ej. la factura PDF de una compra).
>
> Este documento es el contrato de trabajo para el swarm de agentes que implementa la app.
> Cada tarea es autocontenida: entradas, salidas, criterios de aceptación y tests. Las tareas
> del mismo hito sin dependencia entre sí pueden ejecutarse en paralelo.

Estado del documento: **v1.1 — 2026-09-14**. Todos los nombres de métodos, eventos y formas de
payload de la §2 se han verificado contra el código del repo en esa fecha (rutas indicadas).
Cambios v1.1: emulador de referencia **API 35** (era API 34); A1 documenta el arranque contra
FakeGateway; B5 añade `:testing:run` como proceso independiente; strings por pantalla (§4).

---

## 0. Decisiones cerradas (no se re-discuten)

| Tema | Decisión |
|---|---|
| Plataforma | Android nativo. Kotlin 2.x, Jetpack Compose + Material 3. `minSdk 29`, `targetSdk 35`. |
| Arquitectura | Single-Activity, MVVM, `StateFlow`, Compose Navigation, Hilt para DI. |
| Backend | `hermes serve` (JSON-RPC 2.0 sobre WebSocket en `/api/ws`). Sin lógica de agente en el cliente. |
| Red | OkHttp (WebSocket + HTTP) + kotlinx.serialization. Tipos generados del contrato OpenRPC. |
| Auth | Proveedor `basic` (usuario + contraseña) del dashboard → cookies de sesión → ticket WS. |
| Voz | En el móvil: `SpeechRecognizer` (dictar, `es-ES`) y `TextToSpeech` (leer respuestas). |
| Navegación visible (fase 1) | `WebView` in-app que implementa el protocolo `browser.controller` v1. |
| Descargas | Las hace el `WebView` en el móvil (carpeta Descargas). Si Hermes necesita el fichero, la app lo sube con `file.attach`. |
| Control del móvil (fase 2) | `AccessibilityService` que reutiliza el mismo protocolo de controlador. |
| Lectura de pantalla | (a) compatibilidad TalkBack; (b) botón "¿Qué pone aquí?" que envía a Hermes lo que hay en pantalla. |
| Distribución | Pista de pruebas internas de Google Play (AAB). Si Play no lo acepta → APK firmado, sideload. |
| Idioma UI | Español. |
| Repositorio | Público: cero secretos, cero datos personales (ver `AGENTS.md`). |

---

## 1. Arquitectura

```
┌────────────────────────── Android app (apps/android) ──────────────────────────┐
│                                                                                 │
│  ui/            Compose screens + ViewModels (Chats, Chat, Navegador, Ajustes)  │
│     │                                                                           │
│  domain/        casos de uso: SendMessage, LoadHistory, AnswerApproval, …       │
│     │                                                                           │
│  data/                                                                          │
│    gateway/     JsonRpcChannel ── GatewayClient (typed) ── EventBus (Flow)      │
│    auth/        BasicAuthSession (login, cookie jar cifrado, ws-ticket)         │
│    sessions/    SessionRepository (Room cache + red)                            │
│    attachments/ ImageAttacher, FileAttacher                                     │
│    controller/  BrowserController (WebView)  ·  DeviceController (A11y, fase 2) │
│    voice/       SpeechInput, SpeechOutput                                       │
│    settings/    ConnectionSettings (DataStore + EncryptedSharedPreferences)     │
│                                                                                 │
│  contract/      Generado: DTOs @Serializable + nombres de métodos/eventos       │
└────────────────────────────────────┬────────────────────────────────────────────┘
                                     │ wss://host/api/ws?ticket=…  (JSON-RPC 2.0)
┌────────────────────────────────────┴────────────────────────────────────────────┐
│  hermes serve (VPS)  ── sesiones, prompt.submit, eventos de streaming,          │
│                         approval/clarify, browser.controller.* (broker)         │
│  skills/conectores: Gmail, Google Workspace, web, ficheros…                     │
└─────────────────────────────────────────────────────────────────────────────────┘
```

### 1.1 Módulos Gradle

```
apps/android/
├── settings.gradle.kts, build.gradle.kts, gradle.properties, gradle/libs.versions.toml
├── config/            ktlint, detekt, lint baseline
├── app/               módulo aplicación (flavors: dev | mama)
├── core-contract/     tipos generados del OpenRPC (sin dependencias Android)
├── core-gateway/      JSON-RPC channel + GatewayClient + auth (JVM puro, testeable sin emulador)
├── core-controller/   lógica del controlador de navegador (snapshot builder, mapeo de acciones) — JVM puro
├── feature-chat/      pantallas Chats + Chat + Aprobaciones/Clarify
├── feature-browser/   pantalla Navegador (WebView + overlay)
├── feature-voice/     STT/TTS
├── feature-settings/  Conexión
└── testing/           FakeGateway (servidor WS de pruebas), fixtures, builders
```

`core-*` son módulos JVM puros (`kotlin("jvm")`): sus tests corren sin emulador y cubren el 80 %
de la lógica. Los `feature-*` son módulos Android con Compose.

### 1.2 Flavors

| Flavor | Uso | Diferencias |
|---|---|---|
| `dev` | desarrollo y CI | Pantalla de conexión siempre accesible, logs de red visibles (sin secretos), `usesCleartextTraffic` sólo para `10.0.2.2`/Tailscale, menú oculto "Diagnóstico". |
| `mama` | la usuaria | Pantalla de conexión sólo la primera vez (o mantener pulsado el logo 3 s), sin logs, sin diagnóstico, `network_security_config` sólo HTTPS. |

`applicationId`: `ai.hermes.mama` (`.dev` sufijo en `dev`).

---

## 2. Contrato con el backend (verificado en el repo)

### 2.1 Autenticación (proveedor `basic`)

Fuente: `hermes_cli/dashboard_auth/routes.py`, `plugins/dashboard_auth/basic/__init__.py`,
`hermes_cli/web_server_chat.py::_ws_credential`.

1. `POST {base}/auth/password-login`
   ```json
   {"provider": "basic", "username": "…", "password": "…", "next": ""}
   ```
   → `200 {"ok": true, "next": "/"}` y **cookies** de sesión (`Set-Cookie`, `HttpOnly`;
   nombres resueltos por `cookies.py::_resolved_name`, con prefijo `__Host-`/`__Secure-` en
   HTTPS). `401` credenciales inválidas; `404` proveedor no registrado; `429` rate-limit.
   La app **no** parsea los nombres de cookie: guarda todo el `CookieJar` tal cual.
2. `GET {base}/api/auth/me` → `{"user_id","email","display_name","provider","expires_at"}` —
   se usa como "Probar conexión". `401` → volver a login.
3. `POST {base}/api/auth/ws-ticket` (con cookies) → `{"ticket": "…", "ttl_seconds": 30}`.
   **Ticket de un solo uso, 30 s**: mintearlo inmediatamente antes de abrir el socket, uno por
   conexión (también en cada reconexión).
4. `wss://{host}/api/ws?ticket={ticket}` — el servidor sella en la conexión la identidad
   `{user_id, provider}` (necesaria para registrar el controlador de navegador, §2.6).
5. Refresco: la middleware renueva cookies transparentemente (TTL 12 h, refresh 30 días). Si
   `ws-ticket` devuelve `401` → repetir paso 1 con las credenciales guardadas; si vuelve a fallar
   → mostrar pantalla de conexión con mensaje humano.

> `?token=` (token de sesión legacy) sólo vale en binds loopback; en un `serve` público con auth
> se rechaza. No implementarlo.

### 2.2 Framing JSON-RPC sobre el WebSocket

Fuente: `tui_gateway/ws.py`, `apps/shared/src/json-rpc-channel.ts`.

- Frames de texto, un JSON por mensaje. Cliente→servidor: `{"jsonrpc":"2.0","id":N,"method":"…","params":{…}}`.
- Respuestas: `{"jsonrpc":"2.0","id":N,"result":{…}}` o `{"id":N,"error":{"code":-32601,"message":"…","data":…}}`.
- **Eventos** (notificaciones servidor→cliente): `{"method":"event","params":{"type":"message.delta","session_id":"…","seq":12,"payload":{…}}}`.
- **Peticiones servidor→cliente** (`id` es *string*, `method` ≠ `"event"`):
  `{"id":"req-…","method":"approval","params":{…}}`. El cliente responde
  `{"jsonrpc":"2.0","id":"req-…","result":{…}}` o error. Sin handler → responder `-32601`
  para no bloquear al backend. Tras una reconexión el backend puede re-entregar peticiones
  abiertas con el mismo `id`: responder dos veces es no-op.
- Nada más aceptar, el servidor envía el evento `gateway.ready`
  (`{skin, change_events, replay_epoch, heartbeat?}`).
- **Heartbeat**: cada 15 s enviar `{"id":N,"method":"gateway.ping","params":{}}`; si no llega
  ninguna respuesta en 45 s → cerrar socket y reconectar con backoff (1 s, 2 s, 4 s… máx 30 s,
  jitter ±20 %). Máx. 8 pings pendientes.
- Timeout por defecto de una petición: 120 s.
- Los textos pueden contener emoji/plano astral; usar UTF-8 estricto.

### 2.3 Métodos RPC que usa la app (subconjunto de los 215 del contrato)

Fuente: `apps/shared/src/gateway-contract.openrpc.json` (`components.schemas`).

| Método | Params (esenciales) | Result (esencial) | Uso en la app |
|---|---|---|---|
| `gateway.ping` | `{}` | `{}` | heartbeat |
| `gateway.capabilities` | `{}` | capacidades | diagnóstico (`dev`) |
| `session.list` | `{limit?, include_hidden:false}` | `{sessions:[{id, resolved_id?, title?, preview?, started_at?, message_count?, source?}]}` | lista de chats |
| `session.create` | `{title?, source:"android"}` | `{session_id, stored_session_id, message_count, messages:[TranscriptMessage], info}` | nuevo chat |
| `session.resume` | `{session_id: STORED_ID, source:"android"}` | `{session_id (runtime), stored_session_id, messages, info}` | abrir chat existente |
| `session.history` | `{session_id}` | `{count, messages:[TranscriptMessage]}` | recargar transcript |
| `session.title` | `{session_id, title}` | — | renombrar |
| `session.delete` | `{session_id: STORED_ID}` | — | borrar (con confirmación) |
| `session.interrupt` | `{session_id}` | — | botón **Parar** |
| `session.events.since` | `{session_id, last_seen}` | eventos perdidos | rellenar huecos tras reconexión |
| `prompt.submit` | `{session_id, text, surface:"android", voice_context?}` | `{status?}` | enviar mensaje |
| `image.attach_bytes` | `{session_id, content_base64, filename}` | `{attached, name, width, height…}` | foto (se adjunta al **siguiente** `prompt.submit`) |
| `file.attach` | `{session_id, data_url, name}` | `{attached, name, path, ref_path, ref_text}` | PDF/otros → incluir `ref_text` en el texto del prompt |
| `approval.pending` | `{session_id}` | `{approvals:[PendingApproval]}` | re-sincronizar tras reconexión |
| `approval.respond` | `{session_id, request_id, choice}` | — | Sí / No (ver §2.5) |
| `request.answer` | `{id, result}` | — | responder `clarify` cuando llegó como replay |
| `browser.controller.register` | ver §2.6 | `{scope}` | navegador visible |
| `browser.controller.result` | ver §2.6 | `{accepted}` | navegador visible |
| `browser.controller.heartbeat` | `{session_id}` | `{ok}` | navegador visible |
| `browser.controller.detach` | `{session_id}` | — | al cerrar la pantalla |

**Identificadores de sesión**: `session.list` devuelve el *stored id*; `session.resume` /
`session.create` devuelven el *runtime* `session_id` que hay que usar en `prompt.submit`,
eventos, approvals y el controlador. La app guarda ambos por chat.

`TranscriptMessage` = `{role:"user"|"assistant"|"tool"|…, text?, timestamp?, row_id?, display_kind?, name?, args?, reasoning?}`.
Para la usuaria sólo se pintan `user` y `assistant` (y un resumen de `tool` como "🔎 buscando…").

### 2.4 Eventos que la app consume

Fuente: `apps/shared/src/gateway-contract.generated.ts` (`GATEWAY_EVENT_TYPES`).

| `type` | `payload` | Qué hace la app |
|---|---|---|
| `gateway.ready` | `{skin, change_events, replay_epoch}` | marcar conectado; si `replay_epoch` cambió → `session.history` completo |
| `message.start` | `{}` | crear burbuja de asistente vacía + indicador "escribiendo…" |
| `message.delta` | `{text, rendered?}` | append streaming |
| `message.complete` | `{text?, status?, error?, partial?, failure_reason?}` | cerrar burbuja; si `error` → burbuja de error amable; disparar TTS si está activo |
| `thinking.delta`, `reasoning.delta` | — | **ignorar** (no mostrar razonamiento) |
| `tool.start` | `{tool_id, name, context?, preview?}` | chip de estado ("🌐 abriendo la web…", "📧 leyendo correo…"), mapeo por nombre en §5 |
| `tool.complete` | `{tool_id, name, summary?}` | quitar chip |
| `status.update` | `{kind, text}` | texto de estado bajo el título |
| `session.title` | `{session_id, title}` | actualizar título |
| `sessions.changed` | — | refrescar `session.list` |
| `session.info` | `SessionLiveInfo{running, title, stored_session_id…}` | estado ocupado/libre |
| `error` / `notice` | `{message}` | snackbar (texto humano; nunca stack traces) |
| `request.cancel` | `{id, method, reason}` | cerrar tarjeta de aprobación/clarify con ese `id` |
| `voice.transcript` | — | no se usa (STT es local) |
| `browser.controller.command` | ver §2.6 | ejecutar en el WebView |
| `browser.controller.cancel` | `{command_id}` | abortar comando en curso |
| `browser.progress` | `{message, level}` | texto del overlay del navegador |
| resto | — | ignorar sin fallar (el cliente debe tolerar tipos desconocidos) |

### 2.5 Peticiones servidor→cliente que la app responde

| `method` | `params` | `result` que devuelve la app |
|---|---|---|
| `approval` | `{session_id, request_id, command?, description?, choices?:[…], tool_name?, allow_session?, allow_permanent?}` | `{"choice": "once" \| "deny"}` — la app **sólo** ofrece Sí (`once`) / No (`deny`); nunca `always`/`session`. Se muestra `description` (o `command` si no hay) en lenguaje llano. |
| `clarify` | `{session_id, question?, choices?, multi_select?, questions?:[{qid?, question, choices?…}]}` | `{"answer": "…"}` para una pregunta; `{"answers": {qid: "…"}}` para lote. Con `choices` → botones grandes; sin → campo de texto + micrófono. |
| `secret`, `sudo`, `vault.*`, `mcp.setup`, `preview.*`, `terminal.read`, `window.read`, `tour` | — | responder error `-32601` (no soportado) y mostrar "Hermes necesita algo que esta app no puede dar; pídeselo a [tu hijo/a]" — texto configurable en `strings.xml` como `helper_name`. |

### 2.6 Protocolo del controlador de navegador (fase 1)

Fuente: `gateway/browser_control_broker.py`, `tui_gateway/methods_browser_control.py`,
`tools/browser_extension_router.py`, `tools/browser_tool.py`.

**Requisitos del servidor**: `browser.extension_control.enabled: true` en `config.yaml`; el
socket debe venir autenticado (identidad no interna) — cumplido con §2.1.

**Registro** (una vez por sesión de chat, al abrir la pantalla Navegador o al primer
`tool.start` con `name` que empiece por `browser_`):

```json
{"method":"browser.controller.register","params":{
  "session_id":"<runtime session_id>",
  "controller_id":"android-<installation-uuid>",
  "browser_profile_id":"mama-webview",
  "protocol_version":1,
  "capabilities":["controller.noop","browser_navigate","browser_snapshot","browser_click",
                  "browser_type","browser_scroll","browser_back","browser_press",
                  "browser_screenshot","browser_tabs","browser_tab_activate"]
}}
```
→ `{"scope":{principal_id, profile_id, session_id, controller_id, browser_profile_id, transport_family, capabilities}}`.
Error `4403` si el flag está apagado, la versión no es `1` o no hay identidad. Heartbeat:
`browser.controller.heartbeat {session_id}` cada 20 s mientras la pantalla esté activa.
`browser.controller.detach {session_id}` al salir. Si el socket se cae, hay que **volver a
registrar** tras reconectar.

**Comando** (evento `browser.controller.command`):
```json
{"type":"browser.controller.command","session_id":"…","payload":{
  "command_id":"a1b2…","action":"browser_click","arguments":{"ref":"@e5"},
  "controller_id":"…","browser_profile_id":"…","tool_call_id":"…"}}
```
Timeout del broker: **30 s** por comando → responder antes. Cancelación: evento
`browser.controller.cancel {command_id}`.

**Resultado**:
```json
{"method":"browser.controller.result","params":{
  "session_id":"…","command_id":"a1b2…","ok":true,"result":"<string JSON>"}}
```
`result` se entrega **tal cual** al modelo como salida de la herramienta: debe ser un **string
JSON** con la misma forma que devuelven las herramientas locales (`tools/browser_tool.py`):

| `action` | `arguments` | `result` (string JSON) |
|---|---|---|
| `controller.noop` | `{}` | `{"success":true}` |
| `browser_navigate` | `{url}` | `{"success":true,"url":<final>,"title":…,"snapshot":<compacto>,"element_count":N}` |
| `browser_snapshot` | `{full?:bool}` | `{"success":true,"snapshot":…,"element_count":N}` |
| `browser_click` | `{ref:"@eN"}` | `{"success":true,"clicked":"@eN"}` |
| `browser_type` | `{ref, text}` | `{"success":true,"typed":<text>,"element":"@eN"}` |
| `browser_scroll` | `{direction:"up"\|"down"}` | `{"success":true,"scrolled":"down"}` |
| `browser_back` | `{}` | `{"success":true,"url":…}` |
| `browser_press` | `{key:"Enter"\|"Tab"\|"Escape"\|"ArrowDown"…}` | `{"success":true,"pressed":"Enter"}` |
| `browser_screenshot` | `{}` | `{"success":true,"image_base64":<png>,"width":W,"height":H}` |
| `browser_tabs` | `{}` | `{"success":true,"tabs":[{"id":"1","url":…,"title":…,"active":true}]}` (una sola pestaña) |
| `browser_tab_activate` | `{id}` | `{"success":true}` |
| error | — | `ok:false`, `result:{"success":false,"error":"<motivo humano en inglés>"}` |

**Formato del snapshot** (idéntico al de `agent-browser`, que es lo que el modelo ya sabe leer):
```
- heading "Mis pedidos" [ref=e1] [level=1]
- link "Pedido 403-1234567" [ref=e2]
  - text "Entregado el 3 de septiembre"
- button "Descargar factura" [ref=e3]
- textbox "Buscar" [ref=e4]
- combobox "Ordenar por" [ref=e5]
- checkbox "Recordarme" [ref=e6] [checked]
```
- Una línea por nodo, indentación de 2 espacios por nivel de árbol.
- `role` según ARIA/HTML (`heading, link, button, textbox, combobox, checkbox, radio, listitem,
  img, text, region, navigation, main, form, cell, row, table, option, menuitem, dialog`).
- `name` = nombre accesible (aria-label → label asociado → texto visible → alt/title → placeholder).
- `[ref=eN]` sólo en elementos interactivos o con nombre; N incrementa por documento; el mismo
  nodo conserva su ref entre snapshots mientras la página no cambie (mapa `WeakMap<Element,int>`).
- Compacto (`full=false`): sólo interactivos + headings + texto corto de contexto (≤ 80 chars).
  Completo: todo el texto visible.
- Límite 15 000 chars: truncar por líneas y añadir `… [truncated]` (el servidor ya lo hace, pero
  evita frames de varios MB).
- Ocultar contenido de `<input type=password>` (`textbox "Contraseña" [ref=e7] [password]`).
- `pending_dialogs`: si hay `alert/confirm/prompt` JS abierto, la app lo resuelve automáticamente
  (aceptar `alert`, cancelar `confirm/prompt`) y lo refleja en el snapshot como
  `- dialog "…" [auto-dismissed]`.

**Restricción verificada**: `browser_artifact_download/upload` requiere un *artifact store* que
sólo cablea `gateway/platforms/api_server.py`, **no** `hermes serve`. No anunciar esas
capacidades. Las descargas se gestionan en el móvil (§5 hito M6).

### 2.7 Configuración del servidor de pruebas (sin datos reales)

```yaml
# ~/.hermes/config.yaml (host de pruebas)
dashboard:
  basic_auth:
    username: usuario
    password_hash: "scrypt$16384$8$1$…"   # python -c "from plugins.dashboard_auth.basic import hash_password; print(hash_password('…'))"
    secret: "<aleatorio, para que las sesiones sobrevivan reinicios>"
browser:
  extension_control:
    enabled: true
```
Arranque: `hermes serve --host 0.0.0.0 --port 9119` (detrás de Tailscale o de un proxy HTTPS).
Para el emulador: host `10.0.2.2`. Para CI (§7.4): `hermes serve --host 127.0.0.1` en el runner.

---

## 3. Experiencia de usuario

Mockups: `apps/android/design/mockups/*.dc.html` (HTML plano, ábrelos en un navegador; `canvas.json`
indica el orden). Son la referencia visual de las tareas C1–C7, F4, G1 y H2: tipografía Atkinson
Hyperlegible, paleta cálida con un solo acento verde, botones ≥ 56 dp. Tokens en `design/README.md`.

Pantallas (todas en español, letra grande):

1. **Conexión** (sólo primera vez / oculta): dirección del servidor, usuario, contraseña,
   botón "Probar" → "✅ Conectado como …". Opción "Leer las respuestas en voz alta" (on/off).
2. **Chats**: lista tipo WhatsApp (avatar con emoji, título, última línea, hora), botón grande
   "＋ Nuevo chat". Deslizar → borrar (con confirmación). Estado de conexión como una franja
   discreta arriba sólo cuando falla ("Sin conexión. Reintentando…").
3. **Chat**: burbujas, indicador "Hermes está escribiendo…", chip de actividad ("🌐 Buscando en
   la web…"), barra inferior: 📎 · campo de texto · 🎤 (mantener para hablar) / ➤. Cada burbuja de
   Hermes tiene 🔊. Botón **Parar** visible mientras Hermes trabaja.
4. **Tarjeta Sí/No** (aprobación): sobre el chat, dos botones enormes.
5. **Pregunta** (clarify): opciones como botones o campo con micrófono.
6. **Navegador**: pantalla completa con WebView; barra superior "Hermes está … (texto de
   `browser.progress`)" + botón **Parar**; barra inferior "Volver al chat". La usuaria puede tocar
   el WebView (p. ej. para meter su contraseña); mientras Hermes ejecuta un comando se muestra
   un velo semitransparente con "Un momento…".
7. **Documento listo**: hoja inferior con "📄 factura.pdf — Abrir · Compartir · Enviar a Hermes".
8. **¿Qué pone aquí?** (fase 2): botón flotante que captura lo que hay en pantalla y lo manda
   al chat con "Explícame qué pone aquí".

Reglas: texto de chat 18–20 sp, títulos 22 sp, objetivos táctiles ≥ 56 dp, contraste ≥ 4.5:1,
tema claro por defecto (respeta el tema del sistema), sin gestos ocultos salvo los estándar de
Android, nada de tecnicismos en pantalla ("sesión" → "chat", "gateway" → "Hermes").

---

## 4. Estructura, versiones y convenciones

- Gradle 8.9+, AGP 8.7+, Kotlin 2.0+, Compose BOM 2025.x, Material3, Navigation-Compose,
  Hilt, kotlinx.serialization 1.7+, kotlinx.coroutines 1.9+, OkHttp 4.12+, Room 2.6+, DataStore
  1.1+, security-crypto 1.1+, Coil (imágenes), `androidx.webkit`.
- Tests: JUnit 5 + kotlin-test, Turbine (Flows), MockK, OkHttp `MockWebServer` (WS), Robolectric
  (donde haga falta Android en JVM), Compose UI Test, Roborazzi (screenshots), Espresso-A11y
  (`AccessibilityChecks.enable()`).
- Lint: ktlint 1.x, detekt 1.23+ (configs en `config/`), Android Lint con `warningsAsErrors` en CI.
- Versionado: `versionName` = `0.<hito>.<n>`; `versionCode` = número de run de CI.
- Paquete raíz: `ai.hermes.mama`.
- Strings: todos en `res/values/strings*.xml` (es), **un fichero por pantalla**
  (`strings_chat.xml`, `strings_chats.xml`, …) para evitar conflictos entre PRs paralelos.
  Nada hardcodeado en Compose.
- Logs: `Timber`; en `mama` el árbol sólo registra `WARN+` y **redacta** cookies, tickets y
  cuerpos de mensajes.

---

## 5. Hitos y tareas

Formato de cada tarea: **ID · título** — dependencias · entregables · criterios de aceptación ·
tests. "JVM" = test unitario sin emulador; "Inst" = instrumentado en emulador.

### M0 — Scaffold y CI

**A1 · Proyecto Gradle multi-módulo** — deps: ninguna
- Entregables: estructura de §1.1, `libs.versions.toml`, flavors `dev`/`mama`, `.gitignore`
  (keystores, `local.properties`), `README.md` con "cómo compilar en 3 comandos" **y el comando
  exacto para arrancar la app contra el FakeGateway**:
  `./gradlew :app:installDevDebug` +
  `adb shell am start -n ai.hermes.mama.dev/ai.hermes.mama.MainActivity --es fake_script <guion>`
  (el componente va en FQCN: el `applicationId` lleva `.dev` pero la clase vive en el
  namespace `ai.hermes.mama`; la forma corta `.MainActivity` NO resuelve).
  Mecanismo (sólo flavor `dev`): el extra `fake_script` hace que la app use como endpoint
  `ws://10.0.2.2:8399` (FakeGateway standalone de B5) en lugar de las credenciales guardadas;
  con la app vacía de A1 basta el override del endpoint — el guion real llega con B5.
- Aceptación: `./gradlew assembleDevDebug` compila una app vacía con `MainActivity` Compose que
  muestra "Hermes". `./gradlew lint ktlintCheck detekt` en verde.
- Tests: `app/src/test/…/SmokeTest.kt` (JVM) que arranca `MainActivity` con Robolectric.

**A2 · CI (GitHub Actions)** — deps: A1
- Entregables: `.github/workflows/android.yml`: jobs `lint`, `unit` (JVM, todos los módulos),
  `instrumented` (emulador API 35, `reactivecircus/android-emulator-runner`), `build`
  (APK `mama` release firmado si existen los secretos, si no `debug`), `contract` (§A3).
  Caché de Gradle. Artefactos: APK/AAB + informes de tests + capturas de Roborazzi.
- Aceptación: pipeline verde en un PR vacío; el APK aparece como artefacto.
- Tests: el propio pipeline.

**A3 · Generador de tipos desde OpenRPC** — deps: A1
- Entregables: `scripts/gen_android_contract.py` (Python 3.11, sin deps externas) que lee
  `apps/shared/src/gateway-contract.openrpc.json` y escribe
  `apps/android/core-contract/src/main/kotlin/ai/hermes/mama/contract/Generated.kt`:
  `object RpcMethods` (constantes de nombre), `object EventTypes`, `object ServerRequests`, y
  `@Serializable data class` para los schemas de §2.3–2.6 (lista explícita en el script
  `SCHEMAS = [...]`; `anyOf [T, null]` → `T? = null`; `additionalProperties:true` → campo extra
  `JsonObject`). Cabecera "GENERATED — do not edit".
- Aceptación: `python scripts/gen_android_contract.py --check` sale 0 si el fichero está al
  día, 1 si no; job `contract` de CI lo ejecuta. El módulo compila.
- Tests: `tests/scripts/test_gen_android_contract.py` (pytest, en la suite Python del repo):
  genera a un tmp y comprueba que (a) cada schema de `SCHEMAS` produce una clase, (b) opcionales
  → nullable con default, (c) `--check` detecta desactualización. JVM:
  `GeneratedRoundTripTest` deserializa fixtures JSON reales (`testing/fixtures/*.json`) a cada
  DTO y vuelve a serializar sin perder campos requeridos.

### M1 — Núcleo de red (módulo `core-gateway`, JVM puro)

**B1 · `JsonRpcChannel`** — deps: A3
- Entregables: clase que, dada una `Transport { send(text); incoming: Flow<String>; close() }`,
  ofrece `suspend fun call(method, params: JsonElement): JsonElement`, `events: SharedFlow<GatewayEvent>`,
  `serverRequests: SharedFlow<ServerRequest>` (con `respond(result)` / `fail(code,msg)`
  idempotentes), heartbeat `gateway.ping` (15 s / 45 s), timeouts (120 s), ids incrementales.
  Frames desconocidos → log, nunca excepción.
- Aceptación: implementa §2.2 al completo.
- Tests (JVM, con transport falso): respuesta OK; error JSON-RPC → `JsonRpcException(code,
  data)`; timeout; evento decodificado con `seq`; server request sin handler → `-32601`
  automático; `respond` dos veces = un solo frame; heartbeat: sin pong en 45 s → `onDead`;
  frame malformado no rompe el canal; 1 000 llamadas concurrentes no mezclan ids.

**B2 · `OkHttpTransport` + reconexión** — deps: B1
- Entregables: `WebSocketTransport(url, cookieJar)` sobre OkHttp; `ConnectionManager` con
  estados `Disconnected/Connecting/Connected/Reconnecting(attempt)`, backoff exponencial con
  jitter, `NetworkCallback` de Android (opcional, en `feature`), y hook `onBeforeConnect` para
  mintear el ticket (B3). Tras reconectar: emitir `Reconnected(replayEpoch)`.
- Aceptación: sobrevive a cortar la red 60 s y vuelve solo; nunca dos sockets abiertos.
- Tests (JVM, `MockWebServer` con `WebSocketListener`): conexión, cierre por el servidor →
  reconexión con backoff (`TestScope` + `advanceTimeBy`), ticket nuevo por intento, cierre
  explícito no reconecta.

**B3 · `BasicAuthSession`** — deps: B2
- Entregables: `login(base, user, pass)`, `me()`, `wsTicket()`, `wsUrl(ticket)`; `CookieJar`
  persistente cifrado (EncryptedSharedPreferences, interfaz `SecureStore` para poder falsear en
  JVM); manejo de `401` en `ws-ticket` → re-login automático una vez.
- Aceptación: flujo §2.1 completo; nunca se loguea el valor de una cookie o ticket.
- Tests (JVM, `MockWebServer`): login OK guarda cookies; 401 → `InvalidCredentials`; 404 →
  `ProviderMissing`; 429 → `RateLimited(retryAfter)`; `wsTicket` reintenta login tras 401 y
  falla con `SessionExpired` si el segundo también falla; `https` → URL `wss`; `http` → `ws`
  sólo si el host es loopback/`10.0.2.2` o flavor `dev`.

**B4 · `GatewayClient` tipado** — deps: B1, A3
- Entregables: funciones `suspend` por método de §2.3 con DTOs generados; `events` filtrados
  por `session_id`; `serverRequests` tipadas (`ApprovalRequest`, `ClarifyRequest`,
  `UnsupportedRequest`).
- Aceptación: ningún string de método fuera de `RpcMethods`.
- Tests (JVM): cada método serializa exactamente los params esperados (golden JSON en
  `testing/fixtures/rpc/*.json`) y deserializa el result de fixture; `UnsupportedRequest`
  responde `-32601`.

**B5 · `FakeGateway` (módulo `testing`)** — deps: B1
- Entregables: servidor WS en JVM (`MockWebServer` o Ktor CIO) que implementa `gateway.ping`,
  `session.*`, `prompt.submit` (responde con un guion configurable de eventos: `message.start`,
  N `message.delta`, `message.complete`), `approval` (petición servidor→cliente), `clarify`,
  `browser.controller.*` (emite comandos y captura resultados), más endpoints HTTP de auth
  (`password-login`, `me`, `ws-ticket`). Guiones en JSON (`testing/scripts/*.json`).
  Además arranca como **proceso independiente**: `./gradlew :testing:run` escucha en
  `0.0.0.0:8399` (el emulador lo alcanza vía `10.0.2.2`), guion seleccionable por argumento.
- Aceptación: usable desde tests JVM e instrumentados (se arranca en `@Before`) y como proceso.
- Tests: `FakeGatewayTest` — un cliente B4 completa el guion "hola → 3 deltas → complete".

**B6 · `SessionRepository` + Room** — deps: B4
- Entregables: entidad `ChatEntity(storedId, runtimeId?, title, preview, startedAt, messageCount)`,
  `MessageEntity(rowId, chatId, role, text, ts, kind)`; `refreshList()`, `open(storedId)`
  (resume → runtime id), `history()`, `create(title)`, `delete`, `rename`; escritura
  incremental de deltas en memoria y persistencia al `message.complete`.
- Aceptación: abrir la app sin red muestra la última lista y transcripts cacheados.
- Tests (JVM, Room in-memory con Robolectric): lista se actualiza desde `session.list`;
  `open` mapea stored→runtime; `sessions.changed` dispara refresh; borrado local + remoto.

### M2 — UI de chat (feature-chat, feature-settings)

**C1 · Sistema de diseño** — deps: A1
- Entregables: `MamaTheme` (M3, tipografía escalada: body 18 sp, title 22 sp, label 16 sp;
  paleta clara/oscura; `dynamicColor=false`), componentes: `BigButton`, `ChatBubble`,
  `ActivityChip`, `TopBanner`, `EmptyState`. `Preview`s por componente.
- Aceptación: todos los componentes ≥ 56 dp de alto, contraste AA verificado.
- Tests: Roborazzi screenshot por componente (claro/oscuro, fuente 1.0× y 1.3×);
  `AccessibilityChecks` en Compose UI Test.

**C2 · Pantalla Conexión** — deps: B3, C1
- Entregables: formulario (servidor, usuario, contraseña con ojo), "Probar", "Guardar",
  "Leer respuestas en voz alta", mensajes de error humanos (sin códigos HTTP). En `mama`: se
  abre sólo si no hay credenciales o con pulsación larga (3 s) sobre el logo en Chats.
- Aceptación: tras guardar, `me()` OK → navega a Chats.
- Tests: Inst con `FakeGateway`: credenciales correctas → Chats; incorrectas → mensaje "Usuario
  o contraseña incorrectos"; servidor caído → "No encuentro a Hermes. ¿Está encendido el
  servidor?"; JVM: `ConnectionViewModelTest` estados.

**C3 · Pantalla Chats** — deps: B6, C1
- Entregables: lista, "＋ Nuevo chat" (crea con título "Chat de <fecha>"; Hermes lo renombra
  vía `session.title`), swipe para borrar con diálogo, franja de conexión, pull-to-refresh.
  Emoji de avatar derivado del título (mapa: correo→📧, compra/pedido/factura→🧾, receta→🍲,
  médico/cita→🩺, familia→👨‍👩‍👧, otro→💬).
- Tests: Inst: crear chat navega al chat; borrar pide confirmación; sin red muestra caché y
  franja; JVM: `ChatsViewModelTest`.

**C4 · Pantalla Chat (transcript + streaming)** — deps: B6, C1
- Entregables: `LazyColumn` invertida, burbujas usuario/Hermes, Markdown mínimo (negrita,
  listas, enlaces, bloques de código como texto plano monoespaciado), indicador "escribiendo",
  chip de actividad por `tool.start` (mapa nombre→texto: `web_search`→"🔎 Buscando en
  internet", `browser_*`→"🌐 Navegando", `read_file`/`terminal`→"📂 Mirando archivos", `email`/
  `gmail`→"📧 Leyendo el correo", `send_email`→"📤 Enviando correo", otro→"⏳ Trabajando…"),
  botón **Parar** (`session.interrupt`), auto-scroll salvo que la usuaria haya subido.
- Aceptación: 500 mensajes cacheados scrollean a 60 fps en emulador API 35.
- Tests: JVM `ChatViewModelTest` con guion de `FakeGateway` (deltas se concatenan en orden de
  `seq`; `message.complete` con `error` produce burbuja de error; `Reconnected` recarga
  historial y rellena con `session.events.since`); Inst: streaming visible; Roborazzi del chat.

**C5 · Composer (texto + adjuntos + micro)** — deps: C4
- Entregables: campo multilínea, ➤ (deshabilitado si vacío), 📎 (galería/cámara/archivo vía
  `ActivityResultContracts`), 🎤 con "mantener para hablar" (usa D1), cola de adjuntos con
  miniaturas y ✕; al enviar: primero `image.attach_bytes`/`file.attach`, después
  `prompt.submit` (con `ref_text` de los ficheros añadido al final del texto).
- Tests: JVM: orden de llamadas adjunto→prompt (fixture), texto vacío no envía; Inst: elegir
  imagen del `TestContentProvider` muestra miniatura.

**C6 · Aprobaciones (Sí/No)** — deps: B4, C1
- Entregables: `ApprovalCard` modal sobre el chat con `description`/`command` en texto llano
  (humanizador: `send_email` → "Hermes quiere enviar un correo a …"), botones "Sí, adelante"
  / "No". Re-sync con `approval.pending` al reconectar. `request.cancel` cierra la tarjeta.
- Tests: JVM `ApprovalControllerTest` (respuesta `once`/`deny`, replay no duplica tarjeta,
  cancel cierra); Inst: tarjeta visible y pulsable con TalkBack (`AccessibilityChecks`).

**C7 · Preguntas (clarify)** — deps: C6
- Entregables: `ClarifyCard`: con `choices` → botones; `multi_select` → chips + "Listo"; sin
  → campo + 🎤; lote `questions` → una a una con progreso "1 de 3".
- Tests: JVM: mapeo a `{answer}` / `{answers}`; Inst: flujo de 3 preguntas.

**C8 · Navegación, ciclo de vida y servicio en primer plano** — deps: C2–C7
- Entregables: `NavHost` (Conexión ↔ Chats → Chat → Navegador), `ForegroundService` con
  notificación "Hermes está trabajando…" mientras una sesión tiene `running=true` (mantiene el
  socket cuando la app va a segundo plano ≤ 10 min), `Doze`-friendly (sin wakelocks
  permanentes). Deep link `hermes-mama://chat/<storedId>` desde la notificación.
- Tests: Inst: enviar mensaje, ir a Home, volver → la respuesta está; JVM: máquina de estados
  del servicio.

### M3 — Voz (feature-voice)

**D1 · `SpeechInput`** — deps: A1
- Entregables: wrapper de `SpeechRecognizer` (`es-ES`, parciales en vivo, `EXTRA_PREFER_OFFLINE`
  si hay modelo), permiso `RECORD_AUDIO` con explicación, estados `Idle/Listening(partial)/
  Done(text)/Error(kind)`; fallback a `RecognizerIntent` si el dispositivo no tiene servicio.
- Tests: JVM con `FakeRecognizer`; Inst: permiso denegado → mensaje y el botón queda en
  "Escribir".

**D2 · `SpeechOutput`** — deps: A1
- Entregables: `TextToSpeech` (`es-ES`), cola por burbuja, `stop()` al pulsar Parar o al
  empezar a dictar, limpieza de Markdown antes de leer, respeta "Leer en voz alta" de ajustes
  y el modo silencio del móvil (`AudioManager`).
- Tests: JVM: `MarkdownToSpeechText` (elimina `**`, enlaces → texto, listas → pausas);
  `FakeTts` recibe el texto al `message.complete` sólo si el ajuste está activo.

### M4 — Adjuntos (data/attachments)

**E1 · `ImageAttacher`** — deps: B4
- Entregables: lee `Uri`, corrige orientación EXIF, reduce a ≤ 1 600 px lado mayor y ≤ 1 MB
  JPEG, base64 → `image.attach_bytes`.
- Tests: JVM (Robolectric) con imágenes de fixture: tamaño de salida, EXIF rotado, PNG con
  transparencia → PNG.

**E2 · `FileAttacher`** — deps: B4
- Entregables: `data_url` (`data:<mime>;base64,…`) para PDF/DOC/TXT ≤ 8 MB, error humano si
  mayor; devuelve `ref_text` para el composer.
- Tests: JVM: PDF fixture → `data_url` válido; 9 MB → `TooLarge`.

### M5 — Navegador visible (core-controller + feature-browser)

**F1 · `SnapshotBuilder` (JS + Kotlin)** — deps: A1
- Entregables: `assets/hermes_snapshot.js` (IIFE sin dependencias, expone
  `window.__hermes = {snapshot(full), click(ref), type(ref,text), press(key), scroll(dir)}`) que
  genera el formato §2.6; parser Kotlin `SnapshotResult(text, refCount)`.
- Aceptación: sobre las páginas de fixture (`testing/fixtures/html/*.html`: tienda con listado
  de pedidos, login, formulario con select/checkbox, página con iframe same-origin, página con
  contraseña) produce exactamente los snapshots `*.expected.txt`.
- Tests: JVM con **jsdom vía Node** (`npm test` en `core-controller/js`, incluido en CI) para el
  JS; Kotlin: parser/trunc. Inst: los mismos fixtures en un `WebView` real (Robolectric no
  ejecuta JS de WebView).

**F2 · `WebViewController`** — deps: F1, B4
- Entregables: clase que recibe `BrowserCommand` y ejecuta sobre un `WebView` en el hilo
  principal: `navigate` (espera `onPageFinished` + 500 ms de calma de red o 10 s máx, después
  snapshot compacto), `click`/`type`/`press`/`scroll` (vía `evaluateJavascript`, después 300 ms
  de espera y comprobación de navegación), `back`, `screenshot` (`PixelCopy`/`draw` → PNG
  base64 ≤ 1 200 px), `tabs`/`tab_activate`. Timeout interno 25 s → `ok:false`. Cancelación por
  `command_id`. Resultado como string JSON de §2.6.
- Tests: JVM: mapeo comando→JS y timeouts con `FakeWebView` (interfaz); Inst: `FakeGateway`
  envía `navigate`→`snapshot`→`click` sobre las páginas de fixture servidas por `MockWebServer`
  y comprueba los resultados.

**F3 · `ControllerSession` (registro, heartbeat, reconexión)** — deps: F2, B2
- Entregables: registra al abrir la pantalla Navegador o al primer `tool.start` `browser_*`
  de la sesión (en ese caso navega automáticamente a la pantalla Navegador con una animación
  y un aviso "Hermes va a usar el navegador"), heartbeat 20 s, re-registro tras `Reconnected`,
  `detach` al salir. Un solo controlador activo por app.
- Tests: JVM con `FakeGateway`: registro con capabilities exactas; error 4403 → estado
  `ServerNotEnabled` con texto "El servidor no tiene activado el navegador compartido"; caída de
  socket → re-registro.

**F4 · Pantalla Navegador** — deps: F3, C1
- Entregables: WebView a pantalla completa, barra superior con `browser.progress` (o "Hermes
  está navegando…") + **Parar** (`session.interrupt` + cancelar comando), velo "Un momento…"
  durante un comando (la usuaria puede tocar cuando no hay comando en curso: p. ej. escribir su
  contraseña; ese contenido nunca sale del móvil salvo en snapshots, donde los campos de
  contraseña se ocultan), botón "Volver al chat" (el controlador sigue registrado en segundo
  plano hasta cerrar el chat), desactivar zoom accidental, `WebView` con cookies persistentes
  (`CookieManager` + `setAcceptThirdPartyCookies` false), `safeBrowsingEnabled`.
- Tests: Inst: recorrido completo con `FakeGateway` (guion "buscar factura"): se abre el
  navegador solo, se ven 3 comandos, Parar interrumpe; Roborazzi del overlay.

### M6 — Documentos ("Abrir factura")

**G1 · Descargas del WebView** — deps: F4
- Entregables: `DownloadListener` → `DownloadManager` a `Downloads/Hermes/` (`MediaStore` en
  API 29+), con cookies del WebView y `User-Agent`; detección de `Content-Disposition`;
  hoja inferior "📄 <nombre> — Abrir · Compartir · Enviar a Hermes" al terminar; notificación
  del sistema. Al descargar, la app manda a la sesión un mensaje de sistema visible
  "📄 Se ha guardado *factura.pdf* en Descargas" y devuelve al modelo (vía el resultado del
  comando en curso si lo hay, o `prompt.submit` con `display_kind:"system"`) el texto
  `"Downloaded file saved on the user's phone: factura.pdf (application/pdf, 123 KB)"` para que
  Hermes sepa que ya está.
- Tests: Inst: la página de fixture con enlace PDF → fichero en `Downloads/Hermes/`, hoja
  inferior visible; JVM: parser de `Content-Disposition` (UTF-8, comillas, sin nombre).

**G2 · "Enviar a Hermes"** — deps: G1, E2
- Entregables: sube el fichero descargado con `file.attach` y envía "Aquí tienes el archivo
  <nombre>" para que Hermes pueda p. ej. reenviarlo por correo.
- Tests: JVM: orden attach→prompt; Inst: botón visible y funcional con `FakeGateway`.

**G3 · Documentos en el chat** — deps: C4
- Entregables: burbuja de documento (icono por tipo, nombre, tamaño) tanto para adjuntos
  enviados como para descargas; tocar abre con `ACTION_VIEW` (FileProvider).
- Tests: Roborazzi; Inst: tocar abre intent (con `Intents.intended`).

### M7 — Accesibilidad y lectura de pantalla

**H1 · Auditoría TalkBack** — deps: C1–C8, F4
- Entregables: `contentDescription` en todo, orden de foco lógico, `liveRegion` para "Hermes
  está escribiendo", anuncios de nuevo mensaje, `semantics` en burbujas (rol, autor, hora),
  soporte de fuente del sistema hasta 2.0×, `Talkback`-friendly del WebView (no robar foco
  durante comandos).
- Tests: `AccessibilityChecks.enable()` en todos los tests Inst (falla el build si hay
  violaciones); Roborazzi a 2.0×.

**H2 · "¿Qué pone aquí?" (en la app)** — deps: C4, F4
- Entregables: en el Navegador, botón flotante 👁️ que toma snapshot **completo** + captura y
  envía `prompt.submit` con "Explícame en pocas palabras qué pone en esta pantalla" + la imagen
  (`image.attach_bytes`); la respuesta se lee en voz alta aunque el ajuste esté apagado.
- Tests: JVM: composición del prompt; Inst: flujo con `FakeGateway`.

### M8 — Fase 2: control del móvil (feature-device, opcional para v1)

**I1 · `HermesAccessibilityService`** — deps: F1 (formato), F3 (protocolo)
- Entregables: servicio de accesibilidad (declarado con `canRetrieveWindowContent`,
  `canPerformGestures`), `DeviceSnapshotBuilder` que convierte el árbol
  `AccessibilityNodeInfo` de la app en primer plano al formato §2.6 (roles: `button, textbox,
  checkbox, listitem, text, img, tab…`; nombre = `text` → `contentDescription` → `hint`), refs
  estables por `(windowId, hashCode)` durante un snapshot; acciones: `click` (`ACTION_CLICK` o
  gesto en el centro), `type` (`ACTION_SET_TEXT`), `scroll` (`ACTION_SCROLL_FORWARD/BACKWARD`
  o gesto), `press` (`Enter`→`ACTION_IME_ENTER`/gesto; `Back`/`Home` → `performGlobalAction`),
  `navigate` (`url` → `ACTION_VIEW`; esquema `app://<package>` → `launchIntent`), `screenshot`
  (`takeScreenshot`, API 30+). Registrado como controlador con `browser_profile_id:"mama-device"`
  **sólo** cuando la usuaria activa "Dejar que Hermes use el móvil" y confirma un diálogo del
  sistema; la app muestra una burbuja persistente "Hermes está usando el móvil · Parar".
- Aceptación: Hermes puede abrir la app de Gmail, buscar un correo y leer su contenido.
- Tests: JVM: `DeviceSnapshotBuilder` sobre árboles de fixture (serializados como JSON) →
  snapshots esperados; Inst: sobre una `TestActivity` propia, click/type/scroll reales por el
  servicio (activado en el emulador con `adb shell settings put secure enabled_accessibility_services …`).
- Riesgo: Google Play restringe `AccessibilityService`; se entrega como flavor `mama-device`
  sólo por APK. **Nunca** enviar snapshots del dispositivo si la app en primer plano está en la
  lista de bloqueo (`bank`, `wallet`, `password`, teclados) — lista en `device_blocklist.xml`.

**I2 · "¿Qué pone aquí?" (global)** — deps: I1
- Entregables: botón de accesibilidad global (`flagRequestAccessibilityButton`) o burbuja
  flotante que envía el snapshot + captura de **cualquier** app al último chat.
- Tests: Inst sobre `TestActivity`.

### M9 — Release

**J1 · Firma y distribución** — deps: A2
- Entregables: `signingConfigs.release` que lee de variables de entorno; workflow `release.yml`
  (tag `android-v*`) que produce `app-mama-release.aab` y `.apk`, sube a la **pista interna de
  Google Play** con `r0adkll/upload-google-play` (secreto `PLAY_SERVICE_ACCOUNT_JSON`) si existe
  el secreto, y adjunta el APK a la *GitHub Release* siempre (fallback sideload). `README.md`
  de instalación para la usuaria con capturas **sin datos reales**.
- Aceptación: tag → release con APK descargable; si Play rechaza (AccessibilityService o
  política), el APK sigue publicado y el job de Play es `continue-on-error`.
- Tests: `apksigner verify` en CI; `bundletool validate`.

**J2 · Endurecimiento** — deps: todo
- Entregables: `network_security_config` (HTTPS obligatorio en `mama`, certificados del sistema;
  opción de *pin* del certificado del servidor en Conexión: "Confiar en este servidor" con huella
  SHA-256 mostrada), `android:allowBackup=false`, `FLAG_SECURE` en Conexión, borrado de
  credenciales desde Conexión, `ProGuard/R8` con reglas de kotlinx.serialization, revisión de
  logs (ningún secreto), `exported=false` en todo salvo `MainActivity`.
- Tests: JVM: `LogRedactionTest`; script CI `scripts/android_secret_scan.sh` (gitleaks sobre
  `apps/android/`) obligatorio.

**J3 · QA manual guiada** — deps: J1
- Entregables: `QA_CHECKLIST.md`: 25 escenarios (primer arranque, credenciales mal, sin red,
  red que se cae a mitad de respuesta, foto de un ticket, "busca mi factura de X", Sí/No,
  pregunta de Hermes, voz con ruido, TalkBack, fuente 2×, giro de pantalla, llamada entrante
  durante respuesta, batería baja, 2 h en segundo plano, borrar chat, reinstalar conserva
  nada…). Cada uno con resultado esperado.
- Aceptación: checklist ejecutada en un móvil físico Android 10 y otro Android 14 antes del
  primer envío a la usuaria.

---

## 6. Orden sugerido y paralelismo

```
M0: A1 → (A2 ‖ A3)
M1: B1 → (B2 ‖ B4 ‖ B5) → B3 → B6
M2: C1 ‖ (D1, D2, E1, E2, F1 en paralelo con M2)
    C2 ‖ C3 ‖ C4 → C5 → (C6 ‖ C7) → C8
M5: F1 → F2 → F3 → F4
M6: G1 → G2 ; G3
M7: H1, H2
M9: J1 → J2 → J3
M8 (fase 2) cuando M9 esté entregado y la usuaria lleve ≥ 2 semanas usando la app.
```

Entregable "v1 funcional" = M0–M7 + M9. M8 es v1.1.

---

## 7. Estrategia de tests

### 7.1 Pirámide
- **JVM (≥ 70 % de los tests)**: `core-contract`, `core-gateway`, `core-controller`, ViewModels,
  mapeadores. Rápidos (< 60 s toda la suite), sin emulador.
- **Instrumentados**: un emulador API 35 en CI; cada pantalla tiene ≥ 1 test de recorrido con
  `FakeGateway`; `AccessibilityChecks` siempre activo.
- **Screenshots (Roborazzi)**: componentes y pantallas, claro/oscuro, fuente 1.0×/1.3×/2.0×.
  Los PNG de referencia viven en `apps/android/screenshots/` y se actualizan con
  `./gradlew recordRoborazzi` en un PR dedicado.
- **Contrato**: `gen_android_contract.py --check` + `GeneratedRoundTripTest` con fixtures
  grabadas de un `hermes serve` real (script `scripts/record_gateway_fixtures.py`, que
  **anonimiza** y guarda en `testing/fixtures/`). Cuando el backend cambie un schema, este test
  falla antes que la app.
- **JS del snapshot**: suite Node/jsdom en `core-controller/js` (`npm test`), fixtures HTML.
- **Python**: los scripts en `scripts/` se testean en la suite pytest del repo (`tests/scripts/`).

### 7.2 Fixtures (todas sintéticas, sin datos reales)
- `testing/fixtures/rpc/`: golden params/results por método.
- `testing/fixtures/events/`: guiones de streaming (`hello.json`, `tool_then_reply.json`,
  `approval_flow.json`, `clarify_batch.json`, `browser_find_invoice.json`).
- `testing/fixtures/html/`: tienda ficticia "Tienda Ejemplo" con pedidos y facturas PDF de 1 KB.
- `testing/fixtures/a11y/`: árboles `AccessibilityNodeInfo` serializados (fase 2).

### 7.3 Reglas
- Contratos de comportamiento, no snapshots de valores (regla del repo): p. ej. "los deltas se
  concatenan en orden de `seq`", no "el texto final es X".
- Cada bug que se arregle trae un test que fallaba antes.
- Sin `Thread.sleep` en tests; usar `TestScope`/`IdlingResource`.

### 7.4 E2E contra `hermes serve` real (nightly, opcional)
Workflow `android-e2e.yml` (manual/nightly): instala el repo Python, configura `basic_auth`
con credenciales generadas al vuelo, arranca `hermes serve --host 127.0.0.1` con un proveedor de
modelo *mock* (`HERMES_TEST_MODEL_STUB=1` si existe; si no, se salta) y ejecuta el test
instrumentado `RealGatewaySmokeTest` (login, crear chat, enviar "hola", recibir respuesta,
registrar controlador). Sirve para detectar drift real del backend.

---

## 8. Seguridad y privacidad (checklist obligatoria por PR)

- [ ] Ningún secreto/URL real/nombre real en código, tests, fixtures, capturas ni mensajes de commit.
- [ ] Credenciales sólo en `EncryptedSharedPreferences`; nunca en logs, `Bundle`s ni `Intent`s.
- [ ] Tickets WS de un solo uso; nunca reutilizados ni persistidos.
- [ ] HTTPS obligatorio en `mama`; cleartext sólo loopback/emulador en `dev`.
- [ ] Snapshots: campos de contraseña ocultos; en fase 2, apps de la lista de bloqueo nunca se leen.
- [ ] Aprobaciones: nunca `always`/`session`; cada acción peligrosa pasa por Sí/No.
- [ ] `allowBackup=false`, `exported=false`, `FLAG_SECURE` en Conexión.
- [ ] El `WebView` no expone `addJavascriptInterface` con métodos peligrosos; el puente JS→Kotlin
      sólo recibe strings y valida el `command_id` en curso.
- [ ] Permisos mínimos: `INTERNET`, `RECORD_AUDIO`, `CAMERA` (opcional), `POST_NOTIFICATIONS`,
      `FOREGROUND_SERVICE(_DATA_SYNC)`; `BIND_ACCESSIBILITY_SERVICE` sólo en flavor `mama-device`.

---

## 9. Riesgos y preguntas abiertas (resolver con el backend, no parchear en la app)

1. **`browser_screenshot` como capacidad del controlador**: está en la allowlist del broker pero
   la tabla de herramientas ruteadas de `tools/browser_tool.py` no incluye `browser_screenshot`
   (sólo `navigate, snapshot, click, type, scroll, back, press`). Anunciarla no hace daño; puede
   que el modelo use `browser_vision` (no ruteada) y falle → aceptable en v1.
2. **Descargas**: sin artifact store en `serve`, Hermes sabe que el PDF existe sólo por el texto
   que la app devuelve (G1). Si se quiere que Hermes lo reenvíe por correo, la usuaria pulsa
   "Enviar a Hermes" (G2). Alternativa futura: pedir que `serve` monte el artifact store.
3. **Sesión ocupada**: `prompt.submit` en sesión ocupada encola/steer; la UI debe reflejar
   "Hermes sigue con lo anterior; tu mensaje espera" (estado `queued`).
4. **Ticket WS 30 s**: en móviles con radio dormida el handshake puede superar 30 s →
   reintentar con ticket nuevo (B2 lo cubre); vigilar en QA.
5. **Play y AccessibilityService**: esperar rechazo; plan = APK.
6. **Skills de correo/Google** deben estar instaladas y autenticadas en el servidor; la app no
   puede ayudar en ese flujo (OAuth de Google se hace en el servidor/desktop).
7. **Idioma del modelo**: configurar el perfil del servidor para que responda en español y en
   frases cortas (system prompt/persona del lado servidor), no desde la app.
8. **Resultados del controlador sin redacción de secretos** (encontrado en revisión de F1):
   `tools/browser_extension_router.py` devuelve el `result` del broker verbatim, mientras el
   camino local pasa la salida del navegador por `redact_sensitive_text(force=True)`
   (`tools/browser_tool.py`). Un snapshot con texto con forma de secreto (`sk-…`,
   `password=…`) llega al modelo sin redactar. Fix de backend: post-procesar
   `browser.controller.result` en el broker con el mismo redactor — no parcheable desde la app.

---

## 10. Definición de "hecho" (global)

Una tarea está hecha cuando: compila en CI; todos sus tests listados existen y pasan; lint/detekt
en verde; no introduce secretos ni datos reales; actualiza `README.md`/`QA_CHECKLIST.md` si
cambia algo visible; y el PR enlaza el ID de tarea de este roadmap.
