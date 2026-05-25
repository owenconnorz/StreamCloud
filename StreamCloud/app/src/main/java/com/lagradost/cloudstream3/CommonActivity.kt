@file:Suppress("unused", "MemberVisibilityCanBePrivate")
package com.lagradost.cloudstream3

import android.app.Activity
import java.lang.ref.WeakReference

abstract class CommonActivity : Activity() {
    companion object {
        var activity: WeakReference<CommonActivity>? = null

        fun getActivity(): CommonActivity? = activity?.get()

        fun showToast(message: String, duration: Int = android.widget.Toast.LENGTH_SHORT) {
            getActivity()?.runOnUiThread {
                android.widget.Toast.makeText(getActivity(), message, duration).show()
            }
        }

        fun showToast(resId: Int, duration: Int = android.widget.Toast.LENGTH_SHORT) {
            getActivity()?.runOnUiThread {
                android.widget.Toast.makeText(getActivity(), resId, duration).show()
            }
        }
    }
}
