package es.moxlite.data.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Lógica pura de actualizaciones (sin Android) para poder probarla con tests JVM.

/** Respuesta parcial de GET /repos/{owner}/{repo}/releases/latest de la API de GitHub. */
@Serializable
data class GhRelease(
    @SerialName("tag_name") val tagName: String = "",
    val name: String? = null,
    val body: String? = null,
    @SerialName("html_url") val htmlUrl: String = "",
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    @SerialName("published_at") val publishedAt: String? = null,
    val assets: List<GhAsset> = emptyList(),
)

@Serializable
data class GhAsset(
    val name: String = "",
    @SerialName("browser_download_url") val downloadUrl: String = "",
    val size: Long = 0,
)

/** Versión numérica tipo 0.1.42 (admite prefijo "v" y sufijos como "-dev"). */
data class AppVersion(val parts: List<Int>) : Comparable<AppVersion> {

    override fun compareTo(other: AppVersion): Int {
        for (i in 0 until maxOf(parts.size, other.parts.size)) {
            val diff = parts.getOrElse(i) { 0 } - other.parts.getOrElse(i) { 0 }
            if (diff != 0) return diff
        }
        return 0
    }

    override fun toString() = parts.joinToString(".")

    companion object {
        private val VERSION = Regex("""^[vV]?(\d+(?:\.\d+)*)""")

        fun parse(text: String): AppVersion? =
            VERSION.find(text.trim())?.groupValues?.get(1)?.split('.')?.map { it.toInt() }?.let(::AppVersion)
    }
}

/** Una versión nueva lista para descargar. */
data class UpdateInfo(
    val version: String,
    val apkUrl: String,
    val apkSize: Long,
    val notes: String,
    val pageUrl: String,
)

object UpdateChecker {
    /** Nombre del APK que publica el workflow de GitHub Actions. */
    const val APK_NAME = "moxlite.apk"

    /** Devuelve la actualización si [release] es más nueva que [currentVersion] y trae un APK. */
    fun evaluate(release: GhRelease, currentVersion: String): UpdateInfo? {
        if (release.draft || release.prerelease) return null
        val remote = AppVersion.parse(release.tagName) ?: return null
        val current = AppVersion.parse(currentVersion) ?: return null
        if (remote <= current) return null
        val apk = release.assets.firstOrNull { it.name == APK_NAME }
            ?: release.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
            ?: return null
        return UpdateInfo(
            version = remote.toString(),
            apkUrl = apk.downloadUrl,
            apkSize = apk.size,
            notes = cleanNotes(release.body.orEmpty()),
            pageUrl = release.htmlUrl,
        )
    }

    /**
     * Si la comprobación en segundo plano debe notificar [version]: no se avisa de una versión
     * omitida ni dos veces de la misma.
     */
    fun shouldNotify(version: String, skipped: String?, lastNotified: String?): Boolean =
        version != skipped && version != lastNotified

    /** Notas de la Release en texto plano: sin comentarios HTML, encabezados ni enlaces de comparación. */
    fun cleanNotes(markdown: String): String =
        markdown
            .replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
            .lineSequence()
            .map { it.trimEnd() }
            .filterNot { it.startsWith("**Full Changelog**") }
            .map { it.replace(Regex("^#+\\s*"), "").replace("**", "").replace("`", "") }
            .joinToString("\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
}
