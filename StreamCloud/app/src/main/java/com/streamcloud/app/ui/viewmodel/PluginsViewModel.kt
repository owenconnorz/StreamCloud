package com.streamcloud.app.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.streamcloud.app.data.nuvio.InstalledNuvioProvider
import com.streamcloud.app.data.nuvio.NuvioProviderEntry
import com.streamcloud.app.data.nuvio.NuvioRepoManifest
import com.streamcloud.app.data.nuvio.NuvioSavedRepo
import com.streamcloud.app.data.nuvio.NuvioRepository
import com.streamcloud.app.data.plugins.CloudStreamPlugin
import com.streamcloud.app.data.plugins.CloudStreamRepo
import com.streamcloud.app.data.plugins.InstalledPlugin
import com.streamcloud.app.data.plugins.PluginRepository
import com.streamcloud.app.data.plugins.PluginRuntime
import com.streamcloud.app.data.stremio.InstalledStremioAddon
import com.streamcloud.app.data.stremio.StremioRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

enum class TestStatus { Idle, Running, Success, Error }

data class ProviderTestResult(
    val id: String,
    val name: String,
    val ecosystem: String,
    val logo: String? = null,
    val status: TestStatus = TestStatus.Idle,
    val streamCount: Int = 0,
    val error: String? = null,
    val durationMs: Long = 0L,
)

data class PluginsState(
    val repos: List<CloudStreamRepo> = emptyList(),
    val installed: List<InstalledPlugin> = emptyList(),
    val pluginsByRepo: Map<String, List<CloudStreamPlugin>> = emptyMap(),
    val loadingRepoIds: Set<String> = emptySet(),
    val installingNames: Set<String> = emptySet(),
    val stremioAddons: List<InstalledStremioAddon> = emptyList(),
    val addingStremio: Boolean = false,
    val nuvioProviders: List<InstalledNuvioProvider> = emptyList(),
    val nuvioSavedRepos: List<NuvioSavedRepo> = emptyList(),
    val nuvioRepoUrl: String = "",
    val nuvioRepoManifest: NuvioRepoManifest? = null,
    val loadingNuvioRepo: Boolean = false,
    val installingNuvioIds: Set<String> = emptySet(),
    val error: String? = null,
    val info: String? = null,
    val testResults: List<ProviderTestResult> = emptyList(),
    val testRunning: Boolean = false,
)

