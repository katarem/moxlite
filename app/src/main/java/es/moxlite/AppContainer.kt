package es.moxlite

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import es.moxlite.data.local.AppDatabase
import es.moxlite.data.remote.Http
import es.moxlite.data.remote.MoxError
import es.moxlite.data.remote.isCloudflareChallenge
import es.moxlite.data.remote.moxfield.MoxfieldService
import es.moxlite.data.remote.scryfall.ScryfallService
import es.moxlite.data.repo.CardRepository
import es.moxlite.data.repo.DeckRepository
import es.moxlite.data.session.ApiTrafficLog
import es.moxlite.data.session.MoxfieldWebBridge
import es.moxlite.data.session.SessionManager
import es.moxlite.data.session.WebViewCookieJar
import es.moxlite.data.sync.PendingDiscoveryWriter
import es.moxlite.data.update.AppUpdater
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

/** Inyección de dependencias manual (sin Hilt para mantener el proyecto simple). */
class AppContainer(private val context: Context) {

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
    }

    private val baseClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Cliente para api2.moxfield.com: cabeceras de navegador + cookies del WebView + token. */
    private val moxfieldClient = baseClient.newBuilder()
        .cookieJar(WebViewCookieJar())
        .addInterceptor(Interceptor { chain ->
            val s = session.state.value
            val request = chain.request().newBuilder()
                .header("User-Agent", session.userAgent)
                .header("Accept", "application/json, text/plain, */*")
                .header("Origin", "https://moxfield.com")
                .header("Referer", "https://moxfield.com/")
                .apply { s.accessToken?.takeIf { !s.isExpired }?.let { header("Authorization", "Bearer $it") } }
                .build()
            val response = chain.proceed(request)
            when {
                response.isCloudflareChallenge() -> { response.close(); throw MoxError.CloudflareBlocked() }
                response.code == 401 -> { response.close(); session.markExpired(); throw MoxError.Unauthorized() }
                response.code == 404 -> { response.close(); throw MoxError.NotFound() }
                response.code == 429 -> {
                    val retry = response.header("Retry-After")?.toLongOrNull()
                    response.close(); throw MoxError.RateLimited(retry)
                }
                response.code >= 500 -> { val code = response.code; response.close(); throw MoxError.Server(code) }
                else -> response
            }
        })
        .addInterceptor(logging)
        .build()

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
        writer = PendingDiscoveryWriter(),
        imageLoader = imageLoader,
    )
}

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
