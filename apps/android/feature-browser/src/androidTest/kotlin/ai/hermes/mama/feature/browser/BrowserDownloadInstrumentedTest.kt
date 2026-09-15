package ai.hermes.mama.feature.browser

import ai.hermes.mama.contract.RpcMethods
import ai.hermes.mama.contract.SessionCreateParams
import ai.hermes.mama.core.controller.BrowserCommand
import ai.hermes.mama.core.controller.ControllerSession
import ai.hermes.mama.core.controller.ControllerSignal
import ai.hermes.mama.core.controller.WebViewController
import ai.hermes.mama.core.ui.theme.MamaTheme
import ai.hermes.mama.gateway.GatewayClient
import ai.hermes.mama.gateway.JsonRpcChannel
import ai.hermes.mama.gateway.WebSocketTransport
import ai.hermes.mama.gateway.createSession
import ai.hermes.mama.gateway.submitPrompt
import ai.hermes.mama.testing.FakeGateway
import ai.hermes.mama.testing.FakeGatewayScript
import ai.hermes.mama.testing.FakeIdentity
import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.provider.MediaStore
import android.webkit.CookieManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.accessibility.AccessibilityChecks
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Flujo E2E instrumentado de G1 (ROADMAP §5/G1): la página fixture
 * `descargas.html` enlaza a `/factura-mama.pdf` servido por [MockWebServer]
 * con `Content-Disposition: attachment`. El `DownloadListener` del WebView
 * real dispara la descarga: OkHttp re-descarga con cookies y `User-Agent` del
 * WebView → fichero en `Downloads/Hermes/` (MediaStore) → hoja inferior
 * «📄 nombre — Abrir · Compartir · Enviar a Hermes», notificación del sistema
 * y `prompt.submit` `display_kind:"system"` al FakeGateway.
 *
 * Asserts del roadmap: fichero en Downloads/Hermes/, hoja inferior visible,
 * notificación, aviso a la sesión y nota «Downloaded file saved…» en el
 * `browser.controller.result` de un comando en curso.
 *
 * `AccessibilityChecks` (espresso → ATF) está activo: violaciones ERROR rompen
 * el test. Los clicks lógicos usan `performSemanticsAction(OnClick)` — nunca
 * `performClick` sobre nodos que el IME pueda cubrir.
 */
