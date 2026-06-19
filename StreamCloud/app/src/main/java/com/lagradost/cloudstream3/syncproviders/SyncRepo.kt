@file:Suppress("unused")
package com.lagradost.cloudstream3.syncproviders

import com.lagradost.cloudstream3.syncproviders.providers.AniListApi

data class SyncRepo(
    val aniListApi: AniListApi? = null,
) {
    // Plugins compiled against the real CloudStream API construct SyncRepo
    // by passing a SyncAPI instance: new SyncRepo(api).  This secondary
    // constructor satisfies that call-site without changing the primary
    // constructor that our own code uses.
    constructor(api: SyncAPI) : this()

    companion object {
        val noop = SyncRepo()
    }
}
