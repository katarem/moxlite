package es.moxlite.data.diagnostics

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** State of the app when the report is exported. Built by [DiagnosticsCollector]. */
data class DiagnosticsSnapshot(
    val generatedAt: Long,
    val zone: ZoneId,
    val app: List<Pair<String, String>>,
    val session: List<Pair<String, String>>,
    val background: List<Pair<String, String>>,
    val work: List<WorkSnapshot>,
    val pendingOps: List<PendingOpSnapshot>,
    val localDecks: List<LocalDeckSnapshot>,
)

data class WorkSnapshot(
    val uniqueName: String,
    val state: String,
    val runAttemptCount: Int,
    val nextRunAt: Long?,
    val stopReason: String?,
)

data class PendingOpSnapshot(
    val id: Long,
    val deckId: String,
    val type: String,
    val attempts: Int,
    val createdAt: Long,
    val lastError: String?,
    val payload: String,
)

data class LocalDeckSnapshot(
    val id: String,
    val name: String,
    val isLocalOnly: Boolean,
    val hasLocalChanges: Boolean,
    val hasConflict: Boolean,
    val remoteVersion: Int,
    val isMine: Boolean,
    val pendingOps: Int,
)

/**
 * Plain-text report to share: the current state (session, background work, queue of changes)
 * followed by the whole [DiagnosticLog]. Pure, so it can be tested without Android.
 */
object DiagnosticsReport {

    private const val MAX_PAYLOAD = 400

    fun render(snapshot: DiagnosticsSnapshot, log: String): String {
        val time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(snapshot.zone)
        fun at(millis: Long?) = millis?.let { time.format(Instant.ofEpochMilli(it)) } ?: "-"
        fun inMinutes(millis: Long?) = millis?.let {
            val minutes = (it - snapshot.generatedAt) / 60_000
            if (minutes >= 0) "in $minutes min" else "${-minutes} min ago"
        }.orEmpty()

        val text = buildString {
            appendLine("# Moxlite diagnostics")
            appendLine("Generated: ${at(snapshot.generatedAt)} (${snapshot.zone})")
            section("App", snapshot.app)
            section("Session", snapshot.session)
            section("Background execution", snapshot.background)

            appendLine().appendLine("## Background work (WorkManager)")
            if (snapshot.work.isEmpty()) appendLine("(none)")
            snapshot.work.forEach { w ->
                append("- ${w.uniqueName}: ${w.state}, attempts=${w.runAttemptCount}")
                w.nextRunAt?.let { append(", next run ${at(it)} (${inMinutes(it)})") }
                w.stopReason?.let { append(", last stop reason=$it") }
                appendLine()
            }

            appendLine().appendLine("## Pending changes (${snapshot.pendingOps.size})")
            if (snapshot.pendingOps.isEmpty()) appendLine("(none)")
            snapshot.pendingOps.forEach { op ->
                appendLine("- #${op.id} ${op.type} deck=${op.deckId} attempts=${op.attempts} queued=${at(op.createdAt)}")
                op.lastError?.let { appendLine("    last error: $it") }
                appendLine("    payload: ${op.payload.take(MAX_PAYLOAD)}")
            }

            appendLine().appendLine("## Decks with local changes (${snapshot.localDecks.size})")
            if (snapshot.localDecks.isEmpty()) appendLine("(none)")
            snapshot.localDecks.forEach { d ->
                appendLine(
                    "- ${d.id} \"${d.name}\" localOnly=${d.isLocalOnly} localChanges=${d.hasLocalChanges} " +
                        "conflict=${d.hasConflict} remoteVersion=${d.remoteVersion} mine=${d.isMine} pendingOps=${d.pendingOps}"
                )
            }

            appendLine().appendLine("## Log (oldest first)")
            append(log.ifBlank { "(empty)\n" })
        }
        return DiagnosticLog.redact(text)
    }

    private fun StringBuilder.section(title: String, rows: List<Pair<String, String>>) {
        appendLine().appendLine("## $title")
        rows.forEach { (key, value) -> appendLine("- $key: $value") }
    }
}
