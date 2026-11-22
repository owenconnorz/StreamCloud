package com.streamcloud.app.data.plugins

import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Makes a private code-cache copy of a CloudStream plugin, rewriting only DEX coroutine aliases
 * that the app's bundled kotlinx.coroutines version cannot resolve.
 */
internal object CloudStreamPluginArchivePreparer {
    internal data class Result(
        val rewrittenDexFiles: Int,
        val rewrittenMethodReferences: Int,
    )

    private val dexEntryPattern = Regex("classes\\d*\\.dex")

    fun prepare(source: File, destination: File): Result {
        destination.parentFile?.mkdirs()
        if (isRawDex(source)) {
            val patch = patchSafely(source.readBytes())
            destination.writeBytes(patch.dex)
            return Result(
                rewrittenDexFiles = if (patch.rewrittenReferences > 0) 1 else 0,
                rewrittenMethodReferences = patch.rewrittenReferences,
            )
        }

        val zip = try {
            ZipFile(source)
        } catch (_: Exception) {
            // Some older extensions are delivered as non-ZIP DEX containers. Leave those files
            // unchanged; DexClassLoader will report any actual format problem during loading.
            source.copyTo(destination, overwrite = true)
            return Result(0, 0)
        }
        val changedDexEntries = linkedMapOf<String, ByteArray>()
        var rewrittenReferences = 0
        zip.use {
            val entries = it.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (!dexEntryPattern.matches(entry.name)) continue
                val bytes = it.getInputStream(entry).use { input -> input.readBytes() }
                val patch = patchSafely(bytes)
                if (patch.rewrittenReferences > 0) {
                    changedDexEntries[entry.name] = patch.dex
                    rewrittenReferences += patch.rewrittenReferences
                }
            }
        }

        if (changedDexEntries.isEmpty()) {
            source.copyTo(destination, overwrite = true)
            return Result(0, 0)
        }

        val writtenDexEntries = mutableSetOf<String>()
        ZipInputStream(source.inputStream().buffered()).use { input ->
            ZipOutputStream(FileOutputStream(destination).buffered()).use { output ->
                var entry = input.nextEntry
                while (entry != null) {
                    val outputEntry = ZipEntry(entry.name).apply {
                        if (entry.time >= 0L) time = entry.time
                        entry.comment?.let { comment = it }
                    }
                    output.putNextEntry(outputEntry)
                    val replacement = changedDexEntries[entry.name]
                    if (replacement == null) {
                        input.copyTo(output)
                    } else {
                        output.write(replacement)
                        writtenDexEntries += entry.name
                    }
                    output.closeEntry()
                    input.closeEntry()
                    entry = input.nextEntry
                }
            }
        }
        check(writtenDexEntries == changedDexEntries.keys) {
            "The rewritten DEX entries were not all copied into the plugin archive"
        }

        return Result(
            rewrittenDexFiles = changedDexEntries.size,
            rewrittenMethodReferences = rewrittenReferences,
        )
    }

    private fun patchSafely(bytes: ByteArray): CloudStreamDexAbiPatcher.PatchResult {
        return try {
            CloudStreamDexAbiPatcher.patchDex(bytes)
        } catch (error: Exception) {
            if (containsAliasName(bytes, "runBlockingK")) {
                throw IllegalStateException(
                    "Found a runBlockingK coroutine reference, but could not safely rewrite its DEX: " +
                        (error.message ?: error::class.simpleName),
                    error,
                )
            }
            CloudStreamDexAbiPatcher.PatchResult(bytes, 0)
        }
    }

    private fun isRawDex(file: File): Boolean {
        return file.inputStream().buffered().use { input ->
            val prefix = ByteArray(4)
            input.read(prefix) == prefix.size &&
                prefix.contentEquals(byteArrayOf('d'.code.toByte(), 'e'.code.toByte(), 'x'.code.toByte(), '\n'.code.toByte()))
        }
    }

    private fun containsAliasName(bytes: ByteArray, alias: String): Boolean {
        val needle = alias.toByteArray(Charsets.US_ASCII)
        if (needle.size > bytes.size) return false
        for (start in 0..bytes.size - needle.size) {
            var matches = true
            for (index in needle.indices) {
                if (bytes[start + index] != needle[index]) {
                    matches = false
                    break
                }
            }
            if (matches) return true
        }
        return false
    }
}
