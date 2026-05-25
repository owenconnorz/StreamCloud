@file:Suppress("unused")
package com.lagradost.cloudstream3.syncproviders

import com.lagradost.cloudstream3.syncproviders.providers.AniListApi

data class SyncRepo(
    val aniListApi: AniListApi? = null,
) {
    companion object {
        val noop = SyncRepo()
    }
}
