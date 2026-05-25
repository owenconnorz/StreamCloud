@file:Suppress("unused")
package com.lagradost.cloudstream3.utils

import android.content.Context

sealed class UiText {
    data class DynamicString(val value: String) : UiText()
    class StringResource(val resId: Int, vararg val args: Any) : UiText()

    fun asString(context: Context): String = when (this) {
        is DynamicString -> value
        is StringResource -> runCatching {
            context.getString(resId, *args)
        }.getOrDefault(resId.toString())
    }
}
