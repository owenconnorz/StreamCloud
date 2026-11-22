package com.streamcloud.app.data.plugins

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.Adler32

/**
 * Rewrites the coroutine builder aliases emitted by some CloudStream extension builds to the
 * equivalent names exported by the coroutines library bundled in StreamCloud.
 *
 * This is deliberately limited to references in kotlinx.coroutines.BuildersKt with the exact
 * runBlocking signatures. Other plugin symbols are left untouched.
 */
internal object CloudStreamDexAbiPatcher {
    private const val DEX_HEADER_SIZE = 112
    private const val STRING_IDS_SIZE_OFFSET = 56
    private const val STRING_IDS_OFFSET_OFFSET = 60
    private const val TYPE_IDS_SIZE_OFFSET = 64
    private const val TYPE_IDS_OFFSET_OFFSET = 68
    private const val PROTO_IDS_SIZE_OFFSET = 72
    private const val PROTO_IDS_OFFSET_OFFSET = 76
    private const val METHOD_IDS_SIZE_OFFSET = 88
    private const val METHOD_IDS_OFFSET_OFFSET = 92
    private const val MAP_OFFSET_OFFSET = 52

    private const val STRING_DATA_ITEM_TYPE = 0x2002
    private const val BUILDERS_CLASS = "Lkotlinx/coroutines/BuildersKt;"
    private const val COROUTINE_CONTEXT = "Lkotlin/coroutines/CoroutineContext;"
    private const val FUNCTION_2 = "Lkotlin/jvm/functions/Function2;"
    private const val OBJECT = "Ljava/lang/Object;"

    internal data class PatchResult(
        val dex: ByteArray,
        val rewrittenReferences: Int,
    )

    private data class DexString(
        val value: String,
        val lengthOffset: Int,
        val utf16Length: Int,
        val dataOffset: Int,
        val endOffset: Int,
    )

    private data class Prototype(
        val returnType: String,
        val parameterTypes: List<String>,
    )