class PluginsViewModel(
    private val repo: PluginRepository,
    private val stremio: StremioRepository,
    private val nuvio: NuvioRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(PluginsState())
    val state: StateFlow<PluginsState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(repo.repos, repo.installed, stremio.addons, nuvio.installed, nuvio.savedRepos) { r, i, s, n, sr ->
                arrayOf(r, i, s, n, sr)
            }.collect { arr ->
                @Suppress("UNCHECKED_CAST")
                _state.update { it.copy(
                    repos = arr[0] as List<CloudStreamRepo>,
                    installed = arr[1] as List<InstalledPlugin>,
                    stremioAddons = arr[2] as List<InstalledStremioAddon>,
                    nuvioProviders = arr[3] as List<InstalledNuvioProvider>,
                    nuvioSavedRepos = arr[4] as List<NuvioSavedRepo>,
                ) }
            }
        }
    }

    fun addRepo(name: String, url: String) = viewModelScope.launch {
        if (name.isBlank() || url.isBlank()) {
            _state.update { it.copy(error = "Name and URL are required") }
            return@launch
        }
        try {
            repo.addRepo(name.trim(), url.trim())
            _state.update { it.copy(info = "Repo '$name' added", error = null) }
        } catch (e: Exception) {
            _state.update { it.copy(error = "Failed: ${e.message}") }
        }
    }

    fun removeRepo(id: String) = viewModelScope.launch {
        repo.removeRepo(id)
        _state.update { s -> s.copy(pluginsByRepo = s.pluginsByRepo - id) }
    }

    fun fetchRepo(r: CloudStreamRepo) = viewModelScope.launch {
        _state.update { it.copy(loadingRepoIds = it.loadingRepoIds + r.id, error = null) }
        try {
            val plugins = repo.fetchPluginList(r.url)
            _state.update {
                it.copy(
                    pluginsByRepo = it.pluginsByRepo + (r.id to plugins),
                    loadingRepoIds = it.loadingRepoIds - r.id,
                    info = "Loaded ${plugins.size} plugins from ${r.name}",
                )
            }
        } catch (e: Exception) {
            _state.update {
                it.copy(
                    loadingRepoIds = it.loadingRepoIds - r.id,
                    error = "Fetch failed: ${e.message}",
                )
            }
        }
    }

    fun install(repoModel: CloudStreamRepo, plugin: CloudStreamPlugin) = viewModelScope.launch {
        _state.update { it.copy(installingNames = it.installingNames + plugin.name) }
        try {
            repo.installPlugin(repoModel, plugin)
            _state.update {
                it.copy(
                    installingNames = it.installingNames - plugin.name,
                    info = "Installed: ${plugin.name}",
                )
            }
        } catch (e: Exception) {
            _state.update {
                it.copy(
                    installingNames = it.installingNames - plugin.name,
                    error = "Install failed: ${e.message}",
                )
            }
        }
    }

    fun uninstall(p: InstalledPlugin) = viewModelScope.launch {
        repo.uninstallPlugin(p.internalName, p.sourceRepoId)
    }


    fun addStremioAddon(url: String) = viewModelScope.launch {
        if (url.isBlank()) {
            _state.update { it.copy(error = "Manifest URL is required") }
            return@launch
        }
        _state.update { it.copy(addingStremio = true, error = null) }
        try {
            val a = stremio.addAddon(url.trim())
            _state.update { it.copy(addingStremio = false, info = "Stremio addon added: ${a.name}") }
        } catch (e: Exception) {
            _state.update { it.copy(addingStremio = false, error = "Stremio: ${e.message}") }
        }
    }

    fun removeStremioAddon(manifestUrl: String) = viewModelScope.launch {
        stremio.removeAddon(manifestUrl)
    }


    fun loadNuvioRepo(url: String) = viewModelScope.launch {
        if (url.isBlank()) {
            _state.update { it.copy(error = "Nuvio repo URL is required") }
            return@launch
        }
        _state.update { it.copy(loadingNuvioRepo = true, nuvioRepoUrl = url, nuvioRepoManifest = null, error = null) }
        try {
            val mf = nuvio.fetchManifest(url)
            nuvio.addSavedRepo(url, mf.name)
            _state.update { it.copy(loadingNuvioRepo = false, nuvioRepoManifest = mf) }
        } catch (e: Exception) {
            _state.update { it.copy(loadingNuvioRepo = false, error = "Nuvio: ${e.message}") }
        }
    }

    fun removeNuvioSavedRepo(id: String) = viewModelScope.launch {
        nuvio.removeSavedRepo(id)
    }

    fun installNuvioProvider(entry: NuvioProviderEntry) = viewModelScope.launch {
        val repoUrl = _state.value.nuvioRepoUrl
        _state.update { it.copy(installingNuvioIds = it.installingNuvioIds + entry.id, error = null) }
        try {
            val rec = nuvio.installProvider(repoUrl, entry)
            _state.update { it.copy(
                installingNuvioIds = it.installingNuvioIds - entry.id,
                info = "Installed Nuvio provider: ${rec.name}",
            ) }
        } catch (e: Exception) {
            _state.update { it.copy(
                installingNuvioIds = it.installingNuvioIds - entry.id,
                error = "Nuvio install failed: ${e.message}",
            ) }
        }
    }

    fun uninstallNuvioProvider(id: String) = viewModelScope.launch {
        nuvio.uninstall(id)
    }

    fun clearMessages() {
        _state.update { it.copy(error = null, info = null) }
    }

    private fun updateTestResult(id: String, block: ProviderTestResult.() -> ProviderTestResult) {
        _state.update { s ->
            s.copy(testResults = s.testResults.map { if (it.id == id) it.block() else it })
        }
    }

    fun runAllProviderTests(context: Context) = viewModelScope.launch {
        if (_state.value.testRunning) return@launch
        val s = _state.value

        val initialResults = buildList {
            s.installed.forEach { add(ProviderTestResult(it.internalName, it.name, "CloudStream", it.iconUrl)) }
            s.stremioAddons.forEach { add(ProviderTestResult(it.manifestUrl, it.name, "Stremio", it.logo)) }
            s.nuvioProviders.forEach { add(ProviderTestResult(it.id, it.name, "Nuvio", it.logo)) }
        }
        _state.update { it.copy(testResults = initialResults, testRunning = true) }

        try {
            coroutineScope {
                val jobs = buildList {
                    s.installed.forEach { plugin ->
                        add(async(Dispatchers.IO) {
                            updateTestResult(plugin.internalName) { copy(status = TestStatus.Running) }
                            val t0 = System.currentTimeMillis()
                            try {
                                val count = withTimeoutOrNull(30_000L) {
                                    val results = PluginRuntime.search(context, plugin.filePath, TEST_TITLE)
                                    val best = results.firstOrNull() ?: return@withTimeoutOrNull 0
                                    val detail = PluginRuntime.loadDetail(context, plugin.filePath, best.url)
                                    val dataUrl = when (detail) {
                                        is com.lagradost.cloudstream3.MovieLoadResponse -> detail.dataUrl
                                        is com.lagradost.cloudstream3.LiveStreamLoadResponse -> detail.dataUrl
                                        is com.lagradost.cloudstream3.TvSeriesLoadResponse ->
                                            detail.episodes.singleOrNull()?.data
                                        is com.lagradost.cloudstream3.AnimeLoadResponse ->
                                            detail.episodes.values.flatten().singleOrNull()?.data
                                        else -> null
                                    } ?: return@withTimeoutOrNull 0
                                    val (links, _) = PluginRuntime.loadLinks(context, plugin.filePath, dataUrl, false)
                                    links.size
                                } ?: 0
                                updateTestResult(plugin.internalName) {
                                    copy(status = TestStatus.Success, streamCount = count, durationMs = System.currentTimeMillis() - t0)
                                }
                            } catch (e: Exception) {
                                updateTestResult(plugin.internalName) {
                                    copy(status = TestStatus.Error, error = e.message ?: "Error", durationMs = System.currentTimeMillis() - t0)
                                }
                            }
                        })
                    }

                    s.stremioAddons.forEach { addon ->
                        add(async(Dispatchers.IO) {
                            updateTestResult(addon.manifestUrl) { copy(status = TestStatus.Running) }
                            val t0 = System.currentTimeMillis()
                            try {
                                val count = withTimeoutOrNull(15_000L) {
                                    stremio.fetchStreams(addon, "movie", TEST_IMDB).size
                                } ?: 0
                                updateTestResult(addon.manifestUrl) {
                                    copy(status = TestStatus.Success, streamCount = count, durationMs = System.currentTimeMillis() - t0)
                                }
                            } catch (e: Exception) {
                                updateTestResult(addon.manifestUrl) {
                                    copy(status = TestStatus.Error, error = e.message ?: "Error", durationMs = System.currentTimeMillis() - t0)
                                }
                            }
                        })
                    }

                    s.nuvioProviders.forEach { provider ->
                        add(async(Dispatchers.IO) {
                            updateTestResult(provider.id) { copy(status = TestStatus.Running) }
                            val t0 = System.currentTimeMillis()
                            try {
                                val (count, err) = nuvio.testSingleProvider(provider)
                                val elapsed = System.currentTimeMillis() - t0
                                if (err != null) {
                                    updateTestResult(provider.id) { copy(status = TestStatus.Error, error = err, durationMs = elapsed) }
                                } else {
                                    updateTestResult(provider.id) { copy(status = TestStatus.Success, streamCount = count, durationMs = elapsed) }
                                }
                            } catch (e: Exception) {
                                updateTestResult(provider.id) {
                                    copy(status = TestStatus.Error, error = e.message ?: "Error", durationMs = System.currentTimeMillis() - t0)
                                }
                            }
                        })
                    }
                }
                jobs.awaitAll()
            }
        } finally {
            _state.update { it.copy(testRunning = false) }
        }
    }

    companion object {
        private const val TEST_TITLE = "The Dark Knight"
        private const val TEST_IMDB  = "tt0468569"

        fun factory(context: Context) = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return PluginsViewModel(
                    PluginRepository(context.applicationContext),
                    StremioRepository(context.applicationContext),
                    NuvioRepository(context.applicationContext),
                ) as T
            }
        }
    }
}
