package es.moxlite.data.sync

import android.content.Context
import android.os.Build
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import es.moxlite.MoxliteApp
import es.moxlite.data.diagnostics.DiagnosticLog
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * Sincronización en segundo plano (solo con red):
 *  1. Sube la cola de cambios locales a Moxfield (si hay sesión).
 *  2. Trae y guarda sin conexión los mazos de la cuenta (si está activado en Ajustes).
 *  3. Actualiza los mazos guardados sin cambios locales para que la copia offline esté al día.
 *
 * Si Moxfield no responde (bloqueo durante el fútbol, caída…), WorkManager reintenta más tarde
 * con espera creciente: los cambios se suben solos cuando vuelva.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as MoxliteApp).container
        val log = container.diagnostics
        val onlyPush = inputData.getBoolean(KEY_ONLY_PUSH, false)
        val run = "Worker ${id.toString().take(8)} (${if (onlyPush) "push" else "full"}, attempt ${runAttemptCount + 1})"
        log.i(TAG, "$run started: account=${container.session.state.value.hasAccount}, online=${container.connectivity.online.value}")
        val result = try {
            val repo = container.deckRepository
            val pushed = repo.pushPendingOps()
            if (!onlyPush) {
                if (container.settings.autoSaveMine.value && container.session.state.value.hasAccount) {
                    repo.syncMine(download = true)
                }
                repo.refreshSavedDecks()
            }
            if (pushed) Result.success() else Result.retry()
        } catch (e: CancellationException) {
            log.w(TAG, "$run stopped by the system (stop reason ${stopReasonOrUnknown()})")
            throw e
        } catch (e: Exception) {
            log.w(TAG, "$run failed", e)
            if (runAttemptCount < 5) Result.retry() else Result.failure()
        }
        val outcome = when (result) {
            is Result.Success -> "success"
            is Result.Retry -> "retry later"
            else -> "failed"
        }
        log.i(TAG, "$run finished: $outcome")
        return result
    }

    private fun stopReasonOrUnknown(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) stopReason.toString() else "unknown"

    companion object {
        private const val TAG = "SyncWorker"
        private const val PERIODIC = "moxlite-sync-periodic"
        private const val NOW = "moxlite-sync-now"
        private const val FULL = "$NOW-full"
        private const val KEY_ONLY_PUSH = "only_push"

        /** Unique work names, to report their state in the diagnostics. */
        val uniqueWorkNames = listOf(PERIODIC, NOW, FULL)

        private val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(12, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /**
         * Subir los cambios pendientes en cuanto haya red. Espera unos segundos para juntar
         * ediciones seguidas; si ya hay una subida en marcha, esa misma recoge lo nuevo (repasa la
         * cola hasta vaciarla).
         */
        fun pushSoon(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints)
                .setInitialDelay(3, TimeUnit.SECONDS)
                .setInputData(workDataOf(KEY_ONLY_PUSH to true))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, request)
            context.diagnostics()?.d(TAG, "Push requested (unique work $NOW, KEEP: ignored while another one is queued)")
        }

        /** Sincronización completa a petición del usuario. */
        fun runNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(FULL, ExistingWorkPolicy.REPLACE, request)
            context.diagnostics()?.i(TAG, "Full sync requested by the user (unique work $FULL, REPLACE)")
        }

        private fun Context.diagnostics(): DiagnosticLog? = (applicationContext as? MoxliteApp)?.let { it.container.diagnostics }
    }
}
