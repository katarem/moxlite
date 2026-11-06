package es.moxlite

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import es.moxlite.data.builder.BuilderRepository
import es.moxlite.data.local.AppDatabase
import es.moxlite.data.remote.Http
import es.moxlite.data.remote.MoxError
import es.moxlite.data.remote.edhrec.EdhrecClient
import es.moxlite.data.remote.isCloudflareChallenge
import es.moxlite.data.remote.looksLikeBlocking
import es.moxlite.data.remote.moxfield.MoxfieldService
import es.moxlite.data.remote.scryfall.ScryfallService
import es.moxlite.data.repo.CardRepository
import es.moxlite.data.repo.DeckRepository
import es.moxlite.data.session.ApiTrafficLog
import es.moxlite.data.session.MoxfieldAuth
import es.moxlite.data.session.MoxfieldWebBridge
import es.moxlite.data.session.SessionManager
import es.moxlite.data.session.WebViewCookieJar
import es.moxlite.data.settings.AppSettings
import es.moxlite.data.settings.ProxySettings
import es.moxlite.data.settings.ProxyType
import es.moxlite.data.sync.MoxfieldApiWriter
import es.moxlite.data.update.AppUpdater
import es.moxlite.i18n.I18n
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.TimeUnit

/** Inyección de dependencias manual (sin Hilt para mantener el proyecto simple). */
class AppContainer(private val context: Context) {

    /** Ámbito de la app (vive lo que vive el proceso). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings = AppSettings(context)
    val session = SessionManager(context)
    val apiLog = ApiTrafficLog()
    val webBridge = MoxfieldWebBridge(session, apiLog)
    val connectivity = Connectivity(context)

    private val database = AppDatabase.build(context)
    private val jsonMedia = "application/json".toMediaType()

    private val logging = HttpLoggingInterceptor().apply {
        level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC else HttpLoggingInterceptor.Level.NONE
        redactHeader("Authorization")
        redactHeader("Cookie")
        redactHeader("Set-Cookie")
    }

    /** El proxy de Ajustes se aplica al vuelo a todas las peticiones (Moxfield, Scryfall, imágenes). */
    private val proxySelector = object : ProxySelector() {
        override fun select(uri: URI?): List<Proxy> = listOf(settings.proxy.value.toJavaProxy())
        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
    }

    private val baseClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .proxySelector(proxySelector)
        .build()

    val auth = MoxfieldAuth(session, baseClient)

    /** Cliente para api2.moxfield.com: cabeceras de navegador + cookies del WebView + token. */
    private val moxfieldClient = baseClient.newBuilder()
        .cookieJar(WebViewCookieJar())
        .addInterceptor(Interceptor { chain -> moxfieldCall(chain) })
        .addInterceptor(logging)
        .build()

    private fun moxfieldCall(chain: Interceptor.Chain): Response {
        // Token caducado: se renueva antes de la petición (con la cookie de sesión).
        if (session.state.value.hasAccount && !session.state.value.hasValidToken) auth.refreshBlocking()

        var response = proceed(chain, authorized(chain.request()))
        if (response.code == 401 && session.state.value.hasAccount) {
            response.close()
            session.dropAccessToken()
            if (auth.refreshBlocking(force = true)) response = proceed(chain, authorized(chain.request()))
            else return failWith(response, MoxError.Unauthorized())
        }
        return when {
            response.isCloudflareChallenge() -> failWith(response, MoxError.CloudflareBlocked())
            response.code == 401 -> failWith(response, MoxError.Unauthorized())
            response.code == 404 -> failWith(response, MoxError.NotFound())
            response.code == 429 -> failWith(response, MoxError.RateLimited(response.header("Retry-After")?.toLongOrNull()))
            response.code >= 500 -> failWith(response, MoxError.Server(response.code))
            else -> response
        }
    }

    private fun authorized(request: Request): Request {
        val s = session.state.value
        return request.newBuilder()
            .header("User-Agent", session.userAgent)
            .header("Accept", "application/json, text/plain, */*")
            .header("Origin", "https://moxfield.com")
            .header("Referer", "https://moxfield.com/")
            .apply { s.accessToken?.takeIf { s.hasValidToken }?.let { header("Authorization", "Bearer $it") } }
            .build()
    }

    /** Con red pero sin respuesta de Moxfield = bloqueo (fútbol) o caída: mensaje específico. */
    private fun proceed(chain: Interceptor.Chain, request: Request): Response = try {
        chain.proceed(request)
    } catch (e: IOException) {
        if (connectivity.online.value && e.looksLikeBlocking()) throw MoxError.Unreachable(e) else throw e
    }

