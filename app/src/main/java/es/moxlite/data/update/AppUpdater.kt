package es.moxlite.data.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import es.moxlite.data.remote.Http
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    /** [progress] entre 0 y 1, o null si no se conoce el tamaño. */
    data class Downloading(val info: UpdateInfo, val progress: Float?) : UpdateState
    data class ReadyToInstall(val info: UpdateInfo, val apk: File) : UpdateState
    data class Failed(val message: String, val info: UpdateInfo? = null) : UpdateState
}

/**
 * Busca versiones nuevas en las Releases de GitHub, descarga el APK y abre el instalador del sistema.
 *
 * Android no deja instalar sin confirmación: la app avisa, descarga y lanza el instalador, y el
 * usuario solo tiene que pulsar "Instalar" (la primera vez, además, permitir "instalar apps de
 * esta fuente"). Para que se instale encima, el APK tiene que estar firmado con la misma clave.
 */
class AppUpdater(
    private val context: Context,
    private val client: OkHttpClient,
    /** "owner/repo" de GitHub. */
    private val repository: String,
    val currentVersion: String,
) {
    private val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val updatesDir get() = File(context.cacheDir, "updates")
    private var downloadJob: Job? = null

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** Si el diálogo de actualización debe estar visible. */
    private val _dialogVisible = MutableStateFlow(false)
    val dialogVisible: StateFlow<Boolean> = _dialogVisible.asStateFlow()

    /** Las compilaciones locales ("-dev") no buscan solas: siempre parecerían antiguas. */
    val isDevBuild: Boolean get() = currentVersion.endsWith("-dev")

    /** Comprobación automática al abrir la app, como mucho cada [CHECK_INTERVAL_MS]. */
    fun checkIfDue() {
        if (isDevBuild) return
        if (System.currentTimeMillis() - prefs.getLong(KEY_LAST_CHECK, 0) < CHECK_INTERVAL_MS) return
        if (_state.value !is UpdateState.Idle && _state.value !is UpdateState.UpToDate) return
        scope.launch { check(manual = false) }
    }

    /** "Buscar actualizaciones" desde Ajustes. */
    fun checkNow() {
        scope.launch { check(manual = true) }
    }

    /**
     * Consulta la última Release. En la comprobación automática no se vuelve a ofrecer una
     * versión que el usuario ha decidido omitir; en la manual, sí.
     */
    private suspend fun check(manual: Boolean) {
        if (_state.value is UpdateState.Downloading) return
        _state.value = UpdateState.Checking
        val result = runCatching { fetchLatest() }
        prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
        result.fold(
            onSuccess = { release ->
                val info = release?.let { UpdateChecker.evaluate(it, currentVersion) }
                when {
                    info == null -> {
                        _state.value = UpdateState.UpToDate
                        withContext(Dispatchers.IO) { updatesDir.deleteRecursively() }
                    }
                    !manual && prefs.getString(KEY_SKIPPED, null) == info.version -> _state.value = UpdateState.Idle
                    else -> {
                        _state.value = UpdateState.Available(info)
                        _dialogVisible.value = true
                    }
                }
            },
            onFailure = { e ->
                // Sin conexión al abrir la app no merece un aviso; en la manual, sí.
                _state.value = if (manual) UpdateState.Failed("No se pudo comprobar: ${e.message ?: e::class.java.simpleName}") else UpdateState.Idle
            },
        )
    }

    fun showDialog() {
        if (_state.value.info != null) _dialogVisible.value = true
    }

    /** "Más tarde": oculta el aviso; una descarga en curso sigue en segundo plano. */
    fun dismiss() {
        _dialogVisible.value = false
    }

    /** "Omitir esta versión": no se vuelve a avisar de ella automáticamente. */
    fun skip(info: UpdateInfo) {
        prefs.edit().putString(KEY_SKIPPED, info.version).apply()
        _dialogVisible.value = false
        _state.value = UpdateState.Idle
    }

    fun download(info: UpdateInfo) {
        if (downloadJob?.isActive == true) return
        _dialogVisible.value = true
        downloadJob = scope.launch {
            _state.value = UpdateState.Downloading(info, null)
            try {
                val apk = withContext(Dispatchers.IO) { downloadApk(info) }
                _state.value = UpdateState.ReadyToInstall(info, apk)
                install(info, apk)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = UpdateState.Failed("No se pudo descargar: ${e.message ?: e::class.java.simpleName}", info)
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        val info = _state.value.info
        _state.value = if (info != null) UpdateState.Available(info) else UpdateState.Idle
    }

    /** Abre el instalador del sistema con el APK descargado. */
    fun install(info: UpdateInfo, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            _state.value = UpdateState.Failed("No se encontró el instalador de Android.", info)
        }
    }

    /** Alternativa si falla la instalación: abrir la Release en el navegador. */
    fun openReleasePage(info: UpdateInfo) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(info.pageUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    private suspend fun fetchLatest(): GhRelease? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://api.github.com/repos/$repository/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "Moxlite/$currentVersion")
            .build()
        client.newCall(request).execute().use { response ->
            when {
                response.code == 404 -> null // todavía no hay ninguna Release
                !response.isSuccessful -> throw IOException("GitHub ha respondido ${response.code}")
                else -> Http.json.decodeFromString(GhRelease.serializer(), response.body!!.string())
            }
        }
    }

    private suspend fun downloadApk(info: UpdateInfo): File {
        val dir = updatesDir.apply { deleteRecursively(); mkdirs() }
        val target = File(dir, "moxlite-${info.version}.apk")
        val partial = File(dir, target.name + ".part")
        val request = Request.Builder().url(info.apkUrl).header("User-Agent", "Moxlite/$currentVersion").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("GitHub ha respondido ${response.code}")
            val body = response.body ?: throw IOException("Respuesta vacía")
            val total = body.contentLength().takeIf { it > 0 } ?: info.apkSize.takeIf { it > 0 }
            body.byteStream().use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var copied = 0L
                    var lastProgress = -1
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        if (total != null) {
                            val percent = (copied * 100 / total).toInt()
                            if (percent != lastProgress) {
                                lastProgress = percent
                                _state.value = UpdateState.Downloading(info, (copied.toFloat() / total).coerceIn(0f, 1f))
                            }
                        }
                    }
                }
            }
        }
        if (!partial.renameTo(target)) throw IOException("No se pudo guardar el APK")
        return target
    }

    companion object {
        private const val KEY_LAST_CHECK = "last_check"
        private const val KEY_SKIPPED = "skipped_version"
        private const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
        private const val APK_MIME = "application/vnd.android.package-archive"
    }
}

/** La versión a la que se refiere el estado, si hay alguna. */
val UpdateState.info: UpdateInfo?
    get() = when (this) {
        is UpdateState.Available -> info
        is UpdateState.Downloading -> info
        is UpdateState.ReadyToInstall -> info
        is UpdateState.Failed -> info
        else -> null
    }
