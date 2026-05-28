@file:Suppress("unused", "MemberVisibilityCanBePrivate")
package com.lagradost.cloudstream3.syncproviders

import android.content.Context
import com.lagradost.cloudstream3.syncproviders.providers.AniListApi

abstract class AccountManager(val accountId: String) : SyncAPI {

    companion object {
        val accountManagers: MutableList<AccountManager> = mutableListOf()

        // Real CloudStream3 exposes these as properties on the companion so plugins
        // can call AccountManager.aniListApi (Kotlin → getAniListApi() JVM getter).
        val aniListApi:  AniListApi  = AniListApi()
        val malApi:      AniListApi  = AniListApi("mal")      // stub — share same class
        val simklApi:    AniListApi  = AniListApi("simkl")    // stub — share same class

        fun getAccountManagers(context: Context): List<AccountManager> = accountManagers

        fun register(manager: AccountManager) {
            if (accountManagers.none { it.accountId == manager.accountId }) {
                accountManagers.add(manager)
            }
        }
    }

    open fun loginInfo(): AuthUser? = null
    open fun logOut() {}
    open fun hasLoggedIn(): Boolean = loginInfo() != null
    open fun getLatestLoginData(): Any? = null
}
