package org.wxyc.dj.di

import android.content.Context
import coil3.ImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import org.wxyc.dj.api.Configuration
import org.wxyc.dj.api.CookielessHttpClient
import org.wxyc.dj.api.CookielessHttpClientFactory

/**
 * Hilt's home for the plain, credential-unaware transport: [Configuration],
 * the no-cookie OkHttp client, and Coil's [ImageLoader]. Every `@Module`
 * lives in `:app` (never `:api`) because Hilt is Android-only — see this
 * repo's `CLAUDE.md`, "`:api` is a pure JVM module".
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    /**
     * [Configuration.production] unconditionally. `:api`'s `Configuration`
     * KDoc flags `.localDevelopment`'s cleartext-traffic allowance as this
     * issue's concern to resolve; deferred rather than wired here, since it
     * needs a debug-only `network_security_config.xml` and a way to select
     * a preset without the `buildConfig` this app deliberately doesn't
     * enable (see `CLAUDE.md`, "No secrets, deliberately") -- worth a
     * reviewer's second look, not a blocker for the graph this issue ships.
     */
    @Provides
    @Singleton
    fun provideConfiguration(): Configuration = Configuration.production

    /**
     * The sole owner of this app's no-cookie policy (invariant 1). `:api`'s
     * [CookielessHttpClientFactory] is the only way to obtain one — this
     * provider calls it rather than restating the policy, so an `:app`
     * consumer can never reach a raw, cookie-armed `OkHttpClient` instead.
     */
    @Provides
    @Singleton
    fun provideCookielessHttpClient(configuration: Configuration): CookielessHttpClient =
        CookielessHttpClientFactory.create(configuration)

    /**
     * Built over [CookielessHttpClient] as its [okhttp3.Call.Factory] --
     * **not** a default `ImageLoader.Builder(context).build()`, which would
     * make Coil construct its own `OkHttpClient` with cookie handling at its
     * default. That is the same hole `CookielessHttpClient`'s KDoc documents
     * for iOS's `AsyncImage` on `URLSession.shared`: latent today because
     * cover art is served from third-party CDN hosts, fatal the day it is
     * proxied through `api.wxyc.org`. See issue #7's acceptance criteria for
     * why this construction site is the fix rather than a follow-up.
     */
    @Provides
    @Singleton
    fun provideImageLoader(
        @ApplicationContext context: Context,
        callFactory: CookielessHttpClient,
    ): ImageLoader = ImageLoader.Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = callFactory)) }
        .build()
}
