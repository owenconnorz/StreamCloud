package com.aioweb.app.data

import android.content.Context
import com.aioweb.app.BuildConfig
import com.aioweb.app.data.api.AioWebBackendApi
import com.aioweb.app.data.api.TmdbApi
import com.aioweb.app.data.network.Net
import com.aioweb.app.data.nuvio.NuvioRepository
import com.aioweb.app.data.plugins.PluginRepository
import com.aioweb.app.data.stremio.StremioRepository
import kotlinx.coroutines.flow.first

/**
 * Single application-wide service locator (no DI framework to keep build simple).
 */
class ServiceLocator(context: Context) {
    val settings = SettingsRepository(context.applicationContext)
    val plugins = PluginRepository(context.applicationContext)
    val stremio = StremioRepository(context.applicationContext)
    val nuvio = NuvioRepository(context.applicationContext)

    val tmdb: TmdbApi = Net.retrofit("https://api.themoviedb.org/").create(TmdbApi::class.java)
    val tmdbApiKey: String = BuildConfig.TMDB_API_KEY

    suspend fun backend(): AioWebBackendApi {
        val url = settings.backendUrl.first()
        return Net.retrofit(url).create(AioWebBackendApi::class.java)
    }

    companion object {
        @Volatile private var I: ServiceLocator? = null
        fun get(ctx: Context): ServiceLocator =
            I ?: synchronized(this) { I ?: ServiceLocator(ctx).also { I = it } }
    }
}
