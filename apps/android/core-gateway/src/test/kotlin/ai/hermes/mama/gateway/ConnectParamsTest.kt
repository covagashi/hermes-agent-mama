package ai.hermes.mama.gateway

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse

/** §8: el `toString()` de [ConnectParams] no puede filtrar ticket ni headers. */
class ConnectParamsTest {
    @Test
    fun `toString nunca imprime la url con ticket ni los headers`() {
        val params =
            ConnectParams(
                url = "wss://hermes.example.invalid/api/ws?ticket=TICKET-SECRETO-9f8a",
                headers = mapOf("Authorization" to "Bearer TOKEN-SECRETO-7c2b"),
            )

        val text = params.toString()

        assertFalse(text.contains("TICKET-SECRETO-9f8a"), "el ticket se filtró: $text")
        assertFalse(text.contains("TOKEN-SECRETO-7c2b"), "un header se filtró: $text")
        assertFalse(text.contains("hermes.example.invalid"), "la url se filtró: $text")
    }
}
