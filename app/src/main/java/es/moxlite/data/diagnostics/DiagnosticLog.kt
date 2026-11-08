package es.moxlite.data.diagnostics

import es.moxlite.data.session.ApiTrafficLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Persistent diagnostic log of what the app itself does: background sync, every call to the
 * Moxfield API made by the app (not by the WebView), session renewals and crashes.
 *
 * It survives restarts (unlike Logcat, which a release build cannot read from the phone) so it
 * can be exported from Settings and analysed afterwards. Lines go to [file]; when it grows past
 * [maxBytes] it is rotated to `<name>.1`, so at most twice that size is kept on disk. Tokens,
 * passwords and cookies are redacted before anything is written.
 */
class DiagnosticLog(
    private val file: File,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val recentLines: Int = DEFAULT_RECENT_LINES,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val executor: Executor = Executors.newSingleThreadExecutor { Thread(it, "diagnostic-log").apply { isDaemon = true } },
    /** Mirror of every line (Logcat in the app; nothing in unit tests). */
    private val echo: (Level, String, String) -> Unit = { _, _, _ -> },
) {
    /** [priority] matches android.util.Log, so lines can be mirrored to Logcat. */
    enum class Level(val letter: Char, val priority: Int) { DEBUG('D', 3), INFO('I', 4), WARN('W', 5), ERROR('E', 6) }

    private val lock = Any()
    private val rotated get() = File(file.parentFile, "${file.name}.1")
    private val timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(zone)

    private val _recent = MutableStateFlow<List<String>>(emptyList())

    /** Last lines written (newest last), for the live view in the app. */
    val recent: StateFlow<List<String>> = _recent.asStateFlow()

    init {
        executor.execute {
            val tail = synchronized(lock) { readUnlocked() }.lineSequence().filter { it.isNotEmpty() }.toList().takeLast(recentLines)
            _recent.update { current -> (tail + current).takeLast(recentLines) }
        }
    }

    fun d(tag: String, message: String) = log(Level.DEBUG, tag, message)
    fun i(tag: String, message: String) = log(Level.INFO, tag, message)
    fun w(tag: String, message: String, error: Throwable? = null) = log(Level.WARN, tag, message, error)
    fun e(tag: String, message: String, error: Throwable? = null) = log(Level.ERROR, tag, message, error)

    /** Writes in the background: safe to call from any thread, including the main one. */
    fun log(level: Level, tag: String, message: String, error: Throwable? = null) {
        val entry = format(level, tag, message, error)
        echo(level, tag, entry.substringAfter(": "))
        executor.execute { write(entry) }
    }

    /** Writes right now, on the calling thread: for crashes, when the process is about to die. */
    fun logBlocking(level: Level, tag: String, message: String, error: Throwable? = null) {
        val entry = format(level, tag, message, error)
        echo(level, tag, entry.substringAfter(": "))
        write(entry)
    }

    /** Everything kept on disk, oldest first. */
    fun readAll(): String = synchronized(lock) { readUnlocked() }

    fun clear() {
        synchronized(lock) {
            file.delete()
            rotated.delete()
        }
        _recent.value = emptyList()
    }

    private fun readUnlocked(): String = listOf(rotated, file)
        .filter { it.exists() }
        .joinToString("") { runCatching { it.readText() }.getOrDefault("") }

    private fun write(entry: String) {
        synchronized(lock) {
            try {
                file.parentFile?.mkdirs()
                if (file.length() + entry.length > maxBytes && file.exists()) {
                    rotated.delete()
                    file.renameTo(rotated)
                }
                file.appendText(entry + "\n")
            } catch (e: IOException) {
                // Nowhere else to report a failing diagnostic log: Logcat only.
                echo(Level.ERROR, TAG, "Could not write the diagnostic log: ${e.message}")
            }
        }
        _recent.update { (it + entry.lines()).takeLast(recentLines) }
    }

    private fun format(level: Level, tag: String, message: String, error: Throwable?): String = buildString {
        append(timeFormat.format(Instant.ofEpochMilli(clock()))).append(' ')
        append(level.letter).append('/').append(tag).append(": ").append(redact(message))
        if (error != null) append(describe(error, withStack = level == Level.ERROR))
    }

    companion object {
        const val TAG = "Diagnostics"
        const val DEFAULT_MAX_BYTES = 512L * 1024
        const val DEFAULT_RECENT_LINES = 400
        private const val STACK_FRAMES = 12
        private const val MAX_CAUSES = 4

        private val BEARER = Regex("""(Bearer\s+)[A-Za-z0-9._~+/=-]+""", RegexOption.IGNORE_CASE)
        private val COOKIE = Regex("""((?:Set-)?Cookie:\s*).*""", RegexOption.IGNORE_CASE)

        /** Removes tokens, passwords and cookies from free text, JSON bodies and URLs. */
        fun redact(text: String): String = ApiTrafficLog.redactUrl(ApiTrafficLog.redactBody(text))
            .replace(BEARER) { "${it.groupValues[1]}***" }
            .replace(COOKIE) { "${it.groupValues[1]}***" }

        /** Exception chain (type and message of each cause) and, optionally, the top of the stack. */
        fun describe(error: Throwable, withStack: Boolean): String = buildString {
            var current: Throwable? = error
            var depth = 0
            while (current != null && depth < MAX_CAUSES) {
                append("\n    ").append(if (depth == 0) "! " else "caused by ")
                append(current::class.java.name).append(": ").append(redact(current.message.orEmpty()))
                if (withStack && depth == 0) {
                    current.stackTrace.take(STACK_FRAMES).forEach { append("\n        at ").append(it) }
                }
                current = current.cause?.takeIf { it !== current }
                depth++
            }
        }
    }
}
