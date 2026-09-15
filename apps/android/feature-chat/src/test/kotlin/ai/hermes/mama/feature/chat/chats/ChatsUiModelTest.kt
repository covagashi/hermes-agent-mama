package ai.hermes.mama.feature.chat.chats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Reglas puras de la UI de Chats (C3): emoji por título, etiqueta de tiempo
 * del mockup y el título "Chat de <fecha>" del botón Nuevo chat.
 */
class ChatsUiModelTest {
    @Test
    fun `emoji por titulo - cada categoria del roadmap`() {
        assertEquals("📧", chatEmojiForTitle("Correo"))
        assertEquals("📧", chatEmojiForTitle("Enviar email a la farmacia"))
        assertEquals("🧾", chatEmojiForTitle("Factura de la lavadora"))
        assertEquals("🧾", chatEmojiForTitle("Pedido de Mercadona"))
        assertEquals("🧾", chatEmojiForTitle("Compra del pan"))
        assertEquals("🍲", chatEmojiForTitle("Recetas"))
        assertEquals("🩺", chatEmojiForTitle("Cita del médico"))
        assertEquals("🩺", chatEmojiForTitle("Medico de cabecera")) // sin tilde también casa
        assertEquals("👨‍👩‍👧", chatEmojiForTitle("Familia"))
        assertEquals("👨‍👩‍👧", chatEmojiForTitle("Fotos de los nietos"))
        assertEquals("💬", chatEmojiForTitle("Chat de 15 de septiembre"))
        assertEquals("💬", chatEmojiForTitle(""))
    }

    @Test
    fun `la primera regla que casa gana - correo antes que recibo`() {
        // "correo" aparece antes en la cadena de reglas que "factura".
        assertEquals("📧", chatEmojiForTitle("Correo con la factura"))
    }

    @Test
    fun `etiqueta de tiempo - hoy es HH mm`() {
        // Jueves 16 nov 2023 22:13:20 UTC == mismo día en UTC.
        val label = chatTimeLabel(NOW_SECONDS - 60.0, NOW_MILLIS, ZoneOffset.UTC)
        assertEquals(ChatTimeLabel.Today("22:12"), label)
    }

    @Test
    fun `etiqueta de tiempo - ayer y dia de la semana`() {
        assertEquals(
            ChatTimeLabel.Yesterday,
            chatTimeLabel(NOW_SECONDS - DAY_SECONDS, NOW_MILLIS, ZoneOffset.UTC),
        )
        // El "ahora" de test cae en jueves: el lunes de esta misma semana ISO
        // (3 días atrás — ayer ya lo cubre la regla Yesterday).
        val monday = LocalDate.ofInstant(Instant.ofEpochMilli(NOW_MILLIS), ZoneOffset.UTC).minusDays(3)
        val label =
            chatTimeLabel(
                monday.atStartOfDay().toEpochSecond(ZoneOffset.UTC).toDouble(),
                NOW_MILLIS,
                ZoneOffset.UTC,
            )
        // Tipo de etiqueta (Weekday), no el texto literal — el CLDR cambia
        // las abreviaturas entre JDKs ("lun" vs "lun.").
        assertTrue("lunes de esta semana → Weekday", label is ChatTimeLabel.Weekday)
    }

    @Test
    fun `etiqueta de tiempo - mas atras es d MMM`() {
        val label =
            chatTimeLabel(
                LocalDate
                    .of(2023, 9, 3)
                    .atStartOfDay()
                    .toEpochSecond(ZoneOffset.UTC)
                    .toDouble(),
                NOW_MILLIS,
                ZoneOffset.UTC,
            )
        assertTrue("fecha antigua → Date", label is ChatTimeLabel.Date)
        assertEquals("3", (label as ChatTimeLabel.Date).text.take(1))
    }

    @Test
    fun `titulo por defecto es Chat de fecha en espanol`() {
        // 16 de noviembre de 2023 (UTC).
        assertEquals("Chat de 16 de noviembre", defaultNewChatTitle(NOW_MILLIS, ZoneOffset.UTC))
    }

    private companion object {
        const val NOW_SECONDS = 1_700_172_800.0
        const val NOW_MILLIS = 1_700_172_800_000L // jue 16 nov 2023 22:13:20 UTC
        const val DAY_SECONDS = 86_400.0
    }
}
