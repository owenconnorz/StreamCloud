package com.streamcloud.app.data.profiles

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class UserProfile(
    val id: String,
    val name: String,
    val avatarUrl: String = "",
    val avatarSeed: String = "",
    val pinHash: String = "",
    // Kept to migrate profile mappings written by older app versions.
    val nuvioProfileIndex: Int? = null,
    val nuvioProfileIndexes: Map<String, Int> = emptyMap(),
) {
    companion object {
        fun create(name: String) = UserProfile(
            id = UUID.randomUUID().toString(),
            name = name,
            avatarSeed = name,
        )
    }
}

const val BUILT_IN_AVATAR_COUNT = 42

val BUILT_IN_AVATAR_SEEDS = listOf(
    "avatar_lalo",
    "avatar_lara",
    "avatar_levi",
    "avatar_mikasa",
    "avatar_naruto",
    "avatar_negan",
    "avatar_neo",
    "avatar_rick_grimes",
    "avatar_saitama",
    "avatar_saul_goodman",
    "avatar_linear_woman_teal",
    "avatar_linear_man_purple",
    "avatar_linear_woman_red",
    "avatar_linear_man_navy",
    "avatar_linear_woman_yellow",
    "avatar_linear_man_green",
    "avatar_linear_woman_pink",
    "avatar_aang",
    "avatar_arthur_morgan",
    "avatar_ash",
    "avatar_chihiro",
    "avatar_daenerys",
    "avatar_dexter",
    "avatar_eleven",
    "avatar_eren",
    "avatar_furiosa",
    "avatar_geralt",
    "avatar_gojo",
    "avatar_goku",
    "avatar_harry_potter",
    "avatar_jack_sparrow",
    "avatar_jinwoo",
    "avatar_joel",
    "avatar_jon_snow",
    "avatar_katara",
    "avatar_killua",
    "avatar_kratos",
    "avatar_tommy_shelby",
    "avatar_v",
    "avatar_walter_white",
    "avatar_wednesday",
    "avatar_moss",
)

private val LEGACY_AVATAR_SEED_MAP = mapOf(
    "aang" to "avatar_aang",
    "ash" to "avatar_ash",
    "arthur" to "avatar_arthur_morgan",
    "dexter" to "avatar_dexter",
    "eleven" to "avatar_eleven",
    "geralt" to "avatar_geralt",
    "goku" to "avatar_goku",
    "harry" to "avatar_harry_potter",
    "jon" to "avatar_jon_snow",
    "joel" to "avatar_joel",
    "killua" to "avatar_killua",
    "kratos" to "avatar_kratos",
    "levi" to "avatar_levi",
    "mikasa" to "avatar_mikasa",
    "naruto" to "avatar_naruto",
    "neo" to "avatar_neo",
    "saul" to "avatar_saul_goodman",
    "tommy" to "avatar_tommy_shelby",
    "walter" to "avatar_walter_white",
)

/**
 * Maps old Dicebear seeds and profile-name defaults to stable entries in the
 * local avatar catalog, so existing profiles adopt the new artwork without
 * changing profile IDs, PINs, or sync mappings.
 */
fun resolveBuiltInAvatarSeed(seed: String): String {
    val normalized = seed.trim()
    BUILT_IN_AVATAR_SEEDS.firstOrNull { it.equals(normalized, ignoreCase = true) }?.let {
        return it
    }

    LEGACY_AVATAR_SEED_MAP[normalized.lowercase()]?.let { return it }
    val index = Math.floorMod(normalized.hashCode(), BUILT_IN_AVATAR_COUNT)
    return BUILT_IN_AVATAR_SEEDS[index]
}
