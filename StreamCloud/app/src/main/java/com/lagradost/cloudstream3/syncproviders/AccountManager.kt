@file:Suppress("unused", "MemberVisibilityCanBePrivate")
package com.lagradost.cloudstream3.syncproviders

import android.content.Context

abstract class AccountManager(val accountId: String) : SyncAPI {

    companion object {
        val accountManagers: MutableList<AccountManager> = mutableListOf()

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
