package com.streamcloud.app.data.plugins

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CloudStreamPlugin(
    val name: String = "",
    @SerialName("jarUrl") val jarUrl: String? = null,
    val url: String? = null,
    val version: Int = 1,
    val apiVersion: Int = 1,
    val description: String? = null,
    val authors: List<String>? = null,
    val repositoryUrl: String? = null,
    val language: String? = null,
    val iconUrl: String? = null,
    val status: Int = 1,
    @SerialName("tvTypes") val tvTypes: List<String>? = null,
    val internalName: String? = null,
    val fileSize: Long? = null,
    val jarFileSize: Long? = null,
    val fileHash: String? = null,
) {

    val downloadUrl: String get() = jarUrl ?: url ?: ""
}

@Serializable
data class CloudStreamRepo(
    val id: String,
    val name: String,
    val url: String,
    val pluginCount: Int = 0,
    val lastFetched: Long = 0L,
)

@Serializable
data class InstalledPlugin(
    val name: String,
    val internalName: String,
    val version: Int,
    val filePath: String,
    val sourceRepoId: String,
    val sourceUrl: String,
    val installedAt: Long,
    val iconUrl: String? = null,
    val description: String? = null,
    val authors: List<String>? = null,
    val language: String? = null,
    val tvTypes: List<String>? = null,
) {
    fun isAdultPlugin(): Boolean {
        val types = tvTypes ?: return false
        val adultKeywords = setOf("nsfw", "adult", "xxx", "hentai", "hentaisub", "18+", "porn", "erotic")
        return types.any { it.lowercase() in adultKeywords }
    }
}
