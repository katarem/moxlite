package es.moxlite.data.diagnostics

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.time.ZoneOffset
import java.util.concurrent.Executor

class DiagnosticsInterceptorTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val log by lazy {
        DiagnosticLog(
            file = folder.root.resolve("diagnostic.log"),
            clock = { 0L },
            zone = ZoneOffset.UTC,
            executor = Executor { it.run() },
        )
    }

    private val json = "application/json".toMediaType()

    /** Client whose "server" is the given interceptor: no network involved. */
    private fun client(server: (Request) -> Response) = OkHttpClient.Builder()
        .addInterceptor(DiagnosticsInterceptor(log, clock = { 0L }))
        .addInterceptor(Interceptor { chain -> server(chain.request()) })
        .build()

    private fun respond(request: Request, code: Int, body: String, type: String = "application/json") = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("status")
        .header("Content-Type", type)
        .body(body.toResponseBody(type.toMediaType()))
        .build()

    @Test
    fun `logs a rejected write with its request and response bodies but no headers`() {
        val request = Request.Builder()
            .url("https://api2.moxfield.com/v2/decks/abc/import?access_token=SECRET")
            .header("Authorization", "Bearer TOKEN123")
            .post("""{"importText":"1 Sol Ring (C21) 263","replaceAll":true}""".toRequestBody(json))
            .build()

        val response = client { respond(it, 400, """{"title":"Unknown card: Foo"}""") }.newCall(request).execute()

        assertEquals("The response is still readable by the caller", """{"title":"Unknown card: Foo"}""", response.body?.string())
        val text = log.readAll()
        assertTrue(text, text.contains("W/Http: POST https://api2.moxfield.com/v2/decks/abc/import?access_token=*** -> 400"))
        assertTrue(text.contains("request: {\"importText\":\"1 Sol Ring (C21) 263\",\"replaceAll\":true}"))
        assertTrue(text.contains("response: {\"title\":\"Unknown card: Foo\"}"))
        assertFalse(text.contains("TOKEN123"))
        assertFalse(text.contains("SECRET"))
    }

    @Test
    fun `logs successful calls on one line`() {
        val request = Request.Builder().url("https://api2.moxfield.com/v3/decks/all/abc").build()

        client { respond(it, 200, "{}") }.newCall(request).execute().close()

        assertTrue(log.readAll().startsWith("1970-01-01 00:00:00.000 I/Http: GET https://api2.moxfield.com/v3/decks/all/abc -> 200 (0 ms)"))
    }

    @Test
    fun `logs and rethrows connection failures`() {
        val request = Request.Builder().url("https://api2.moxfield.com/v2/decks").build()

        val thrown = runCatching {
            client { throw IOException("Connection reset") }.newCall(request).execute()
        }.exceptionOrNull()

        assertTrue(thrown is IOException)
        val text = log.readAll()
        assertTrue(text.contains("W/Http: GET https://api2.moxfield.com/v2/decks failed after 0 ms"))
        assertTrue(text.contains("! java.io.IOException: Connection reset"))
    }
}
