package org.wxyc.dj.di

import coil3.ImageLoader
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.wxyc.dj.api.ApiClient
import org.wxyc.dj.api.AuthService
import org.wxyc.dj.api.Configuration
import org.wxyc.dj.api.CookielessHttpClient
import org.wxyc.dj.api.TokenStorage

/**
 * Reaches every top-level binding this app's Hilt graph provides, from
 * outside an `@AndroidEntryPoint` Activity/Fragment or a `@HiltViewModel`.
 *
 * Its only consumer today is the instrumented `HiltGraphDeviceTest` (issue
 * #7's "the Hilt graph actually constructs on device" acceptance
 * criterion): `EntryPointAccessors.fromApplication(context,
 * AppEntryPoint::class.java)` resolves every one of these against the real,
 * installed app's [dagger.hilt.components.SingletonComponent] -- proving the
 * whole production graph, including the Keystore- and DataStore-backed
 * [TokenStorage] provider, actually resolves on a real Android runtime
 * rather than merely compiling. See this repo's `CLAUDE.md`, "The
 * instrumented tier, and why it is not optional".
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface AppEntryPoint {
    fun configuration(): Configuration
    fun cookielessHttpClient(): CookielessHttpClient
    fun tokenStorage(): TokenStorage
    fun authService(): AuthService
    fun apiClient(): ApiClient
    fun imageLoader(): ImageLoader
}
