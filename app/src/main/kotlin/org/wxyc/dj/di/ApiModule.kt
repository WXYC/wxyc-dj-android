package org.wxyc.dj.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import org.wxyc.dj.api.ApiClient
import org.wxyc.dj.api.AuthService
import org.wxyc.dj.api.Configuration
import org.wxyc.dj.api.CookielessHttpClient
import org.wxyc.dj.api.TokenStorage

/**
 * Wires `:api`'s auth/session layer into the Hilt graph (issue #7):
 * [AuthService] (the better-auth session lifecycle) and [ApiClient] (the
 * typed HTTP surface built over it). Both are plain constructors in a pure
 * JVM module -- this file exists only because Hilt itself is Android-only,
 * per this repo's `CLAUDE.md`.
 */
@Module
@InstallIn(SingletonComponent::class)
object ApiModule {

    @Provides
    @Singleton
    fun provideAuthService(
        configuration: Configuration,
        tokenStorage: TokenStorage,
        callFactory: CookielessHttpClient,
    ): AuthService = AuthService(configuration, tokenStorage, callFactory)

    @Provides
    @Singleton
    fun provideApiClient(
        configuration: Configuration,
        callFactory: CookielessHttpClient,
        authService: AuthService,
    ): ApiClient = ApiClient(configuration, callFactory, authService)
}
