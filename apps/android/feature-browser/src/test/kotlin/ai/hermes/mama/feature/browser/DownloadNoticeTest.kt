package ai.hermes.mama.feature.browser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Formatos exactos del aviso de descarga (§5/G1): el visible en español y la
 * nota del modelo en inglés se fijan aquí — el roadmap los da literales.
 */
class DownloadNoticeTest {
    @Test
    fun `aviso visible es el literal del roadmap`() {
        assertEquals(
            "📄 Se ha guardado *factura.pdf* en Descargas",
            DownloadNotice.userLine("factura.pdf"),
        )
    }

    @Test
    fun `nota del modelo es el literal ingles del roadmap`() {
        // 123 KB = 125_952 bytes → el roadmap fija "(application/pdf, 123 KB)".
        assertEquals(
            "Downloaded file saved on the user's phone: factura.pdf (application/pdf, 123 KB)",
            DownloadNotice.modelLine("factura.pdf", "application/pdf", 125_952),
        )
    }

    @Test
    fun `el mensaje de sesion lleva el aviso y la nota`() {
        val text = DownloadNotice.sessionMessage("factura.pdf", "application/pdf", 125_952)
        assertTrue(text.startsWith("📄 Se ha guardado *factura.pdf* en Descargas"))
        assertTrue(
            text.contains("Downloaded file saved on the user's phone: factura.pdf"),
            "la línea del modelo viaja en el mismo prompt.submit",
        )
    }

    @Test
    fun `sizeLabel en KB y MB`() {
        assertEquals("1 KB", DownloadNotice.sizeLabel(1))
        assertEquals("123 KB", DownloadNotice.sizeLabel(125_952))
        assertEquals("2,0 MB", DownloadNotice.sizeLabel(2 * 1_048_576))
    }
}
