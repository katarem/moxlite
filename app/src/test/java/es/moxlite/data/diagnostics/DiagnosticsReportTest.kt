package es.moxlite.data.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class DiagnosticsReportTest {

    private val now = 1_762_620_000_000L // 2025-11-08 16:40:00 UTC

    private val snapshot = DiagnosticsSnapshot(
        generatedAt = now,
        zone = ZoneOffset.UTC,
        app = listOf("version" to "0.1.42 (42, release)"),
        session = listOf("hasAccount" to "true", "access token" to "valid"),
        background = listOf("standby bucket" to "rare"),
        work = listOf(
            WorkSnapshot("moxlite-sync-now", "ENQUEUED", runAttemptCount = 9, nextRunAt = now + 3 * 60 * 60_000, stopReason = null),
            WorkSnapshot("moxlite-sync-now-full", "SUCCEEDED", runAttemptCount = 0, nextRunAt = null, stopReason = null),
        ),
        pendingOps = listOf(
            PendingOpSnapshot(
                id = 7, deckId = "local-1234", type = "create", attempts = 2, createdAt = now - 60_000,
                lastError = "Moxfield ha rechazado el cambio (400): Unknown card",
                payload = """{"type":"create","name":"Copia","format":"commander"}""",
            ),
        ),
        localDecks = listOf(
            LocalDeckSnapshot(
                id = "local-1234", name = "Copia", isLocalOnly = true, hasLocalChanges = true, hasConflict = false,
                remoteVersion = 0, isMine = true, pendingOps = 1,
            ),
        ),
    )

    @Test
    fun `includes the state needed to explain a stuck sync`() {
        val report = DiagnosticsReport.render(snapshot, "2025-11-08 16:39:00.000 I/Sync: Push started\n")

        assertTrue(report.contains("- standby bucket: rare"))
        assertTrue(report.contains("- moxlite-sync-now: ENQUEUED, attempts=9, next run 2025-11-08 19:40:00 (in 180 min)"))
        assertTrue(report.contains("- moxlite-sync-now-full: SUCCEEDED, attempts=0\n"))
        assertTrue(report.contains("- #7 create deck=local-1234 attempts=2"))
        assertTrue(report.contains("last error: Moxfield ha rechazado el cambio (400): Unknown card"))
        assertTrue(report.contains("- local-1234 \"Copia\" localOnly=true"))
        assertTrue(report.endsWith("## Log (oldest first)\n2025-11-08 16:39:00.000 I/Sync: Push started\n"))
    }

    @Test
    fun `never exports secrets even if they reached the log`() {
        val report = DiagnosticsReport.render(snapshot, "I/Http: Authorization: Bearer abc.def.ghi {\"accessToken\":\"A1\"}\n")

        assertFalse(report.contains("abc.def.ghi"))
        assertFalse(report.contains("A1"))
    }

    @Test
    fun `says so when there is nothing to show`() {
        val empty = snapshot.copy(work = emptyList(), pendingOps = emptyList(), localDecks = emptyList())

        val report = DiagnosticsReport.render(empty, "")

        assertTrue(report.contains("## Pending changes (0)\n(none)"))
        assertTrue(report.endsWith("(empty)\n"))
    }
}
