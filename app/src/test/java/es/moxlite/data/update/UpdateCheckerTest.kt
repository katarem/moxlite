package es.moxlite.data.update

import es.moxlite.data.remote.Http
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    private fun release(
        tag: String,
        assets: List<GhAsset> = listOf(GhAsset("moxlite.apk", "https://example.com/moxlite.apk", 1234)),
        draft: Boolean = false,
        prerelease: Boolean = false,
        body: String? = null,
    ) = GhRelease(tagName = tag, body = body, htmlUrl = "https://github.com/x/y/releases/tag/$tag", draft = draft, prerelease = prerelease, assets = assets)

    @Test
    fun `parsea versiones con prefijo v y sufijos`() {
        assertEquals(AppVersion(listOf(0, 1, 42)), AppVersion.parse("v0.1.42"))
        assertEquals(AppVersion(listOf(0, 1)), AppVersion.parse("0.1-dev"))
        assertEquals(AppVersion(listOf(2)), AppVersion.parse(" V2 "))
        assertNull(AppVersion.parse("latest"))
        assertNull(AppVersion.parse(""))
    }

    @Test
    fun `compara versiones numericamente y no como texto`() {
        assertTrue(AppVersion.parse("0.1.10")!! > AppVersion.parse("0.1.9")!!)
        assertTrue(AppVersion.parse("0.2.1")!! > AppVersion.parse("0.1.99")!!)
        assertTrue(AppVersion.parse("1.0")!! > AppVersion.parse("0.9.9")!!)
        assertEquals(0, AppVersion.parse("0.1")!!.compareTo(AppVersion.parse("0.1.0")!!))
        assertEquals("0.1.42", AppVersion.parse("v0.1.42").toString())
    }

    @Test
    fun `ofrece la version nueva con su APK`() {
        val info = UpdateChecker.evaluate(release("v0.1.42"), "0.1.40")
        assertNotNull(info)
        assertEquals("0.1.42", info!!.version)
        assertEquals("https://example.com/moxlite.apk", info.apkUrl)
        assertEquals(1234L, info.apkSize)
        assertEquals("https://github.com/x/y/releases/tag/v0.1.42", info.pageUrl)
    }

    @Test
    fun `no ofrece nada si ya esta al dia o es mas antigua`() {
        assertNull(UpdateChecker.evaluate(release("v0.1.42"), "0.1.42"))
        assertNull(UpdateChecker.evaluate(release("v0.1.41"), "0.1.42"))
    }

    @Test
    fun `ignora borradores, prereleases, tags raros y releases sin APK`() {
        assertNull(UpdateChecker.evaluate(release("v0.2.0", draft = true), "0.1.0"))
        assertNull(UpdateChecker.evaluate(release("v0.2.0", prerelease = true), "0.1.0"))
        assertNull(UpdateChecker.evaluate(release("nightly"), "0.1.0"))
        assertNull(UpdateChecker.evaluate(release("v0.2.0", assets = listOf(GhAsset("notas.txt", "u"))), "0.1.0"))
        assertNull(UpdateChecker.evaluate(release("v0.2.0"), "desconocida"))
    }

    @Test
    fun `prefiere moxlite apk pero acepta otro APK`() {
        val assets = listOf(GhAsset("otro.APK", "https://e/otro"), GhAsset("moxlite.apk", "https://e/mox"))
        assertEquals("https://e/mox", UpdateChecker.evaluate(release("v1.0", assets), "0.9")!!.apkUrl)
        assertEquals("https://e/otro", UpdateChecker.evaluate(release("v1.0", assets.take(1)), "0.9")!!.apkUrl)
    }

    @Test
    fun `una compilacion local dev ve cualquier release como nueva`() {
        assertNotNull(UpdateChecker.evaluate(release("v0.1.1"), "0.1-dev"))
    }

    @Test
    fun `la notificacion no repite ni avisa de versiones omitidas`() {
        assertTrue(UpdateChecker.shouldNotify("0.1.5", skipped = null, lastNotified = null))
        assertTrue(UpdateChecker.shouldNotify("0.1.6", skipped = "0.1.5", lastNotified = "0.1.5"))
        assertFalse(UpdateChecker.shouldNotify("0.1.5", skipped = "0.1.5", lastNotified = null))
        assertFalse(UpdateChecker.shouldNotify("0.1.5", skipped = null, lastNotified = "0.1.5"))
    }

    @Test
    fun `limpia las notas generadas por GitHub`() {
        val body = """
            Instalar: descarga `moxlite.apk` en el móvil y ábrelo.
            <!-- Release notes generated using configuration in .github/release.yml -->

            ## What's Changed
            * **Añadir** tests by @alguien in https://github.com/x/y/pull/1



            **Full Changelog**: https://github.com/x/y/commits/v0.1.5
        """.trimIndent()
        assertEquals(
            "Instalar: descarga moxlite.apk en el móvil y ábrelo.\n\nWhat's Changed\n* Añadir tests by @alguien in https://github.com/x/y/pull/1",
            UpdateChecker.cleanNotes(body),
        )
    }

    @Test
    fun `parsea la respuesta real de la API de GitHub`() {
        val json = """
            {"url":"https://api.github.com/repos/x/y/releases/1","html_url":"https://github.com/x/y/releases/tag/v0.1.7",
             "id":1,"tag_name":"v0.1.7","name":"Moxlite 0.1.7","draft":false,"prerelease":false,
             "published_at":"2026-11-02T20:00:00Z","body":"Notas",
             "author":{"login":"github-actions[bot]"},
             "assets":[{"id":9,"name":"moxlite.apk","label":"Moxlite 0.1.7 (APK)","content_type":"application/vnd.android.package-archive",
                        "size":8123456,"browser_download_url":"https://github.com/x/y/releases/download/v0.1.7/moxlite.apk"}]}
        """.trimIndent()
        val release = Http.json.decodeFromString(GhRelease.serializer(), json)
        val info = UpdateChecker.evaluate(release, "0.1.6")!!
        assertEquals("0.1.7", info.version)
        assertEquals(8123456L, info.apkSize)
        assertEquals("https://github.com/x/y/releases/download/v0.1.7/moxlite.apk", info.apkUrl)
        assertEquals("Notas", info.notes)
    }
}
