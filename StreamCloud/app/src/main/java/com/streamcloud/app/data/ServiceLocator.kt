package com.streamcloud.app.data

import android.content.Context
import com.streamcloud.app.BuildConfig
import com.streamcloud.app.data.api.StreamCloudBackendApi
import com.streamcloud.app.data.api.TmdbApi
import com.streamcloud.app.data.network.Net
import com.streamcloud.app.data.nuvio.NuvioRepository
import com.streamcloud.app.data.plugins.PluginRepository
import com.streamcloud.app.data.stremio.StremioRepository
import kotlinx.coroutines.flow.first

class ServiceLocator(context: Context) {
    val settings = SettingsRepository(context.applicationContext)
    val plugins = PluginRepository(context.applicationContext)
    val stremio = StremioRepository(context.applicationContext)
    val nuvio = NuvioRepository(context.applicationContext)

    val tmdb: TmdbApi = Net.retrofit("https://api.themoviedb.org/").create(TmdbApi::class.java)
    val tmdbApiKey: String = BuildConfig.TMDB_API_KEY

    suspend fun backend(): StreamCloudBackendApi {
        val url = settings.backendUrl.first()
        return Net.retrofit(url).create(StreamCloudBackendApi::class.java)
    }

    companion object {
        @Volatile private var I: ServiceLocator? = null
        fun get(ctx: Context): ServiceLocator =
            I ?: synchronized(this) { I ?: ServiceLocator(ctx).also { I = it } }
    }
}
