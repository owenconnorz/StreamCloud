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
) {
    companion object {
        fun create(name: String) = UserProfile(
            id = UUID.randomUUID().toString(),
            name = name,
            avatarSeed = name,
        )
    }
}

val BUILT_IN_AVATAR_SEEDS = listOf(
    "Aang", "Sakura", "Ash", "Hinata", "Levi",
    "Goku", "Mikasa", "Naruto", "Killua", "Erwin",
    "Zoro", "Nami", "Luffy", "Robin", "Sanji",
    "Kratos", "Arthur", "Geralt", "Aloy", "Joel",
    "Harry", "Hermione", "Neo", "Arya", "Jon",
    "Walter", "Saul", "Tommy", "Hannibal", "Dexter",
    "Eleven", "Dexter", "Felix", "Luna", "Zara",
)

fun builtInAvatarUrl(seed: String) =
    "https://api.dicebear.com/7.x/adventurer/png?seed=${seed}&size=200"
