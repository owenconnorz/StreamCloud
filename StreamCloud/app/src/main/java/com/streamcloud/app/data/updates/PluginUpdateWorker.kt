package com.streamcloud.app.data.updates

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.streamcloud.app.data.ServiceLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

class PluginUpdateWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    private val sl  = ServiceLocator.get(appContext)
    private val TAG = "PluginUpdateWorker"

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.d(TAG, "Starting plugin update check")
        val updates = mutableListOf<PluginUpdateNotifier.UpdatedItem>()

        coroutineScope {
            val stremioJob      = async { checkStremio(updates) }
            val nuvioJob        = async { checkNuvio(updates) }
            val cloudStreamJob  = async { checkCloudStream(updates) }
            awaitAll(stremioJob, nuvioJob, cloudStreamJob)
        }

        if (updates.isNotEmpty()) {
            Log.d(TAG, "Updates found (${updates.size}) — posting notifications")
            PluginUpdateNotifier.notify(applicationContext, updates)
        } else {
            Log.d(TAG, "All plugins/addons up to date")
        }

        Result.success()
    }

    private suspend fun checkStremio(updates: MutableList<PluginUpdateNotifier.UpdatedItem>) {
        runCatching {
            val addons = sl.stremio.addons.first()
            Log.d(TAG, "Stremio: checking ${addons.size} addons")
            for (addon in addons) {
                runCatching {
                    val mf             = sl.stremio.fetchManifest(addon.manifestUrl)
                    val remoteVersion  = mf.version ?: return@runCatching
                    val localVersion   = addon.version

                    if (localVersion == null) {
                        sl.stremio.updateAddon(addon.copy(version = remoteVersion))
                    } else if (remoteVersion != localVersion) {
                        Log.d(TAG, "Stremio ${addon.name}: $localVersion → $remoteVersion")
                        sl.stremio.updateAddon(addon.copy(version = remoteVersion))
                        updates += PluginUpdateNotifier.UpdatedItem("Stremio", addon.name)
                    }
                }.onFailure { Log.w(TAG, "Stremio check failed for ${addon.name}: ${it.message}") }
            }
        }.onFailure { Log.w(TAG, "Stremio check error: ${it.message}") }
    }

    private suspend fun checkNuvio(updates: MutableList<PluginUpdateNotifier.UpdatedItem>) {
        runCatching {
            val providers = sl.nuvio.installed.first()
            Log.d(TAG, "Nuvio: checking ${providers.size} providers")
            for (provider in providers) {
                runCatching {
                    val manifest      = sl.nuvio.fetchManifest(provider.repoUrl)
                    val entry         = manifest.allProviders.firstOrNull { it.id == provider.id }
                        ?: return@runCatching
                    val remoteVersion = entry.version ?: return@runCatching
                    val localVersion  = provider.version

                    if (localVersion == null) {
                        sl.nuvio.updateProvider(provider.copy(version = remoteVersion))
                    } else if (remoteVersion != localVersion) {
                        Log.d(TAG, "Nuvio ${provider.name}: $localVersion → $remoteVersion")
                        sl.nuvio.installProvider(provider.repoUrl, entry)
                        updates += PluginUpdateNotifier.UpdatedItem("Nuvio", provider.name)
                    }
                }.onFailure { Log.w(TAG, "Nuvio check failed for ${provider.name}: ${it.message}") }
            }
        }.onFailure { Log.w(TAG, "Nuvio check error: ${it.message}") }
    }

    private suspend fun checkCloudStream(updates: MutableList<PluginUpdateNotifier.UpdatedItem>) {
        runCatching {
            val installed = sl.plugins.installed.first()
            val repos     = sl.plugins.repos.first()
            Log.d(TAG, "CloudStream: checking ${installed.size} plugins across ${repos.size} repos")
            for (plugin in installed) {
                runCatching {
                    val repo   = repos.firstOrNull { it.id == plugin.sourceRepoId } ?: return@runCatching
                    val remote = sl.plugins.fetchPluginList(repo.url).firstOrNull { remote ->
                        remote.internalName == plugin.internalName || remote.name == plugin.name
                    } ?: return@runCatching

                    if (remote.version > plugin.version) {
                        Log.d(TAG, "CloudStream ${plugin.name}: v${plugin.version} → v${remote.version}")
                        sl.plugins.installPlugin(repo, remote)
                        updates += PluginUpdateNotifier.UpdatedItem("CloudStream", plugin.name)
                    }
                }.onFailure { Log.w(TAG, "CloudStream check failed for ${plugin.name}: ${it.message}") }
            }
        }.onFailure { Log.w(TAG, "CloudStream check error: ${it.message}") }
    }
}
