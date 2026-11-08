package es.moxlite.data.diagnostics

import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import java.io.IOException

/**
 * Writes every HTTP exchange of the client it is added to into the [DiagnosticLog]: method, URL,
 * status and duration; for writes, the request body; for errors, the start of the response body.
 * Headers are never logged (they carry the token and the cookies).
 *
 * Placed after the interceptor that adds the session, so each real request is logged, including
 * the one repeated after renewing an expired token.
 */
class DiagnosticsInterceptor(
    private val log: DiagnosticLog,
    private val tag: String = TAG,
    private val clock: () -> Long = System::currentTimeMillis,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val started = clock()
        val summary = "${request.method} ${request.url}"
        val sent = requestBody(request)
        val response = try {
            chain.proceed(request)
        } catch (e: IOException) {
            log.w(tag, "$summary failed after ${clock() - started} ms${sent.prefixed()}", e)
            throw e
        }
        val elapsed = clock() - started
        if (response.isSuccessful) {
            log.i(tag, "$summary -> ${response.code} (${elapsed} ms)${sent.prefixed()}")
        } else {
            val type = response.header("Content-Type").orEmpty()
            val mitigated = response.header("cf-mitigated")?.let { ", cf-mitigated=$it" }.orEmpty()
            val received = runCatching { response.peekBody(MAX_BODY.toLong()).string() }.getOrDefault("")
            log.w(
                tag,
                "$summary -> ${response.code} (${elapsed} ms, $type$mitigated)${sent.prefixed()}" +
                    received.oneLine().takeIf { it.isNotBlank() }?.let { "\n    response: $it" }.orEmpty(),
            )
        }
        return response
    }

    private fun requestBody(request: Request): String {
        val body = request.body ?: return ""
        if (body.isOneShot() || body.isDuplex() || body.contentLength() > MAX_READABLE_BODY) return "[${body.contentLength()} bytes]"
        return runCatching { Buffer().also(body::writeTo).readUtf8() }.getOrDefault("").oneLine()
    }

    private fun String.prefixed() = if (isBlank()) "" else "\n    request: $this"

    private fun String.oneLine() = replace(Regex("\\s+"), " ").trim().let {
        if (it.length > MAX_BODY) it.take(MAX_BODY) + "… (${it.length} chars)" else it
    }

    private companion object {
        const val TAG = "Http"
        const val MAX_BODY = 1_500
        const val MAX_READABLE_BODY = 256L * 1024
    }
}
