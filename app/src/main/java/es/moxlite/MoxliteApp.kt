package es.moxlite

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import es.moxlite.data.sync.SyncWorker

class MoxliteApp : Application(), ImageLoaderFactory {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        SyncWorker.schedulePeriodic(this)
    }

    /** Coil usa este cargador (caché en disco persistente) en toda la app. */
    override fun newImageLoader(): ImageLoader = container.imageLoader
}
