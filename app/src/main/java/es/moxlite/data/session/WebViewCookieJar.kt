package es.moxlite.data.session

import android.webkit.CookieManager
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * CookieJar de OkHttp respaldado por el CookieManager del WebView.
 *
 * Así las cookies que consigue el WebView (cf_clearance de Cloudflare, cookies de sesión de
 * Moxfield) las reutilizan las peticiones nativas, y al revés.
 */
class WebViewCookieJar : CookieJar {

    private val manager: CookieManager get() = CookieManager.getInstance()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val target = url.toString()
        cookies.forEach { manager.setCookie(target, it.toString()) }
        manager.flush()
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val raw = manager.getCookie(url.toString()) ?: return emptyList()
        return raw.split(";").mapNotNull { Cookie.parse(url, it.trim()) }
    }
}
