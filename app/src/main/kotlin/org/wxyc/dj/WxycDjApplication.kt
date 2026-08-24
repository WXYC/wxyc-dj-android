package org.wxyc.dj

import android.app.Application
import coil3.ImageLoader
import coil3.SingletonImageLoader
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * The app's Hilt entry point (issue #7). `@HiltAndroidApp` generates the
 * base [dagger.hilt.android.internal.managers.ApplicationComponentManager]
 * that every `@AndroidEntryPoint`/`@HiltViewModel`/[dagger.hilt.EntryPoint]
 * in this app resolves against — everything else in `di/` installs into the
 * [dagger.hilt.components.SingletonComponent] this class roots.
 *
 * Also the one place Coil's process-wide [SingletonImageLoader] is set, over
 * the [ImageLoader] `di/NetworkModule.kt` builds on
 * [org.wxyc.dj.api.CookielessHttpClient] — see that provider's KDoc for why
 * a default Coil-constructed `OkHttpClient` (which would arm cookie
 * handling at its default) is the exact hole this closes.
 */
@HiltAndroidApp
class WxycDjApplication : Application() {
    @Inject
    lateinit var imageLoader: ImageLoader

    override fun onCreate() {
        super.onCreate()
        SingletonImageLoader.setSafe { imageLoader }
    }
}
