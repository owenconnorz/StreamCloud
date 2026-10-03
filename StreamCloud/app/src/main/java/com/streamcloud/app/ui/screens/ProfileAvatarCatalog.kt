package com.streamcloud.app.ui.screens

import com.streamcloud.app.R
import com.streamcloud.app.data.profiles.BUILT_IN_AVATAR_SEEDS
import com.streamcloud.app.data.profiles.resolveBuiltInAvatarSeed

private val NUVIO_AVATAR_DRAWABLES = intArrayOf(
    R.drawable.nuvio_avatar_01,
    R.drawable.nuvio_avatar_02,
    R.drawable.nuvio_avatar_03,
    R.drawable.nuvio_avatar_04,
    R.drawable.nuvio_avatar_05,
    R.drawable.nuvio_avatar_06,
    R.drawable.nuvio_avatar_07,
    R.drawable.nuvio_avatar_08,
    R.drawable.nuvio_avatar_09,
    R.drawable.nuvio_avatar_10,
    R.drawable.nuvio_avatar_11,
    R.drawable.nuvio_avatar_12,
    R.drawable.nuvio_avatar_13,
    R.drawable.nuvio_avatar_14,
    R.drawable.nuvio_avatar_15,
    R.drawable.nuvio_avatar_16,
    R.drawable.nuvio_avatar_17,
    R.drawable.nuvio_avatar_18,
    R.drawable.nuvio_avatar_19,
    R.drawable.nuvio_avatar_20,
    R.drawable.nuvio_avatar_21,
    R.drawable.nuvio_avatar_22,
    R.drawable.nuvio_avatar_23,
    R.drawable.nuvio_avatar_24,
    R.drawable.nuvio_avatar_25,
    R.drawable.nuvio_avatar_26,
    R.drawable.nuvio_avatar_27,
    R.drawable.nuvio_avatar_28,
    R.drawable.nuvio_avatar_29,
    R.drawable.nuvio_avatar_30,
    R.drawable.nuvio_avatar_31,
    R.drawable.nuvio_avatar_32,
    R.drawable.nuvio_avatar_33,
    R.drawable.nuvio_avatar_34,
    R.drawable.nuvio_avatar_35,
    R.drawable.nuvio_avatar_36,
    R.drawable.nuvio_avatar_37,
    R.drawable.nuvio_avatar_38,
    R.drawable.nuvio_avatar_39,
    R.drawable.nuvio_avatar_40,
    R.drawable.nuvio_avatar_41,
    R.drawable.nuvio_avatar_42,
)

fun profileAvatarDrawable(seed: String): Int {
    val resolvedSeed = resolveBuiltInAvatarSeed(seed)
    val index = BUILT_IN_AVATAR_SEEDS.indexOf(resolvedSeed).coerceAtLeast(0)
    return NUVIO_AVATAR_DRAWABLES[index]
}