@RunWith(AndroidJUnit4::class)
class BrowserDownloadInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var server: MockWebServer

    /** Requests a `/factura-mama.pdf` en orden (navegación + re-descarga OkHttp). */
    private val pdfRequests = java.util.concurrent.CopyOnWriteArrayList<RecordedRequest>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    if (path.endsWith("/factura-mama.pdf")) {
                        pdfRequests += request
                    }
                    return when {
                        path.endsWith("/descargas.html") ->
                            MockResponse()
                                .setResponseCode(200)
                                .setHeader("Content-Type", "text/html; charset=utf-8")
                                .setBody(readAsset("descargas.html"))
                        path.endsWith("/factura-mama.pdf") ->
                            MockResponse()
                                .setResponseCode(200)
                                .setHeader("Content-Type", "application/pdf")
                                .setHeader(
                                    "Content-Disposition",
                                    "attachment; filename=\"factura-mama.pdf\"",
                                ).setBody(PDF_BODY)
                        // Navegación lenta pero finita: mantiene un comando en
                        // curso ~2,5 s para la nota en browser.controller.result.
                        path.endsWith("/lenta.html") ->
                            MockResponse()
                                .setResponseCode(200)
                                .setHeader("Content-Type", "text/html; charset=utf-8")
                                .setBody("<html><body>lenta</body></html>")
                                .setBodyDelay(2_500, TimeUnit.MILLISECONDS)
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
        server.start()
        // La notificación del sistema se ejercita de verdad (API 33+ exige el
        // permiso en runtime; el manifest de androidTest lo declara).
        InstrumentationRegistry
            .getInstrumentation()
            .uiAutomation
            .grantRuntimePermission(
                InstrumentationRegistry.getInstrumentation().targetContext.packageName,
                Manifest.permission.POST_NOTIFICATIONS,
            )
    }

    @After
    fun tearDown() {
        server.shutdown()
        // Limpieza: los ficheros del test no se quedan en Descargas del emulador.
        val context = ApplicationProvider.getApplicationContext<Context>()
        deleteTestDownloads(context)
        context
            .getSystemService(NotificationManager::class.java)
            ?.cancelAll()
    }

    @Test
    fun descargarFactura_guardaEnDescargasHermesYAvisaALaSesion() {
        // {{WEB}} del guion → base del MockWebServer.
        val scriptText =
            checkNotNull(
                FakeGatewayScript::class.java.getResource("/scripts/browser_download.json"),
            ) { "guion browser_download.json no empaquetado en :testing" }
                .readText()
                .replace("{{WEB}}", server.url("").toString().removeSuffix("/"))
        val gateway = FakeGateway(FakeGatewayScript.parse(scriptText)).start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var driver: AndroidWebViewDriver? = null
        var session: ControllerSession? = null
        var reporter: DownloadReporter? = null
        try {
            val transport =
                WebSocketTransport(
                    "${gateway.wsUrl}?ticket=" +
                        gateway.auth.mintTicket(FakeIdentity("u_fake_usuario", "basic")),
                )
            runBlocking { transport.awaitOpen() }
            val channel = JsonRpcChannel(transport, scope)
            val client = GatewayClient(channel, scope)
            val clients = MutableSharedFlow<GatewayClient>(replay = 1)
            clients.tryEmit(client)

            val created =
                composeRule.runOnUiThread {
                    AndroidWebViewDriver(composeRule.activity)
                }
            driver = created
            val executor = WebViewController(created, scope)
            val controllerSession =
                ControllerSession(scope, clients, executor, CONTROLLER_ID).also { it.start() }
            session = controllerSession

            val sessionId =
                runBlocking {
                    client
                        .createSession(SessionCreateParams(title = "Test G1"))
                        .sessionId
                }

            // Cookie del WebView: la re-descarga por OkHttp debe llevarla (§5/G1).
            val webBase = server.url("").toString().removeSuffix("/")
            composeRule.runOnUiThread {
                CookieManager.getInstance().apply {
                    setAcceptCookie(true)
                    setCookie(webBase, "hermes_g1=activo")
                    flush()
                }
            }

            // Sesión → reporter (aviso a la sesión) → manager (descarga) → panel.
            val context = ApplicationProvider.getApplicationContext<Context>()
            val downloadReporter =
                DownloadReporter(clients, sessionId, executor, scope).also { it.start() }
            reporter = downloadReporter
            val downloads = BrowserDownloadManager(context, scope, reporter = downloadReporter)

            // La «navegación» de la app: la señal F3 abre el panel con el gestor.
            val screenVisible = mutableStateOf(false)
            val paneRef = AtomicReference<BrowserPaneController>()
            scope.launch {
                controllerSession.signals.collect { signal ->
                    if (signal is ControllerSignal.NavigateToBrowser) {
                        paneRef.set(
                            BrowserPaneController(
                                clients = clients,
                                session = controllerSession,
                                executor = executor,
                                sessionId = signal.sessionId,
                                scope = scope,
                                downloads = downloads,
                            ),
                        )
                        screenVisible.value = true
                    }
                }
            }

            composeRule.setContent {
                MamaTheme {
                    if (screenVisible.value) {
                        paneRef.get()?.let { controller ->
                            BrowserPane(
                                controller = controller,
                                driver = created,
                                onBackToChat = { screenVisible.value = false },
                            )
                        }
                    }
                }
            }

            runBlocking { client.submitPrompt(sessionId, "baja la factura del móvil") }

            // El click a @e1 dispara el DownloadListener → OkHttp → MediaStore.
            composeRule.waitUntil(WAIT_MS) {
                paneRef
                    .get()
                    ?.uiState
                    ?.value
                    ?.download != null
            }
            val doc =
                paneRef
                    .get()
                    ?.uiState
                    ?.value
                    ?.download
            assertNotNull("la hoja debe conocer el documento", doc)
            assertTrue(doc?.fileName.orEmpty().startsWith("factura-mama"))

            // Hoja inferior «📄 nombre — Abrir · Compartir · Enviar a Hermes».
            composeRule.onNodeWithText(doc?.fileName.orEmpty()).assertIsDisplayed()
            composeRule.onNodeWithText(open).assertIsDisplayed()
            composeRule.onNodeWithText(share).assertIsDisplayed()
            // «Enviar a Hermes» llega en G2: visible pero deshabilitado.
            composeRule.onNodeWithText(sendToHermes).assertIsDisplayed().assertIsNotEnabled()
            runEspressoA11yCheck()

            // Fichero materializado en Downloads/Hermes/ (MediaStore).
            composeRule.waitUntil(WAIT_MS) { downloadRow(context, doc?.fileName.orEmpty()) != null }
            val (relativePath, size) =
                checkNotNull(downloadRow(context, doc?.fileName.orEmpty()))
            assertTrue("debe caer en Download/Hermes", relativePath.contains("Hermes"))
            assertTrue("el PDF debe tener contenido", size > 0)

            // Aviso a la sesión: prompt.submit display_kind:"system" con el
            // texto visible (ES) + la línea del modelo (EN) — §5/G1. (El
            // prompt inicial del test también es prompt.submit: se filtra por
            // display_kind.)
            composeRule.waitUntil(WAIT_MS) {
                systemSubmitCalls(gateway).isNotEmpty()
            }
            val submit = systemSubmitCalls(gateway).first()["params"]?.jsonObject
            assertNotNull(submit)
            val submitText =
                submit
                    ?.get("text")
                    ?.jsonPrimitive
                    ?.contentOrNull
                    .orEmpty()
            assertTrue(submitText.contains("Se ha guardado *${doc?.fileName}* en Descargas"))
            assertTrue(submitText.contains("Downloaded file saved on the user's phone"))

            // La RE-descarga (OkHttp) llevó cookies y User-Agent del WebView:
            // la navegación del WebView es el request #1, la descarga el último.
            val webViewUa =
                composeRule.runOnUiThread { created.webView.settings.userAgentString }
            composeRule.waitUntil(WAIT_MS) {
                pdfRequests.size >= 2 &&
                    pdfRequests.last().let { req ->
                        req.getHeader("Cookie")?.contains("hermes_g1=activo") == true &&
                            req.getHeader("User-Agent") == webViewUa
                    }
            }

            // Notificación del sistema publicada (permiso concedido en setUp).
            composeRule.waitUntil(WAIT_MS) {
                context
                    .getSystemService(NotificationManager::class.java)
                    ?.activeNotifications
                    ?.any { it.notification.channelId == "downloads" } == true
            }

            // Nota del modelo en el resultado de un comando en curso (§5/G1):
            // navigate a /lenta.html queda ~2,5 s en vuelo y la descarga
            // (~ms en localhost) aterriza dentro — su nota viaja en el outcome.
            val slowCommand =
                scope.async {
                    executor.execute(
                        BrowserCommand.Navigate("dl-note", "$webBase/lenta.html"),
                    )
                }
            composeRule.waitUntil(WAIT_MS) { executor.busy.value }
            downloads.onDownloadStart(
                WebViewDownloadRequest(
                    url = "$webBase/factura-mama.pdf",
                    userAgent = webViewUa,
                    contentDisposition = "attachment; filename=\"factura-mama.pdf\"",
                    mimeType = "application/pdf",
                    contentLength = -1,
                ),
            )
            val outcome = runBlocking { slowCommand.await() }
            assertTrue(
                "la nota del modelo debe viajar en browser.controller.result",
                outcome.resultJson.contains("Downloaded file saved on the user's phone"),
            )

            // La hoja se descarta con el scrim («Cerrar» accesible).
            composeRule
                .onNodeWithContentDescription(dismissCd)
                .performSemanticsAction(SemanticsActions.OnClick)
            composeRule.waitUntil(WAIT_MS) {
                paneRef
                    .get()
                    ?.uiState
                    ?.value
                    ?.download == null
            }
        } finally {
            reporter?.stop()
            runBlocking { session?.close() }
            driver?.let { created -> composeRule.runOnUiThread { created.destroy() } }
            scope.cancel()
            gateway.close()
        }
    }

    // ------------------------------------------------------------- helpers --

    /** `prompt.submit` recibidos con `display_kind:"system"` (los de descarga). */
    private fun systemSubmitCalls(gateway: FakeGateway) =
        gateway.receivedCalls.filter { call ->
            call["method"]?.jsonPrimitive?.contentOrNull == RpcMethods.PROMPT_SUBMIT &&
                call["params"]
                    ?.jsonObject
                    ?.get("display_kind")
                    ?.jsonPrimitive
                    ?.contentOrNull == "system"
        }

    /** Fila MediaStore del fichero en Descargas → (RELATIVE_PATH, SIZE). */
    private fun downloadRow(
        context: Context,
        displayName: String,
    ): Pair<String, Long>? {
        if (displayName.isBlank()) {
            return null
        }
        context.contentResolver
            .query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(
                    MediaStore.Downloads.RELATIVE_PATH,
                    MediaStore.Downloads.SIZE,
                ),
                "${MediaStore.Downloads.DISPLAY_NAME} = ?",
                arrayOf(displayName),
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) {
                    return null
                }
                return cursor.getString(0).orEmpty() to cursor.getLong(1)
            }
        return null
    }

    private fun deleteTestDownloads(context: Context) {
        context.contentResolver.delete(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            "${MediaStore.Downloads.DISPLAY_NAME} LIKE ?",
            arrayOf("factura-mama%"),
        )
    }

    private fun readAsset(name: String): String =
        InstrumentationRegistry
            .getInstrumentation()
            .context.assets
            .open(name)
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }

    /** Evalúa la raíz con ATF: violaciones de nivel ERROR rompen el test. */
    private fun runEspressoA11yCheck() {
        onView(isRoot()).check(matches(isDisplayed()))
    }

    private companion object {
        const val CONTROLLER_ID = "android-g1-instrumented"
        const val WAIT_MS = 20_000L

        /** PDF mínimo válido (cabecera + EOF) — contenido ficticio del fixture. */
        const val PDF_BODY = "%PDF-1.4\n1 0 obj<</Type/Catalog>>endobj\n%%EOF\n"

        private fun string(id: Int): String = ApplicationProvider.getApplicationContext<Context>().getString(id)

        private val open: String get() = string(R.string.download_open)
        private val share: String get() = string(R.string.download_share)
        private val sendToHermes: String get() = string(R.string.download_send_to_hermes)
        private val dismissCd: String get() = string(R.string.download_dismiss_cd)

        @JvmStatic
        @BeforeClass
        fun enableAccessibilityChecks() {
            AccessibilityChecks
                .enable()
                .setRunChecksFromRootView(true)
                .setThrowExceptionFor(AccessibilityCheckResult.AccessibilityCheckResultType.ERROR)
        }
    }
}
