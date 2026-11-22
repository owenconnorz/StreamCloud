package com.streamcloud.app.data.plugins

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.Adler32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.coroutines.CoroutineContext
import kotlin.jvm.functions.Function2
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudStreamDexAbiPatcherTest {
    @Test
    fun appCoroutinesExportsTheTargetRunBlockingSignatures() {
        val buildersClass = Class.forName("kotlinx.coroutines.BuildersKt")

        buildersClass.getDeclaredMethod(
            "runBlocking",
            CoroutineContext::class.java,
            Function2::class.java,
        )
        buildersClass.getDeclaredMethod(
            "runBlocking\$default",
            CoroutineContext::class.java,
            Function2::class.java,
            Int::class.javaPrimitiveType,
            Any::class.java,
        )
    }

    @Test
    fun rewritesOnlyTheExactBuildersKtCoroutineAliasesAndRefreshesDexIntegrity() {
        val fixture = syntheticDex()

        val result = CloudStreamDexAbiPatcher.patchDex(fixture.bytes)

        assertEquals(2, result.rewrittenReferences)
        assertEquals("runBlocking", CloudStreamDexAbiPatcher.readStringForTest(result.dex, fixture.stringIds.getValue("runBlockingK")))
        assertEquals(
            "runBlocking\$default",
            CloudStreamDexAbiPatcher.readStringForTest(result.dex, fixture.stringIds.getValue("runBlockingK\$default")),
        )
        assertDexIntegrityIsValid(result.dex)
    }

    @Test
    fun leavesSameNamedMethodsOnOtherOwnersUntouched() {
        val fixture = syntheticDex(owner = "Lexample/BuildersKt;")

        val result = CloudStreamDexAbiPatcher.patchDex(fixture.bytes)

        assertEquals(0, result.rewrittenReferences)
        assertArrayEquals(fixture.bytes, result.dex)
    }

    @Test
    fun rewritesTheDexEntryWithoutDroppingOtherPluginFiles() {
        val fixture = syntheticDex()
        val source = File.createTempFile("cloudstream-plugin-source-", ".cs3")
        val output = File.createTempFile("cloudstream-plugin-output-", ".cs3")
        try {
            ZipOutputStream(source.outputStream().buffered()).use { zip ->
                zip.putNextEntry(ZipEntry("classes.dex"))
                zip.write(fixture.bytes)
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write("""{"pluginClassName":"com.example.TestPlugin"}""".toByteArray())
                zip.closeEntry()
            }

            val result = CloudStreamPluginArchivePreparer.prepare(source, output)

            assertEquals(1, result.rewrittenDexFiles)
            assertEquals(2, result.rewrittenMethodReferences)
            ZipFile(output).use { zip ->
                val rewrittenDex = zip.getInputStream(zip.getEntry("classes.dex")).use { it.readBytes() }
                assertEquals(
                    "runBlocking",
                    CloudStreamDexAbiPatcher.readStringForTest(
                        rewrittenDex,
                        fixture.stringIds.getValue("runBlockingK"),
                    ),
                )
                val manifest = zip.getInputStream(zip.getEntry("manifest.json"))
                    .bufferedReader().use { it.readText() }
                assertTrue(manifest.contains("com.example.TestPlugin"))
            }
        } finally {
            source.delete()
            output.delete()
        }
    }

    private data class DexFixture(
        val bytes: ByteArray,
        val stringIds: Map<String, Int>,
    )

    private fun syntheticDex(owner: String = "Lkotlinx/coroutines/BuildersKt;"): DexFixture {
        val typeDescriptors = listOf(
            owner,
            "Ljava/lang/Object;",
            "Lkotlin/coroutines/CoroutineContext;",
            "Lkotlin/jvm/functions/Function2;",
            "I",
        )
        val directParameters = listOf(
            "Lkotlin/coroutines/CoroutineContext;",
            "Lkotlin/jvm/functions/Function2;",
        )
        val defaultParameters = directParameters + listOf("I", "Ljava/lang/Object;")
        val allStrings = (
            typeDescriptors +
                listOf("LLL", "LLLIL", "runBlockingK", "runBlockingK\$default")
            ).distinct().sorted()
        val stringIds = allStrings.withIndex().associate { it.value to it.index }
        val typeNames = typeDescriptors.distinct().sortedBy { stringIds.getValue(it) }
        val typeIds = typeNames.withIndex().associate { it.value to it.index }

        val stringIdsOffset = 112
        val typeIdsOffset = stringIdsOffset + allStrings.size * 4
        val protoIdsOffset = typeIdsOffset + typeNames.size * 4
        val methodIdsOffset = protoIdsOffset + 2 * 12
        val dataOffset = methodIdsOffset + 2 * 8
        val data = ByteArrayOutputStream()

        fun appendTypeList(types: List<String>): Int {
            val offset = dataOffset + data.size()
            data.writeLittleEndianInt(types.size)
            types.forEach { type -> data.writeLittleEndianShort(typeIds.getValue(type)) }
            return offset
        }

        val directParametersOffset = appendTypeList(directParameters)
        val defaultParametersOffset = appendTypeList(defaultParameters)
        val stringDataOffsets = IntArray(allStrings.size)
        allStrings.forEachIndexed { index, value ->
            stringDataOffsets[index] = dataOffset + data.size()
            data.writeUleb128(value.length)
            data.write(value.toByteArray(Charsets.US_ASCII))
            data.write(0)
        }

        val fileSize = dataOffset + data.size()
        val dex = ByteArray(fileSize)
        System.arraycopy(byteArrayOf('d'.code.toByte(), 'e'.code.toByte(), 'x'.code.toByte(), '\n'.code.toByte(), '0'.code.toByte(), '3'.code.toByte(), '5'.code.toByte(), 0), 0, dex, 0, 8)
        val buffer = ByteBuffer.wrap(dex).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(32, fileSize)
        buffer.putInt(36, 112)
        buffer.putInt(40, 0x12345678)
        buffer.putInt(56, allStrings.size)
        buffer.putInt(60, stringIdsOffset)
        buffer.putInt(64, typeNames.size)
        buffer.putInt(68, typeIdsOffset)
        buffer.putInt(72, 2)
        buffer.putInt(76, protoIdsOffset)
        buffer.putInt(88, 2)
        buffer.putInt(92, methodIdsOffset)
        buffer.putInt(104, fileSize - dataOffset)
        buffer.putInt(108, dataOffset)

        allStrings.indices.forEach { index ->
            buffer.putInt(stringIdsOffset + index * 4, stringDataOffsets[index])
        }
        typeNames.forEachIndexed { index, type ->
            buffer.putInt(typeIdsOffset + index * 4, stringIds.getValue(type))
        }
        val objectType = typeIds.getValue("Ljava/lang/Object;")
        buffer.putInt(protoIdsOffset, stringIds.getValue("LLL"))
        buffer.putInt(protoIdsOffset + 4, objectType)
        buffer.putInt(protoIdsOffset + 8, directParametersOffset)
        buffer.putInt(protoIdsOffset + 12, stringIds.getValue("LLLIL"))
        buffer.putInt(protoIdsOffset + 16, objectType)
        buffer.putInt(protoIdsOffset + 20, defaultParametersOffset)

        val ownerType = typeIds.getValue(owner)
        buffer.putShort(methodIdsOffset, ownerType.toShort())
        buffer.putShort(methodIdsOffset + 2, 0.toShort())
        buffer.putInt(methodIdsOffset + 4, stringIds.getValue("runBlockingK"))
        buffer.putShort(methodIdsOffset + 8, ownerType.toShort())
        buffer.putShort(methodIdsOffset + 10, 1.toShort())
        buffer.putInt(methodIdsOffset + 12, stringIds.getValue("runBlockingK\$default"))

        data.toByteArray().copyInto(dex, dataOffset)
        return DexFixture(dex, stringIds)
    }

    private fun ByteArrayOutputStream.writeLittleEndianInt(value: Int) {
        write(value and 0xff)
        write((value ushr 8) and 0xff)
        write((value ushr 16) and 0xff)
        write((value ushr 24) and 0xff)
    }

    private fun ByteArrayOutputStream.writeLittleEndianShort(value: Int) {
        write(value and 0xff)
        write((value ushr 8) and 0xff)
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

    private fun assertDexIntegrityIsValid(dex: ByteArray) {
        val expectedSignature = MessageDigest.getInstance("SHA-1").digest(dex.copyOfRange(32, dex.size))
        assertArrayEquals(expectedSignature, dex.copyOfRange(12, 32))
        val expectedChecksum = Adler32().apply { update(dex, 12, dex.size - 12) }.value.toInt()
        val actualChecksum = (dex[8].toInt() and 0xff) or
            ((dex[9].toInt() and 0xff) shl 8) or
            ((dex[10].toInt() and 0xff) shl 16) or
            ((dex[11].toInt() and 0xff) shl 24)
        assertEquals(expectedChecksum, actualChecksum)
    }
}
