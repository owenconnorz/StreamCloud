@file:Suppress("unused")
package com.lagradost.cloudstream3.syncproviders

data class AuthUser(
    val name: String,
    val profilePicture: String? = null,
    val accountIndex: Int = 0,
    val points: Int? = null,
)
