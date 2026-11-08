package es.moxlite.data.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.time.ZoneOffset
import java.util.concurrent.Executor

class DiagnosticLogTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val now = 1_762_620_000_000L // 2025-11-08 16:40:00 UTC

    private fun newLog(file: File = folder.root.resolve("diag/diagnostic.log"), maxBytes: Long = 64 * 1024) =
        DiagnosticLog(
            file = file,
            maxBytes = maxBytes,
            recentLines = 5,
            clock = { now },
            zone = ZoneOffset.UTC,
            executor = Executor { it.run() },
        )

    @Test
    fun `writes timestamped lines with level and tag that survive a new instance`() {
        val file = folder.root.resolve("diag/diagnostic.log")
        newLog(file).i("Sync", "Push started: 3 pending changes")

        val reopened = newLog(file)

        assertEquals("2025-11-08 16:40:00.000 I/Sync: Push started: 3 pending changes\n", reopened.readAll())
        assertEquals(listOf("2025-11-08 16:40:00.000 I/Sync: Push started: 3 pending changes"), reopened.recent.value)
    }

    @Test
    fun `redacts bearer tokens, cookies and secret json fields`() {
        val log = newLog()
        log.w("Http", """Authorization: Bearer eyJhbGciOi.payload.sig body {"refreshToken":"R1","name":"Deck"}""")
        log.w("Http", "Cookie: refresh_token=abc; cf_clearance=xyz")
        log.w("Http", "GET https://api2.moxfield.com/x?access_token=SECRET&page=1")

        val text = log.readAll()
        listOf("eyJhbGciOi", "R1", "abc", "xyz", "SECRET").forEach { assertFalse("Leaked $it", text.contains(it)) }
        assertTrue(text.contains("\"name\":\"Deck\""))
        assertTrue(text.contains("page=1"))
    }

    @Test
    fun `redacting a token in a url keeps the rest of the line`() {
        val log = newLog()
        log.i("Http", "GET https://api2.moxfield.com/x?token=SECRET -> 200 (12 ms)")

        assertTrue(log.readAll().contains("?token=*** -> 200 (12 ms)"))
    }

    @Test
    fun `records the cause chain of an error and the stack only for errors`() {
        val log = newLog()
        val error = IOException("connection reset", IllegalStateException("socket closed"))

        log.w("Sync", "temporary failure", error)
        log.e("Sync", "rejected", error)

        val text = log.readAll()
        assertTrue(text.contains("! java.io.IOException: connection reset"))
        assertTrue(text.contains("caused by java.lang.IllegalStateException: socket closed"))
        val warning = text.substringBefore("E/Sync")
        assertFalse(warning.contains("        at "))
        assertTrue(text.substringAfter("E/Sync").contains("        at "))
    }

    @Test
    fun `rotates into a second file and keeps both in order`() {
        val file = folder.root.resolve("diagnostic.log")
        val log = newLog(file, maxBytes = 200)

        repeat(6) { log.i("Sync", "line $it with some padding to grow the file") }

        assertTrue(File(file.parentFile, "diagnostic.log.1").exists())
        val lines = log.readAll().lines().filter { it.isNotEmpty() }
        val numbers = lines.map { it.substringAfter("line ").substringBefore(' ').toInt() }
        assertEquals(numbers.sorted(), numbers)
        assertEquals(5, numbers.last())
        assertTrue("At most twice the limit is kept", file.length() + File(file.parentFile, "diagnostic.log.1").length() <= 400)
    }

    @Test
    fun `keeps only the latest lines in memory and clear empties everything`() {
        val log = newLog()
        repeat(8) { log.d("Sync", "line $it") }

        assertEquals(5, log.recent.value.size)
        assertTrue(log.recent.value.last().endsWith("line 7"))

        log.clear()
        assertEquals("", log.readAll())
        assertTrue(log.recent.value.isEmpty())
    }
}
