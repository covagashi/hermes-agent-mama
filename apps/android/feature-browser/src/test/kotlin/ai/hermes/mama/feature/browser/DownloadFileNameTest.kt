package ai.hermes.mama.feature.browser

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [DownloadFileName] (§5/G1): `filename*` UTF-8 de RFC 5987, `filename` con y
 * sin comillas, fallback por URL y nombre de respaldo — JVM puro, sin Android.
 */
class DownloadFileNameTest {
    private val url = "https://tienda.example.invalid/doc/factura.pdf?x=1"

    // ------------------------------------------------- Content-Disposition --

    @Test
    fun `filename entrecomillado (y el header gana a la URL)`() {
        // El basename de la URL es «factura.pdf»: el header manda.
        assertEquals(
            "factura-enero.pdf",
            DownloadFileName.resolve(
                url,
                "attachment; filename=\"factura-enero.pdf\"",
                "application/pdf",
            ),
        )
    }

    @Test
    fun `filename sin comillas (token)`() {
        assertEquals(
            "factura.pdf",
            DownloadFileName.resolve(url, "attachment; filename=factura.pdf", "application/pdf"),
        )
    }

    @Test
    fun `filename asterisco UTF-8 con espacios y acentos`() {
        assertEquals(
            "Factura de crédito.pdf",
            DownloadFileName.resolve(
                url,
                "attachment; filename*=UTF-8''Factura%20de%20cr%C3%A9dito.pdf",
                "application/pdf",
            ),
        )
    }

    @Test
    fun `filename asterisco UTF-8 con unicode (euro)`() {
        assertEquals(
            "€ factura.pdf",
            DownloadFileName.resolve(
                url,
                "attachment; filename*=utf-8''%E2%82%AC%20factura.pdf",
                "application/pdf",
            ),
        )
    }

    @Test
    fun `filename asterisco gana al filename plano (RFC 6266)`() {
        assertEquals(
            "€.pdf",
            DownloadFileName.resolve(
                url,
                "attachment; filename=\"plano.pdf\"; filename*=UTF-8''%E2%82%AC.pdf",
                "application/pdf",
            ),
        )
    }

    @Test
    fun `filename asterisco con charset ISO-8859-1`() {
        // %E1 = á en ISO-8859-1 (en UTF-8 serían dos bytes).
        assertEquals(
            "está.pdf",
            DownloadFileName.resolve(
                url,
                "attachment; filename*=ISO-8859-1''est%E1.pdf",
                "application/pdf",
            ),
        )
    }

    @Test
    fun `comillas con escapes y punto y coma dentro`() {
        assertEquals(
            "fa_c_tu;ra.pdf",
            DownloadFileName.resolve(
                url,
                "attachment; filename=\"fa\\\"c\\\"tu;ra.pdf\"; size=4",
                "application/pdf",
            ),
        )
    }

    @Test
    fun `header sin filename cae al nombre de la URL`() {
        assertEquals(
            "factura.pdf",
            DownloadFileName.resolve(url, "attachment", "application/pdf"),
        )
        assertEquals(
            "factura.pdf",
            DownloadFileName.resolve(url, "attachment; size=99", "application/pdf"),
        )
    }

    @Test
    fun `header ausente cae al nombre de la URL`() {
        assertEquals("factura.pdf", DownloadFileName.resolve(url, null, "application/pdf"))
    }

    @Test
    fun `filename vacio cae a la URL`() {
        assertEquals(
            "factura.pdf",
            DownloadFileName.resolve(url, "attachment; filename=\"\"", "application/pdf"),
        )
    }

    // ------------------------------------------------------------------ URL --

    @Test
    fun `basename de la URL percent-decodificado`() {
        assertEquals(
            "factura marzo.pdf",
            DownloadFileName.resolve(
                "https://tienda.example.invalid/doc/factura%20marzo.pdf?d=1",
                null,
                "application/pdf",
            ),
        )
    }

    @Test
    fun `basename de URL sin extension hereda la del MIME`() {
        assertEquals(
            "download.pdf",
            DownloadFileName.resolve(
                "https://tienda.example.invalid/download?id=7",
                null,
                "application/pdf",
            ),
        )
    }

    @Test
    fun `URL sin nombre usa el respaldo con extension del MIME`() {
        assertEquals(
            "descarga.pdf",
            DownloadFileName.resolve("https://tienda.example.invalid/", null, "application/pdf"),
        )
        // Sin path el dominio NO es un nombre: cae al respaldo igualmente.
        assertEquals(
            "descarga.pdf",
            DownloadFileName.resolve("https://tienda.example.invalid", null, "application/pdf"),
        )
    }

    @Test
    fun `respaldo sin MIME conocido queda sin extension`() {
        assertEquals(
            "descarga",
            DownloadFileName.resolve("https://tienda.example.invalid/", null, null),
        )
        assertEquals(
            "descarga",
            DownloadFileName.resolve(
                "https://tienda.example.invalid/",
                null,
                "application/octet-stream",
            ),
        )
    }

    @Test
    fun `nombre sin extension hereda la del MIME`() {
        assertEquals(
            "documento.pdf",
            DownloadFileName.resolve(
                "https://tienda.example.invalid/doc/documento",
                "attachment; filename=\"documento\"",
                "application/pdf",
            ),
        )
    }

    // ----------------------------------------------------------------- saneo --

    @Test
    fun `separadores de ruta en el nombre se neutralizan`() {
        assertEquals(
            "_.._evil.sh",
            DownloadFileName.resolve(url, "attachment; filename=\"../.._evil.sh\"", null),
        )
        assertEquals(
            "carpeta_factura.pdf",
            DownloadFileName.resolve(
                url,
                "attachment; filename=\"carpeta/factura.pdf\"",
                "application/pdf",
            ),
        )
    }

    @Test
    fun `separador percent-decodificado dentro de filename asterisco`() {
        // %2F decodifica a '/' — no puede colar una ruta.
        assertEquals(
            "a_b.pdf",
            DownloadFileName.resolve(url, "attachment; filename*=UTF-8''a%2Fb.pdf", null),
        )
    }

    @Test
    fun `tipo de disposicion inline tambien aporta nombre`() {
        assertEquals(
            "informe.pdf",
            DownloadFileName.resolve(url, "inline; filename=\"informe.pdf\"", "application/pdf"),
        )
    }
}
