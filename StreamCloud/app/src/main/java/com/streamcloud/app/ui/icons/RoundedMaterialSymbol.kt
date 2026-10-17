package com.streamcloud.app.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp
import java.util.concurrent.ConcurrentHashMap

private val symbolCache = ConcurrentHashMap<String, ImageVector>()
private val mirroredSymbols = setOf(
    "arrow_back", "arrow_forward", "compare_arrows", "playlist_add", "playlist_play", "send",
)

internal data class RoundedSymbolData(
    val paths: List<String>,
    val minX: Float = 0f,
    val minY: Float = -960f,
    val width: Float = 960f,
    val height: Float = 960f,
)

/** Native vectors, parsed once per variant; no font, network request or composition dependency. */
internal fun roundedMaterialSymbol(name: String, filled: Boolean): ImageVector {
    val key = "$name:${if (filled) 1 else 0}"
    return symbolCache.getOrPut(key) {
        val data = roundedSymbolData(key)
        ImageVector.Builder(
            name = "MaterialSymbolsRounded.$key",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = data.width,
            viewportHeight = data.height,
            autoMirror = name in mirroredSymbols,
        ).apply {
            // Preserve both font-coordinate SVGs and older positive-coordinate glyphs.
            addGroup(translationX = -data.minX, translationY = -data.minY)
            data.paths.forEach { path ->
                addPath(pathData = addPathNodes(path), fill = SolidColor(Color.Black))
            }
            clearGroup()
        }.build()
    }
}
