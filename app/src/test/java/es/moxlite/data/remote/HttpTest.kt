package es.moxlite.data.remote

import kotlinx.serialization.SerializationException
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class HttpTest {

    private fun response(code: Int, vararg headers: Pair<String, String>): Response =
        Response.Builder()
            .request(Request.Builder().url("https://api2.moxfield.com/v3/decks/all/x").build())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("msg")
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .build()

    @Test
    fun `detecta el reto de Cloudflare`() {
        assertTrue(response(200, "cf-mitigated" to "challenge").isCloudflareChallenge())
        assertTrue(response(403, "Content-Type" to "text/html; charset=UTF-8").isCloudflareChallenge())
        assertTrue(response(503, "Content-Type" to "TEXT/HTML").isCloudflareChallenge())
    }

    @Test
    fun `no confunde errores JSON con Cloudflare`() {
        assertFalse(response(403, "Content-Type" to "application/json").isCloudflareChallenge())
        assertFalse(response(404, "Content-Type" to "text/html").isCloudflareChallenge())
        assertFalse(response(200).isCloudflareChallenge())
    }

    @Test
    fun `traduce errores a mensajes para el usuario`() {
        assertEquals("Demasiadas peticiones. Espera 12 s.", MoxError.RateLimited(12).userMessage())
        assertEquals("Demasiadas peticiones. Espera 30 s.", MoxError.RateLimited(null).userMessage())
        assertEquals("Moxfield ha respondido con error 502.", MoxError.Server(502).userMessage())
        assertTrue(UnknownHostException("x").userMessage().startsWith("Sin conexión"))
        assertTrue(SocketTimeoutException().userMessage().contains("tarda demasiado"))
        assertTrue(SerializationException("campo raro").userMessage().contains("campo raro"))
        assertEquals("boom", IllegalStateException("boom").userMessage())
        assertEquals("IllegalStateException", IllegalStateException().userMessage())
    }

    @Test
    fun `el json es tolerante con claves desconocidas y nulos`() {
        val parsed = Http.json.decodeFromString(
            es.moxlite.data.remote.moxfield.MoxAuthorDto.serializer(),
            """{"userName":"yo","displayName":null,"extra":[1,2,3]}""",
        )
        assertEquals("yo", parsed.userName)
        assertEquals("", parsed.displayName)
    }

    @Test
    fun `distingue bloqueo de falta de conexion`() {
        assertTrue(java.net.ConnectException("refused").looksLikeBlocking())
        assertTrue(java.net.SocketException("Connection reset").looksLikeBlocking())
        assertTrue(javax.net.ssl.SSLHandshakeException("handshake").looksLikeBlocking())
        assertFalse(UnknownHostException("sin red").looksLikeBlocking())
        assertFalse(MoxError.NotFound().looksLikeBlocking())
        assertTrue(MoxError.Unreachable().userMessage().contains("LaLiga"))
        assertTrue(MoxError.WriteRejected(409, "version").userMessage().contains("409"))
    }
}