    /**
     * Replaces only runBlockingK and runBlockingK$default method-name strings when they refer to
     * BuildersKt and have the exact signatures implemented by the app's standard coroutines API.
     */
    internal fun patchDex(input: ByteArray): PatchResult {
        validateHeader(input)

        val stringsSize = intAt(input, STRING_IDS_SIZE_OFFSET)
        val stringsOffset = intAt(input, STRING_IDS_OFFSET_OFFSET)
        val typesSize = intAt(input, TYPE_IDS_SIZE_OFFSET)
        val typesOffset = intAt(input, TYPE_IDS_OFFSET_OFFSET)
        val protosSize = intAt(input, PROTO_IDS_SIZE_OFFSET)
        val protosOffset = intAt(input, PROTO_IDS_OFFSET_OFFSET)
        val methodsSize = intAt(input, METHOD_IDS_SIZE_OFFSET)
        val methodsOffset = intAt(input, METHOD_IDS_OFFSET_OFFSET)

        requireTable(input, stringsOffset, stringsSize, 4, "string_ids")
        requireTable(input, typesOffset, typesSize, 4, "type_ids")
        requireTable(input, protosOffset, protosSize, 12, "proto_ids")
        requireTable(input, methodsOffset, methodsSize, 8, "method_ids")

        val stringCache = arrayOfNulls<DexString>(stringsSize)
        fun dexString(index: Int): DexString {
            require(index in 0 until stringsSize) { "DEX string index is out of range" }
            return stringCache[index] ?: readString(input, stringsOffset, index).also {
                stringCache[index] = it
            }
        }

        fun typeDescriptor(typeIndex: Int): String {
            require(typeIndex in 0 until typesSize) { "DEX type index is out of range" }
            val descriptorIndex = intAt(input, typesOffset + typeIndex * 4)
            return dexString(descriptorIndex).value
        }

        fun prototype(protoIndex: Int): Prototype {
            require(protoIndex in 0 until protosSize) { "DEX prototype index is out of range" }
            val protoOffset = protosOffset + protoIndex * 12
            val returnType = typeDescriptor(intAt(input, protoOffset + 4))
            val parametersOffset = intAt(input, protoOffset + 8)
            val parameters = if (parametersOffset == 0) {
                emptyList()
            } else {
                require(parametersOffset >= DEX_HEADER_SIZE &&
                    parametersOffset.toLong() + 4L <= input.size
                ) {
                    "DEX type-list offset is out of range"
                }
                val parameterCount = intAt(input, parametersOffset)
                require(parameterCount >= 0 && parametersOffset.toLong() + 4L + parameterCount * 2L <= input.size) {
                    "DEX type-list is out of range"
                }
                List(parameterCount) { index ->
                    typeDescriptor(ushortAt(input, parametersOffset + 4 + index * 2))
                }
            }
            return Prototype(returnType, parameters)
        }

        val referencesByName = mutableMapOf<Int, MutableList<Pair<String, Prototype>>>()
        val aliasIndices = mutableSetOf<Int>()
        repeat(stringsSize) { index ->
            when (dexString(index).value) {
                "runBlockingK", "runBlockingK\$default" -> aliasIndices += index
            }
        }
        if (aliasIndices.isEmpty()) return PatchResult(input, 0)

        repeat(methodsSize) { methodIndex ->
            val methodOffset = methodsOffset + methodIndex * 8
            val classIndex = ushortAt(input, methodOffset)
            val protoIndex = ushortAt(input, methodOffset + 2)
            val nameIndex = intAt(input, methodOffset + 4)
            if (nameIndex in aliasIndices) {
                referencesByName.getOrPut(nameIndex) { mutableListOf() } +=
                    typeDescriptor(classIndex) to prototype(protoIndex)
            }
        }

        val replacements = mutableMapOf<Int, String>()
        for (stringIndex in aliasIndices) {
            val oldName = dexString(stringIndex).value
            val references = referencesByName[stringIndex].orEmpty()
            if (references.none { it.first == BUILDERS_CLASS }) continue
            require(references.all { it.first == BUILDERS_CLASS }) {
                "A coroutine alias string is shared by unrelated plugin methods; refusing an unsafe rewrite"
            }

            val expected = when (oldName) {
                "runBlockingK" -> Prototype(
                    returnType = OBJECT,
                    parameterTypes = listOf(COROUTINE_CONTEXT, FUNCTION_2),
                )
                "runBlockingK\$default" -> Prototype(
                    returnType = OBJECT,
                    parameterTypes = listOf(COROUTINE_CONTEXT, FUNCTION_2, "I", OBJECT),
                )
                else -> continue
            }
            require(references.all { it.second == expected }) {
                "Unexpected $oldName signature in $BUILDERS_CLASS; refusing an unsafe rewrite"
            }
            replacements[stringIndex] = oldName.replace("runBlockingK", "runBlocking")
        }

        if (replacements.isEmpty()) return PatchResult(input, 0)

        // DEX string_ids must remain sorted. The two alias names normally remain in the same
        // relative order after removing the extra K; verify their neighbors before touching bytes.
        replacements.forEach { (index, replacement) ->
            val previous = if (index == 0) null else
                replacements[index - 1] ?: dexString(index - 1).value
            val next = if (index + 1 >= stringsSize) null else
                replacements[index + 1] ?: dexString(index + 1).value
            require(previous == null || previous <= replacement) {
                "Coroutine alias rewrite would reorder the DEX string table"
            }
            require(next == null || replacement <= next) {
                "Coroutine alias rewrite would reorder the DEX string table"
            }
        }

        val output = rebuildStringData(
            input = input,
            stringIdsOffset = stringsOffset,
            strings = (0 until stringsSize).map { it to dexString(it) },
            replacements = replacements,
        )
        updateIntegrity(output)
        return PatchResult(output, replacements.size)
    }

    /** Exposed internally to keep the string-table rewrite covered by a focused unit test. */
    internal fun readStringForTest(dex: ByteArray, stringIndex: Int): String {
        validateHeader(dex)
        val stringsSize = intAt(dex, STRING_IDS_SIZE_OFFSET)
        val stringsOffset = intAt(dex, STRING_IDS_OFFSET_OFFSET)
        requireTable(dex, stringsOffset, stringsSize, 4, "string_ids")
        require(stringIndex in 0 until stringsSize)
        return readString(dex, stringsOffset, stringIndex).value
    }

    private data class DataRegion(val start: Int, val end: Int)

