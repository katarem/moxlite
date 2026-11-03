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
import androidx.work.workDataOf
import es.moxlite.MoxliteApp
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
        val onlyPush = inputData.getBoolean(KEY_ONLY_PUSH, false)
        return runCatching {
            val repo = container.deckRepository
            val pushed = repo.pushPendingOps()
            if (!onlyPush) {
                if (container.settings.autoSaveMine.value && container.session.state.value.hasAccount) {
                    repo.syncMine(download = true)
                }
                repo.refreshSavedDecks()
            }
            pushed
        }.fold(
            onSuccess = { complete -> if (complete) Result.success() else Result.retry() },
            onFailure = {
                Log.w(TAG, "Sincronización fallida", it)
                if (runAttemptCount < 5) Result.retry() else Result.failure()
            },
        )
    }

    companion object {
        private const val TAG = "SyncWorker"
        private const val PERIODIC = "moxlite-sync-periodic"
        private const val NOW = "moxlite-sync-now"
        private const val KEY_ONLY_PUSH = "only_push"

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
        }

        /** Sincronización completa a petición del usuario. */
        fun runNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("$NOW-full", ExistingWorkPolicy.REPLACE, request)
        }
    }
}
