package com.streamcloud.app.data

/**
 * Dolby formats that Media3 can identify as separate audio MIME types.
 *
 * A preference does not make an unsupported stream or device decode a format. It only
 * moves the selected formats ahead of other audio tracks when a movie offers them.
 */
data class MovieAudioFormat(
    val id: String,
    val label: String,
    val description: String,
    val mimeType: String,
)

object MovieAudioPreferences {
    val formats = listOf(
        MovieAudioFormat(
            id = "dolby_atmos",
            label = "Dolby Atmos",
            description = "E-AC-3 JOC immersive audio",
            mimeType = "audio/eac3-joc",
        ),
        MovieAudioFormat(
            id = "dolby_truehd",
            label = "Dolby TrueHD",
            description = "Lossless Blu-ray audio",
            mimeType = "audio/true-hd",
        ),
        MovieAudioFormat(
            id = "dolby_digital_plus",
            label = "Dolby Digital Plus",
            description = "Enhanced AC-3 surround audio",
            mimeType = "audio/eac3",
        ),
        MovieAudioFormat(
            id = "dolby_digital",
            label = "Dolby Digital",
            description = "AC-3 surround audio",
            mimeType = "audio/ac3",
        ),
        MovieAudioFormat(
            id = "dolby_ac4",
            label = "Dolby AC-4",
            description = "Next-generation broadcast audio",
            mimeType = "audio/ac4",
        ),
    )

    val defaultIds: Set<String> = formats.mapTo(linkedSetOf(), MovieAudioFormat::id)
    val defaultIdsCsv: String = defaultIds.joinToString(",")

    fun decodeIds(csv: String): Set<String> =
        csv.split(',')
            .map(String::trim)
            .filter { id -> formats.any { it.id == id } }
            .toSet()

    fun mimeTypesFor(csv: String): List<String> {
        val selected = decodeIds(csv)
        return formats
            .filter { it.id in selected }
            .map(MovieAudioFormat::mimeType)
    }

    fun summary(csv: String): String {
        val selected = decodeIds(csv)
        return when {
            selected.size == formats.size -> "All Dolby formats"
            selected.isEmpty() -> "Auto"
            selected.size == 1 -> formats.first { it.id in selected }.label
            else -> "${selected.size} Dolby formats"
        }
    }
}