    private fun failWith(response: Response, error: MoxError): Nothing {
        response.close()
        throw error
    }

    /** Scryfall pide User-Agent identificable y Accept explícito. */
    private val scryfallClient = baseClient.newBuilder()
        .addInterceptor(Interceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", "Moxlite/${BuildConfig.VERSION_NAME} (cliente personal Android)")
                    .header("Accept", "application/json;q=0.9,*/*;q=0.8")
                    .build()
            )
        })
        .addInterceptor(logging)
        .build()

    /** EDHREC (json.edhrec.com y edhrec.com/api): User-Agent identificable, como con Scryfall. */
    private val edhrecHttp = baseClient.newBuilder()
        .addInterceptor(Interceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", "Moxlite/${BuildConfig.VERSION_NAME} (cliente personal Android)")
                    .header("Accept", "application/json")
                    .build()
            )
        })
        .addInterceptor(logging)
        .build()

    private val moxfieldService: MoxfieldService = Retrofit.Builder()
        .baseUrl("https://api2.moxfield.com/")
        .client(moxfieldClient)
        .addConverterFactory(Http.json.asConverterFactory(jsonMedia))
        .build()
        .create(MoxfieldService::class.java)

    private val scryfallService: ScryfallService = Retrofit.Builder()
        .baseUrl("https://api.scryfall.com/")
        .client(scryfallClient)
        .addConverterFactory(Http.json.asConverterFactory(jsonMedia))
        .build()
        .create(ScryfallService::class.java)

    /**
     * Imágenes en filesDir (no en cache) para que Android no las borre y funcionen sin conexión.
     * Se ignoran las cabeceras de caché: una imagen de carta no cambia.
     */
    val imageLoader: ImageLoader = ImageLoader.Builder(context)
        .okHttpClient(scryfallClient)
        .respectCacheHeaders(false)
        .memoryCache { MemoryCache.Builder(context).maxSizePercent(0.20).build() }
        .diskCache {
            DiskCache.Builder()
                .directory(context.filesDir.resolve("card_images"))
                .maxSizeBytes(1_500L * 1024 * 1024)
                .build()
        }
        .crossfade(true)
        .build()

    val cardRepository = CardRepository(scryfallService)

    /** Actualizaciones desde las Releases de GitHub. */
    val updater = AppUpdater(
        context = context,
        client = baseClient.newBuilder().readTimeout(60, TimeUnit.SECONDS).build(),
        repository = BuildConfig.UPDATE_REPOSITORY,
        currentVersion = BuildConfig.VERSION_NAME,
    )

    val deckRepository = DeckRepository(
        context = context,
        moxfield = moxfieldService,
        cards = cardRepository,
        db = database,
        session = session,
        writer = MoxfieldApiWriter(moxfieldService, session),
        imageLoader = imageLoader,
        settings = settings,
    )

    /** Asistente de construcción de mazos con EDHREC. */
    val builderRepository = BuilderRepository(
        edhrec = EdhrecClient(edhrecHttp, context.filesDir.resolve("edhrec")),
        cards = cardRepository,
        decks = deckRepository,
        dir = context.filesDir.resolve("builder"),
    )

    init {
        I18n.apply(settings.appLanguage.value)
        settings.appLanguage.drop(1).onEach { I18n.apply(it) }.launchIn(appScope)
        // Al cambiar el proxy, cerrar conexiones abiertas para que las nuevas lo usen.
        settings.proxy.drop(1).onEach {
            listOf(baseClient, moxfieldClient, scryfallClient, edhrecHttp).forEach { c -> c.connectionPool.evictAll() }
        }.launchIn(appScope)
    }
}

fun ProxySettings.toJavaProxy(): Proxy =
    if (!isUsable) Proxy.NO_PROXY
    else Proxy(
        if (type == ProxyType.SOCKS) Proxy.Type.SOCKS else Proxy.Type.HTTP,
        InetSocketAddress.createUnresolved(host.trim(), port),
    )

/** Estado de red observable para el banner "sin conexión". */
class Connectivity(context: Context) {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private val _online = MutableStateFlow(isOnlineNow())
    val online: StateFlow<Boolean> = _online.asStateFlow()

    init {
        manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { _online.value = true }
            override fun onLost(network: Network) { _online.value = isOnlineNow() }
        })
    }

    private fun isOnlineNow(): Boolean {
        val caps = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