    private fun rebuildStringData(
        input: ByteArray,
        stringIdsOffset: Int,
        strings: List<Pair<Int, DexString>>,
        replacements: Map<Int, String>,
    ): ByteArray {
        val region = findStringDataRegion(input, strings)
        val orderedStrings = strings.sortedBy { it.second.lengthOffset }
        var originalCursor = region.start
        orderedStrings.forEach { (_, item) ->
            require(item.lengthOffset == originalCursor) {
                "DEX string_data items are not contiguous; refusing to rewrite the archive"
            }
            originalCursor = item.endOffset
        }
        require(originalCursor <= region.end) {
            "DEX string_data items extend beyond their mapped section"
        }

        val rewrittenData = ByteArrayOutputStream()
        val newStringOffsets = IntArray(strings.size)
        orderedStrings.forEach { (stringIndex, item) ->
            newStringOffsets[stringIndex] = region.start + rewrittenData.size()
            val replacement = replacements[stringIndex]
            if (replacement == null) {
                rewrittenData.write(
                    input,
                    item.lengthOffset,
                    item.endOffset - item.lengthOffset,
                )
            } else {
                require(item.value.length == item.utf16Length) {
                    "Coroutine alias is not an ASCII DEX string"
                }
                val removedCharacterIndex = "runBlocking".length
                require(
                    item.value.getOrNull(removedCharacterIndex) == 'K' &&
                        item.value.removeRange(removedCharacterIndex, removedCharacterIndex + 1) == replacement,
                ) {
                    "Coroutine alias does not have the expected K suffix"
                }
                require(replacement.length == item.utf16Length - 1) {
                    "Unexpected coroutine alias length"
                }
                for (charIndex in item.value.indices) {
                    require(
                        (input[item.dataOffset + charIndex].toInt() and 0xff) ==
                            item.value[charIndex].code,
                    ) {
                        "Coroutine alias is not encoded as plain ASCII"
                    }
                }
                require(input[item.dataOffset + item.utf16Length].toInt() == 0) {
                    "Coroutine alias string is not null-terminated"
                }
                rewrittenData.writeUleb128(replacement.length)
                rewrittenData.write(replacement.toByteArray(Charsets.US_ASCII))
                rewrittenData.write(0)
            }
        }

        val newEnd = region.start.toLong() + rewrittenData.size()
        require(newEnd <= region.end) { "Rewritten DEX strings exceed their mapped section" }
        val output = input.copyOf()
        for (offset in region.start until region.end) output[offset] = 0
        rewrittenData.toByteArray().copyInto(output, region.start)
        newStringOffsets.forEachIndexed { index, offset ->
            writeIntAt(output, stringIdsOffset + index * 4, offset)
        }
        return output
    }

    private fun findStringDataRegion(
        dex: ByteArray,
        strings: List<Pair<Int, DexString>>,
    ): DataRegion {
        val mapOffset = intAt(dex, MAP_OFFSET_OFFSET)
        require(mapOffset >= DEX_HEADER_SIZE && mapOffset.toLong() + 4L <= dex.size) {
            "DEX map list is missing; refusing to rewrite coroutine aliases"
        }
        val mapSize = intAt(dex, mapOffset)
        require(mapSize > 0 && mapOffset.toLong() + 4L + mapSize.toLong() * 12L <= dex.size) {
            "DEX map list is out of range"
        }

        data class MapItem(val type: Int, val size: Int, val offset: Int)
        val mapItems = List(mapSize) { index ->
            val offset = mapOffset + 4 + index * 12
            MapItem(
                type = ushortAt(dex, offset),
                size = intAt(dex, offset + 4),
                offset = intAt(dex, offset + 8),
            )
        }
        val stringDataItem = mapItems.singleOrNull { it.type == STRING_DATA_ITEM_TYPE }
            ?: error("DEX map list has no unique string_data section")
        require(stringDataItem.size == strings.size) {
            "DEX string_data count does not match the string identifier table"
        }
        val nextSectionOffset = mapItems
            .asSequence()
            .map { it.offset }
            .filter { it > stringDataItem.offset }
            .minOrNull() ?: dex.size
        val firstStringOffset = strings.minOfOrNull { it.second.lengthOffset }
        require(firstStringOffset == stringDataItem.offset) {
            "DEX string_data map offset does not match its first item"
        }
        require(nextSectionOffset >= stringDataItem.offset) {
            "DEX string_data section has an invalid boundary"
        }
        require(nextSectionOffset <= dex.size) {
            "DEX string_data section extends beyond the file"
        }
        return DataRegion(stringDataItem.offset, nextSectionOffset)
    }

    private fun ByteArrayOutputStream.writeUleb128(value: Int) {
        var remaining = value
        while (true) {
            val next = remaining and 0x7f
            remaining = remaining ushr 7
            write(if (remaining == 0) next else next or 0x80)
            if (remaining == 0) return
        }
    }

    private fun validateHeader(dex: ByteArray) {
        require(dex.size >= DEX_HEADER_SIZE) { "DEX header is truncated" }
        require(dex[0] == 'd'.code.toByte() &&
            dex[1] == 'e'.code.toByte() &&
            dex[2] == 'x'.code.toByte() &&
            dex[3] == '\n'.code.toByte() &&
            dex[7].toInt() == 0
        ) { "Unsupported DEX magic" }
        require(intAt(dex, 32) == dex.size) { "DEX file size does not match its data" }
        require(intAt(dex, 36) == DEX_HEADER_SIZE) { "Unexpected DEX header size" }
    }

