package es.moxlite.data.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import es.moxlite.MainActivity
import es.moxlite.MoxliteApp
import es.moxlite.R
import java.util.concurrent.TimeUnit

/**
 * Comprueba en segundo plano (cada 12 h, solo con red) si hay una Release nueva en GitHub y,
 * si la hay, muestra una notificación. Al tocarla se abre la app con el diálogo de actualizar.
 */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val updater = (applicationContext as MoxliteApp).container.updater
        if (updater.isDevBuild || !updater.backgroundEnabled.value) return Result.success()
        return try {
            updater.findUpdateToNotify()?.let { info ->
                if (UpdateNotifications.show(applicationContext, info)) updater.markNotified(info)
            }
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo comprobar si hay versión nueva", e)
            if (runAttemptCount < 3) Result.retry() else Result.success()
        }
    }

    companion object {
        private const val TAG = "UpdateWorker"
        private const val PERIODIC = "moxlite-update-check"

        fun schedule(context: Context, enabled: Boolean) {
            val work = WorkManager.getInstance(context)
            if (!enabled) {
                work.cancelUniqueWork(PERIODIC)
                return
            }
            val request = PeriodicWorkRequestBuilder<UpdateWorker>(12, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(1, TimeUnit.HOURS)
                .build()
            work.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}

object UpdateNotifications {
    private const val CHANNEL_ID = "updates"
    private const val NOTIFICATION_ID = 1001

    /** Extra del Intent de MainActivity para abrir directamente el diálogo de actualización. */
    const val EXTRA_SHOW_UPDATE = "es.moxlite.SHOW_UPDATE"

    fun createChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL_ID, "Actualizaciones", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Aviso cuando hay una versión nueva de Moxlite"
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** Si Android deja mostrar notificaciones de la app (permiso en 13+ y no bloqueadas). */
    fun canNotify(context: Context): Boolean {
        val permission = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return permission && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /** Devuelve false si no se ha podido mostrar (sin permiso o notificaciones bloqueadas). */
    fun show(context: Context, info: UpdateInfo): Boolean {
        if (!canNotify(context)) return false
        createChannel(context)
        val open = Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_SHOW_UPDATE, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(
            context, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = "Toca para ver las novedades e instalarla."
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_update)
            .setContentTitle("Moxlite ${info.version} disponible")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(if (info.notes.isBlank()) text else "$text\n\n${info.notes.take(500)}"))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        return try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
            true
        } catch (e: SecurityException) {
            false
        }
    }
}
