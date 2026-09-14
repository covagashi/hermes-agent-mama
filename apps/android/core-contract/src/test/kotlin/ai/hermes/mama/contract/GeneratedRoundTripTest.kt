package ai.hermes.mama.contract

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Round-trip de los DTOs generados (A3): cada fixture JSON sintético de
 * `testing/fixtures/` deserializa a su DTO y vuelve a serializar conservando
 * todos los campos que trae el fixture.
 *
 * Las fixtures son 100 % sintéticas (repo público, §7.2): ids `sess_*`,
 * `hermes.example.invalid`, `usuario`. Las claves `_unmodelled_extra` de los
 * modelos abiertos (`additionalProperties: true`) prueban que el decoder las
 * tolera; no se exige que sobrevivan.
 */
class GeneratedRoundTripTest {
    // Misma configuración tolerante que usará JsonRpcChannel (B1).
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = true
        }

    // Gradle ejecuta los tests con cwd = directorio del módulo (:core-contract).
    private val fixturesRoot = File("../testing/fixtures")

    @Test
    fun `cada fixture deserializa y re-serializa sin perder campos`() {
        assertTrue(fixturesRoot.isDirectory, "falta ${fixturesRoot.absolutePath}")
        val files =
            fixturesRoot
                .walkTopDown()
                .filter { it.isFile && it.extension == "json" }
                .sortedBy { it.path }
                .toList()
        assertTrue(files.size >= ContractSerializers.bySchema.size, "fixtures < schemas")

        for (file in files) {
            assertFixtureRoundTrips(file)
        }
    }

    private fun assertFixtureRoundTrips(file: File) {
        val schema = file.nameWithoutExtension

        @Suppress("UNCHECKED_CAST")
        val serializer =
            (
                ContractSerializers.bySchema[schema]
                    ?: error("fixture sin DTO generado: ${file.path}")
            ) as KSerializer<Any?>

        val wire = json.parseToJsonElement(file.readText())
        val decoded = json.decodeFromJsonElement(serializer, wire)
        val reEncoded = json.encodeToJsonElement(serializer, decoded)

        if (wire !is JsonObject) {
            // Fixture escalar (enum): el valor wire sobrevive tal cual.
            assertEquals(wire, reEncoded, file.name)
            return
        }
        val re = reEncoded.jsonObject
        for ((key, value) in wire) {
            if (key.startsWith("_")) {
                continue // clave no modelada: tolerada, no preservada
            }
            assertEquals(
                value,
                re[key],
                "${file.name}: el campo '$key' no sobrevivió al round-trip",
            )
        }
    }

    @Test
    fun `los serializers cubren todos los schemas del contrato generado`() {
        // Si el contrato crece y alguien olvida regenerar, SCHEMAS y el mapa
        // se desplazan juntos — este test lo hace visible.
        assertTrue(ContractSerializers.bySchema.isNotEmpty())
        for ((name, serializer) in ContractSerializers.bySchema) {
            assertNotNull(serializer, "serializer de $name")
        }
    }

    @Test
    fun `nombres de wire esperados por el roadmap`() {
        assertEquals("gateway.ping", RpcMethods.GATEWAY_PING)
        assertEquals("session.resume", RpcMethods.SESSION_RESUME)
        assertEquals("prompt.submit", RpcMethods.PROMPT_SUBMIT)
        assertEquals("browser.controller.register", RpcMethods.BROWSER_CONTROLLER_REGISTER)
        assertEquals("message.delta", EventTypes.MESSAGE_DELTA)
        assertEquals("browser.controller.command", EventTypes.BROWSER_CONTROLLER_COMMAND)
        assertEquals("approval", ServerRequests.APPROVAL)
        assertEquals("clarify", ServerRequests.CLARIFY)
    }
}
