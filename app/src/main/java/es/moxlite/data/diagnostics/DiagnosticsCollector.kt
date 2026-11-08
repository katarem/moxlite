package es.moxlite.data.diagnostics

import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import androidx.core.content.FileProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import es.moxlite.BuildConfig
import es.moxlite.Connectivity
import es.moxlite.data.repo.DeckRepository
import es.moxlite.data.session.SessionManager
import es.moxlite.data.settings.AppSettings
import es.moxlite.data.sync.SyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Gathers a [DiagnosticsSnapshot] from the running app and exports the report as a file. */
class DiagnosticsCollector(
    private val context: Context,
    private val log: DiagnosticLog,
    private val session: SessionManager,
    private val settings: AppSettings,
    private val connectivity: Connectivity,
    private val decks: DeckRepository,
) {

    suspend fun snapshot(): DiagnosticsSnapshot = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val pending = decks.pendingOpsNow()
        DiagnosticsSnapshot(
            generatedAt = now,
            zone = ZoneId.systemDefault(),
            app = appRows(),
            session = sessionRows(now),
            background = backgroundRows(),
            work = SyncWorker.uniqueWorkNames.flatMap { name -> workRows(name) },
            pendingOps = pending.map {
                PendingOpSnapshot(it.id, it.deckId, it.type, it.attempts, it.createdAt, it.lastError, it.payload)
            },
            localDecks = decks.decksWithLocalChangesNow().map { d ->
                LocalDeckSnapshot(
                    id = d.publicId,
                    name = d.name,
                    isLocalOnly = d.isLocalOnly,
                    hasLocalChanges = d.hasLocalChanges,
                    hasConflict = d.hasConflict,
                    remoteVersion = d.remoteVersion,
                    isMine = decks.isMine(d),
                    pendingOps = pending.count { it.deckId == d.publicId },
                )
            },
        )
    }

    /** Writes the full report to a file and returns the share sheet for it. */
    suspend fun exportIntent(chooserTitle: String): Intent {
        val report = DiagnosticsReport.render(snapshot(), withContext(Dispatchers.IO) { log.readAll() })
        val file = withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, EXPORT_DIR).apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() } // only the latest export is kept
            File(dir, "moxlite-diagnostics-${LocalDateTime.now().format(FILE_TIME)}.txt").apply { writeText(report) }
        }
        log.i(DiagnosticLog.TAG, "Report exported (${file.length() / 1024} KB)")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, file.name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(file.name, uri)
        return Intent.createChooser(send, chooserTitle)
    }

    private fun appRows() = listOf(
        "version" to "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}, ${BuildConfig.BUILD_TYPE})",
        "android" to "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
        "device" to "${Build.MANUFACTURER} ${Build.MODEL}",
        "online" to connectivity.online.value.toString(),
        "proxy" to settings.proxy.value.let { if (it.isUsable) it.type.name else "off" },
        "autoSaveMine" to settings.autoSaveMine.value.toString(),
    )

    private fun sessionRows(now: Long): List<Pair<String, String>> {
        val s = session.state.value
        return listOf(
            "hasAccount" to s.hasAccount.toString(),
            "userName set" to s.userName.isNotBlank().toString(),
            "needsLogin" to s.needsLogin.toString(),
            "access token" to when {
                s.accessToken == null -> "none"
                s.hasValidToken -> "valid"
                else -> "expired"
            },
            "token expires" to (s.expiresAt?.let { "${(it - now) / 60_000} min from now" } ?: "-"),
            "refresh token stored" to (s.refreshToken != null).toString(),
        )
    }

    /** Doze, battery restrictions and standby buckets are what usually delays WorkManager. */
    private fun backgroundRows(): List<Pair<String, String>> = buildList {
        val power = context.getSystemService(PowerManager::class.java)
        add("ignoring battery optimizations" to power.isIgnoringBatteryOptimizations(context.packageName).toString())
        add("power save mode" to power.isPowerSaveMode.toString())
        add("device idle (doze)" to power.isDeviceIdleMode.toString())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val activity = context.getSystemService(ActivityManager::class.java)
            add("background restricted" to activity.isBackgroundRestricted.toString())
            val bucket = context.getSystemService(UsageStatsManager::class.java).appStandbyBucket
            add("standby bucket" to standbyBucketName(bucket))
        }
    }

    private suspend fun workRows(name: String): List<WorkSnapshot> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(name).first().map { info ->
            WorkSnapshot(
                uniqueName = name,
                state = info.state.name,
                runAttemptCount = info.runAttemptCount,
                nextRunAt = info.nextScheduleTimeMillis.takeIf { info.state == WorkInfo.State.ENQUEUED && it != Long.MAX_VALUE },
                stopReason = info.stopReason.takeIf { it != WorkInfo.STOP_REASON_NOT_STOPPED }?.toString(),
            )
        }

    private fun standbyBucketName(bucket: Int): String = when (bucket) {
        UsageStatsManager.STANDBY_BUCKET_ACTIVE -> "active"
        UsageStatsManager.STANDBY_BUCKET_WORKING_SET -> "working set"
        UsageStatsManager.STANDBY_BUCKET_FREQUENT -> "frequent"
        UsageStatsManager.STANDBY_BUCKET_RARE -> "rare"
        STANDBY_BUCKET_RESTRICTED -> "restricted"
        else -> "unknown ($bucket)"
    }

    private companion object {
        const val EXPORT_DIR = "diagnostics"
        /** UsageStatsManager.STANDBY_BUCKET_RESTRICTED, only defined from API 30. */
        const val STANDBY_BUCKET_RESTRICTED = 45
        val FILE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    }
}
