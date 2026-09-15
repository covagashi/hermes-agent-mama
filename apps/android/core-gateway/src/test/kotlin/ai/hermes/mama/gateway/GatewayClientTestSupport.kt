package ai.hermes.mama.gateway

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import kotlin.test.assertEquals

/**
 * Soporte compartido de los tests de [GatewayClient]: fixtures golden de
 * `testing/fixtures/rpc/` (JSON 100 % sintético, §7.2) y el invariante de B4 —
 * cada método serializa EXACTAMENTE los params esperados y decodifica el result.
 */
internal val testJson = Json { ignoreUnknownKeys = true }

// Gradle ejecuta los tests con cwd = directorio del módulo (:core-gateway).
// Los nombres de fichero son únicos por schema, repartidos en rpc/, events/,
// model/ y html/ — se resuelven por nombre sin importar el subdirectorio.
private val fixturesRoot = File("../testing/fixtures")

internal fun fixtureFile(schema: String): File {
    val matches =
        fixturesRoot
            .walkTopDown()
            .filter { it.isFile && it.name == "$schema.json" }
            .toList()
    check(matches.isNotEmpty()) { "fixture '$schema.json' no encontrada en ${fixturesRoot.absolutePath}" }
    check(matches.size == 1) { "fixture '$schema.json' ambigua: $matches" }
    return matches.single()
}

internal fun fixtureText(schema: String): String = fixtureFile(schema).readText()

internal fun fixture(schema: String): JsonElement = testJson.parseToJsonElement(fixtureText(schema))

internal fun idOf(frame: JsonObject): Long = frame.getValue("id").jsonPrimitive.long

internal fun TestScope.newGatewayClient(
    transport: FakeTransport,
    logger: (String) -> Unit = {},
): GatewayClient =
    GatewayClient(
        channel = JsonRpcChannel(transport = transport, scope = backgroundScope),
        scope = backgroundScope,
        logger = logger,
    )

/**
 * Golden §5/B4: [invoke] debe enviar un frame `method` cuyos `params` son
 * EXACTAMENTE los de `paramsFixture`, y devolver el `resultFixture` decodificado
 * a su DTO.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun <P : Any, R : Any> assertRpcGolden(
    method: String,
    paramsFixture: String,
    resultFixture: String,
    paramsSerializer: KSerializer<P>,
    resultSerializer: KSerializer<R>,
    invoke: suspend GatewayClient.(P) -> R,
) = runTest {
    val transport = FakeTransport()
    val client = newGatewayClient(transport)

    val params = testJson.decodeFromJsonElement(paramsSerializer, fixture(paramsFixture))
    val deferred = async { invoke(client, params) }
    runCurrent()

    val frame = transport.sentFrames().single()
    assertEquals(method, frame.getValue("method").jsonPrimitive.content)
    assertEquals(
        fixture(paramsFixture),
        frame.getValue("params"),
        "params serializados por $method",
    )

    val expected = testJson.decodeFromJsonElement(resultSerializer, fixture(resultFixture))
    transport.emit("""{"id":${idOf(frame)},"result":${fixtureText(resultFixture)}}""")

    assertEquals(expected, deferred.await())
}