    private fun requireTable(bytes: ByteArray, offset: Int, count: Int, itemSize: Int, name: String) {
        require(count >= 0) { "Negative $name count" }
        if (count == 0) return
        require(offset >= DEX_HEADER_SIZE &&
            offset.toLong() + count.toLong() * itemSize <= bytes.size
        ) { "$name table is out of range" }
    }

    private fun readString(bytes: ByteArray, stringIdsOffset: Int, index: Int): DexString {
        val dataOffset = intAt(bytes, stringIdsOffset + index * 4)
        require(dataOffset >= DEX_HEADER_SIZE && dataOffset < bytes.size) {
            "DEX string data offset is out of range"
        }
        val (utf16Length, contentOffset) = readUleb128(bytes, dataOffset)
        require(utf16Length >= 0) { "Negative DEX string length" }
        val value = StringBuilder(utf16Length)
        var offset = contentOffset
        var decodedUnits = 0
        while (decodedUnits < utf16Length) {
            require(offset < bytes.size) { "DEX string data is truncated" }
            val first = bytes[offset].toInt() and 0xff
            when {
                (first and 0x80) == 0 -> {
                    require(first != 0) { "DEX string ended before its declared length" }
                    value.append(first.toChar())
                    offset++
                }
                (first and 0xe0) == 0xc0 -> {
                    require(offset + 1 < bytes.size) { "DEX string data is truncated" }
                    val second = bytes[offset + 1].toInt() and 0xff
                    require((second and 0xc0) == 0x80) { "Invalid modified UTF-8 sequence" }
                    value.append((((first and 0x1f) shl 6) or (second and 0x3f)).toChar())
                    offset += 2
                }
                (first and 0xf0) == 0xe0 -> {
                    require(offset + 2 < bytes.size) { "DEX string data is truncated" }
                    val second = bytes[offset + 1].toInt() and 0xff
                    val third = bytes[offset + 2].toInt() and 0xff
                    require((second and 0xc0) == 0x80 && (third and 0xc0) == 0x80) {
                        "Invalid modified UTF-8 sequence"
                    }
                    value.append(
                        (((first and 0x0f) shl 12) or
                            ((second and 0x3f) shl 6) or
                            (third and 0x3f)).toChar(),
                    )
                    offset += 3
                }
                else -> error("Invalid modified UTF-8 lead byte")
            }
            decodedUnits++
        }
        require(offset < bytes.size && bytes[offset].toInt() == 0) {
            "DEX string is missing its terminator"
        }
        return DexString(value.toString(), dataOffset, utf16Length, contentOffset, offset + 1)
    }

    private fun readUleb128(bytes: ByteArray, startOffset: Int): Pair<Int, Int> {
        var value = 0L
        var shift = 0
        var offset = startOffset
        repeat(5) {
            require(offset < bytes.size) { "ULEB128 value is truncated" }
            val current = bytes[offset].toInt() and 0xff
            offset++
            value = value or ((current and 0x7f).toLong() shl shift)
            if ((current and 0x80) == 0) {
                require(value <= Int.MAX_VALUE) { "ULEB128 value is too large" }
                return value.toInt() to offset
            }
            shift += 7
        }
        error("Invalid ULEB128 value")
    }

    private fun intAt(bytes: ByteArray, offset: Int): Int {
        require(offset >= 0 && offset.toLong() + 4 <= bytes.size) { "DEX integer is out of range" }
        return (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)
    }

    private fun ushortAt(bytes: ByteArray, offset: Int): Int {
        require(offset >= 0 && offset.toLong() + 2 <= bytes.size) { "DEX short is out of range" }
        return (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8)
    }

    private fun writeIntAt(bytes: ByteArray, offset: Int, value: Int) {
        require(offset >= 0 && offset.toLong() + 4 <= bytes.size) { "DEX integer write is out of range" }
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
        bytes[offset + 2] = (value ushr 16).toByte()
        bytes[offset + 3] = (value ushr 24).toByte()
    }

    private fun updateIntegrity(dex: ByteArray) {
        val signature = MessageDigest.getInstance("SHA-1").digest(dex.copyOfRange(32, dex.size))
        System.arraycopy(signature, 0, dex, 12, signature.size)
        val checksum = Adler32().apply { update(dex, 12, dex.size - 12) }.value.toInt()
        dex[8] = checksum.toByte()
        dex[9] = (checksum ushr 8).toByte()
        dex[10] = (checksum ushr 16).toByte()
        dex[11] = (checksum ushr 24).toByte()
    }
}
