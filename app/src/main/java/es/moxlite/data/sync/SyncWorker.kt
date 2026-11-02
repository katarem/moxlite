package es.moxlite.data.sync

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import es.moxlite.MoxliteApp
import java.util.concurrent.TimeUnit

/**
 * Sincronización en segundo plano (solo con red):
 *  1. Sube la cola de cambios locales (si hay escritor disponible).
 *  2. Actualiza los mazos guardados sin cambios locales para que la copia offline esté al día.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as MoxliteApp).container
        return runCatching {
            container.deckRepository.pushPendingOps()
            container.deckRepository.refreshSavedDecks()
        }.fold(
            onSuccess = { Result.success() },
            onFailure = {
                Log.w(TAG, "Sincronización fallida", it)
                if (runAttemptCount < 3) Result.retry() else Result.failure()
            },
        )
    }

    companion object {
        private const val TAG = "SyncWorker"
        private const val PERIODIC = "moxlite-sync-periodic"
        private const val NOW = "moxlite-sync-now"

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

        fun runNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
