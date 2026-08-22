package com.streamcloud.app.data.recognition

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Base64
import com.streamcloud.app.data.network.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin

/**
 * Short, one-shot music recognition using microphone PCM and the Shazam-compatible
 * fingerprint format used by OpenTune. No audio recording is written to disk.
 *
 * The app is GPL-licensed, so this implementation keeps the compatible algorithm in
 * the app rather than introducing another runtime module. It does not copy OpenTune
 * branding or its UI.
 */
class MusicRecognizer {
    private val closeRequested = AtomicBoolean(false)
    private val closeExecutor = Executors.newSingleThreadExecutor()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun recognize(): RecognitionResult {
        val samples = recordPcm(sampleRateHz = SAMPLE_RATE_HZ, recordMs = CAPTURE_MS)
        val signature = withContext(Dispatchers.Default) {
            ShazamSignatureGenerator().apply { feedPcm16Mono(samples) }.nextSignatureOrNull()
        } ?: throw RecognitionException("Could not prepare the microphone signal")

        return requestRecognition(signature)
    }

    fun close() {
        if (!closeRequested.compareAndSet(false, true)) return
        // Closing a TLS socket can write a close-notify record. Compose calls this method from
        // DisposableEffect on the main thread, where Android StrictMode correctly rejects that
        // network I/O. Use a short-lived worker so disposal remains non-blocking.
        closeExecutor.execute {
            try {
                httpClient.dispatcher.cancelAll()
                httpClient.dispatcher.executorService.shutdown()
                httpClient.connectionPool.evictAll()
                httpClient.cache?.close()
            } finally {
                closeExecutor.shutdown()
            }
        }
    }

    private suspend fun requestRecognition(signature: ShazamSignature): RecognitionResult =
        withContext(Dispatchers.IO) {
            val requestId = UUID.randomUUID().toString().uppercase()
            val tagId = UUID.randomUUID().toString()
            val timestamp = System.currentTimeMillis() / 1000
            val requestPayload = ShazamRequest(
                geolocation = ShazamRequest.Geolocation(0.0, 0.0, 0.0),
                signature = ShazamRequest.Signature(
                    sampleMs = signature.sampleDurationMs,
                    timestamp = timestamp,
                    uri = signature.uri,
                ),
                timestamp = timestamp,
                timezone = TimeZone.getDefault().id,
            )
            val body = Net.json.encodeToString(ShazamRequest.serializer(), requestPayload)
                .toRequestBody("application/json".toMediaType())
            val url = "https://amp.shazam.com/discovery/v5/en/US/android/-/tag/" +
                "$requestId/$tagId?sync=true&webv3=true&sampling=true&connected=" +
                "&shazamapiversion=v3&sharehub=true&video=v3"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Content-Language", "en_US")
                .post(body)
                .build()

            val call = httpClient.newCall(request)
            val cancellation = coroutineContext[Job]?.invokeOnCompletion { cause ->
                if (cause != null) call.cancel()
            }
            try {
                call.execute().use { response ->
                when {
                    response.code == 404 -> throw NoMatchException()
                    response.code == 429 -> throw RecognitionException(
                        "The recognition service is busy. Please try again in a moment.",
                    )
                    !response.isSuccessful -> throw RecognitionException(
                        "Recognition failed (error ${response.code})",
                    )
                }
                val responseBody = response.body?.string().orEmpty()
                val parsed = Net.json.decodeFromString(
                    ShazamResponse.serializer(),
                    responseBody,
                )
                parsed.toRecognitionResult() ?: throw NoMatchException()
                }
            } finally {
                cancellation?.dispose()
            }
        }

    private suspend fun recordPcm(sampleRateHz: Int, recordMs: Long): ShortArray =
        withContext(Dispatchers.IO) {
            val channel = AudioFormat.CHANNEL_IN_MONO
            val encoding = AudioFormat.ENCODING_PCM_16BIT
            val minBuffer = AudioRecord.getMinBufferSize(sampleRateHz, channel, encoding)
                .takeIf { it > 0 }
                ?.coerceAtLeast(4096)
                ?: throw RecognitionException("This device cannot capture microphone audio")
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRateHz,
                channel,
                encoding,
                minBuffer,
            )
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                throw RecognitionException("This device cannot capture microphone audio")
            }

            val totalSamples = (recordMs * sampleRateHz / 1000L).toInt()
            val output = ShortArray(totalSamples)
            val buffer = ShortArray((minBuffer / 2).coerceAtLeast(1024))
            var written = 0
            try {
                recorder.startRecording()
                if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    throw RecognitionException("Microphone recording could not start")
                }
                while (written < output.size) {
                    coroutineContext.ensureActive()
                    val read = recorder.read(
                        buffer,
                        0,
                        minOf(buffer.size, output.size - written),
                    )
                    when {
                        read > 0 -> {
                            System.arraycopy(buffer, 0, output, written, read)
                            written += read
                        }
                        read == AudioRecord.ERROR_INVALID_OPERATION ||
                            read == AudioRecord.ERROR_BAD_VALUE ||
                            read == AudioRecord.ERROR_DEAD_OBJECT -> {
                            throw RecognitionException("Microphone recording was interrupted")
                        }
                    }
                }
                return@withContext output
            } finally {
                runCatching {
                    if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        recorder.stop()
                    }
                }
                recorder.release()
            }
        }

    companion object {
        const val CAPTURE_MS = 4_200L
        private const val SAMPLE_RATE_HZ = 16_000
        private const val USER_AGENT =
            "Dalvik/2.1.0 (Linux; U; Android 10; StreamCloud Music)"
    }
}

open class RecognitionException(message: String) : Exception(message)

class NoMatchException : RecognitionException("No match found")

data class RecognitionResult(
    val title: String,
    val artist: String,
    val album: String?,
    val artworkUrl: String?,
    val artworkHqUrl: String?,
    val youtubeVideoId: String?,
)

sealed interface MusicRecognitionState {
    data object Ready : MusicRecognitionState
    data object Listening : MusicRecognitionState
    data object Processing : MusicRecognitionState
    data object PermissionDenied : MusicRecognitionState
    data class Success(val result: RecognitionResult) : MusicRecognitionState
    data class NoMatch(val message: String = "Try moving closer to the music, then listen again.") :
        MusicRecognitionState
    data class Error(val message: String) : MusicRecognitionState
}

@Serializable
private data class ShazamRequest(
    val geolocation: Geolocation,
    val signature: Signature,
    val timestamp: Long,
    val timezone: String,
) {
    @Serializable
    data class Geolocation(
        val altitude: Double,
        val latitude: Double,
        val longitude: Double,
    )

    @Serializable
    data class Signature(
        @SerialName("samplems") val sampleMs: Long,
        val timestamp: Long,
        val uri: String,
    )
}

@Serializable
private data class ShazamResponse(
    val track: Track? = null,
    val tagid: String? = null,
) {
    @Serializable
    data class Track(
        val key: String? = null,
        val title: String? = null,
        val subtitle: String? = null,
        val images: Images? = null,
        val hub: Hub? = null,
        val sections: List<Section?>? = null,
    )

    @Serializable
    data class Images(
        val coverart: String? = null,
        val coverarthq: String? = null,
    )

    @Serializable
    data class Hub(
        val options: List<Option?>? = null,
    )

    @Serializable
    data class Option(
        val type: String? = null,
        val actions: List<Action?>? = null,
    )

    @Serializable
    data class Action(
        val uri: String? = null,
    )

    @Serializable
    data class Section(
        val type: String? = null,
        val metadata: List<Metadata?>? = null,
    )

    @Serializable
    data class Metadata(
        val title: String? = null,
        val text: String? = null,
    )

    fun toRecognitionResult(): RecognitionResult? {
        val foundTrack = track ?: return null
        val title = foundTrack.title?.trim().orEmpty()
        val artist = foundTrack.subtitle?.trim().orEmpty()
        if (title.isBlank() || artist.isBlank()) return null
        val songSection = foundTrack.sections.orEmpty().filterNotNull()
            .firstOrNull { it.type == "SONG" }
        val album = songSection?.metadata.orEmpty().filterNotNull()
            .firstOrNull { it.title.equals("Album", ignoreCase = true) }?.text
        val videoUri = foundTrack.hub?.options.orEmpty().filterNotNull()
            .firstOrNull { it.type?.contains("video", ignoreCase = true) == true }
            ?.actions.orEmpty().filterNotNull().firstOrNull()?.uri

        return RecognitionResult(
            title = title,
            artist = artist,
            album = album?.trim()?.takeIf { it.isNotEmpty() },
            artworkUrl = foundTrack.images?.coverart,
            artworkHqUrl = foundTrack.images?.coverarthq,
            youtubeVideoId = videoUri?.youtubeVideoId(),
        )
    }
}

private fun String.youtubeVideoId(): String? {
    substringAfter("v=", "").substringBefore('&').takeIf { it.length == 11 }?.let { return it }
    substringAfterLast('/').substringBefore('?').takeIf { it.length == 11 }?.let { return it }
    return null
}

private data class ShazamSignature(
    val uri: String,
    val sampleDurationMs: Long,
)

/**
 * Adapted from the OpenTune-compatible Shazam signature algorithm. The input is mono,
 * signed 16-bit PCM at 16 kHz; the output is the compact binary signature expected by
 * the public recognition endpoint.
 */
private class ShazamSignatureGenerator(
    private val maxTimeSeconds: Double = 3.1,
    private val maxPeaks: Int = 255,
) {
    private val pending = IntArrayList()
    private var processedSamples = 0
    private val sampleRateHz = 16_000
    private val fft = Fft(2048)
    private val window = DoubleArray(2048) { index ->
        0.5 - 0.5 * cos(2.0 * PI * (index + 1) / 2049.0)
    }
    private val realBuffer = DoubleArray(2048)
    private val imagBuffer = DoubleArray(2048)
    private val ringSamples = IntArray(2048)
    private var ringSamplePos = 0
    private val fftOutputs = Array(256) { DoubleArray(1025) }
    private var fftPos = 0
    private var fftWritten = 0
    private val spreadOutputs = Array(256) { DoubleArray(1025) }
    private var spreadPos = 0
    private var spreadWritten = 0
    private var signatureNumberSamples = 0
    private val bandToPeaks = linkedMapOf<FrequencyBand, MutableList<FrequencyPeak>>()

    fun feedPcm16Mono(samples: ShortArray) {
        samples.forEach { pending.add(it.toInt()) }
    }

    fun nextSignatureOrNull(): ShazamSignature? {
        if (pending.size - processedSamples < 128) return null
        while (
            pending.size - processedSamples >= 128 &&
            (
                signatureNumberSamples.toDouble() / sampleRateHz < maxTimeSeconds ||
                    bandToPeaks.values.sumOf { it.size } < maxPeaks
                )
        ) {
            processInput(processedSamples, processedSamples + 128)
            processedSamples += 128
        }
        if (bandToPeaks.isEmpty()) return null
        val message = DecodedMessage(
            sampleRateHz = sampleRateHz,
            numberSamples = signatureNumberSamples,
            frequencyBandToSoundPeaks = bandToPeaks.toSortedMap(compareBy { it.value })
                .mapValues { it.value.toList() },
        )
        return ShazamSignature(
            uri = message.encodeToUri(),
            sampleDurationMs = signatureNumberSamples * 1000L / sampleRateHz,
        )
    }

    private fun processInput(start: Int, endExclusive: Int) {
        signatureNumberSamples += endExclusive - start
        var position = start
        while (position < endExclusive) {
            doFft(position)
            doPeakSpreadingAndRecognition()
            position += 128
        }
    }

    private fun doFft(start: Int) {
        var index = start
        while (index < start + 128) {
            ringSamples[ringSamplePos] = pending[index]
            ringSamplePos = (ringSamplePos + 1) % ringSamples.size
            index++
        }
        var position = ringSamplePos
        for (i in 0 until 2048) {
            if (position == 2048) position = 0
            realBuffer[i] = ringSamples[position].toDouble() * window[i]
            imagBuffer[i] = 0.0
            position++
        }
        fft.fft(realBuffer, imagBuffer)
        val output = fftOutputs[fftPos]
        for (k in 0..1024) {
            val value = (realBuffer[k] * realBuffer[k] + imagBuffer[k] * imagBuffer[k]) / 131072.0
            output[k] = if (value <= 1e-10) 1e-10 else value
        }
        fftPos = (fftPos + 1) % fftOutputs.size
        fftWritten++
    }

    private fun doPeakSpreadingAndRecognition() {
        doPeakSpreading()
        if (spreadWritten >= 46) doPeakRecognition()
    }

    private fun doPeakSpreading() {
        val origin = fftOutputs[(fftPos - 1).floorMod(fftOutputs.size)]
        val originSpread = DoubleArray(1025)
        for (i in 0..1021) originSpread[i] = max(origin[i], max(origin[i + 1], origin[i + 2]))
        originSpread[1022] = origin[1022]
        originSpread[1023] = origin[1023]
        originSpread[1024] = origin[1024]
        val s1 = spreadOutputs[(spreadPos - 1).floorMod(spreadOutputs.size)]
        val s2 = spreadOutputs[(spreadPos - 3).floorMod(spreadOutputs.size)]
        val s3 = spreadOutputs[(spreadPos - 6).floorMod(spreadOutputs.size)]
        for (bin in 0..1024) {
            val first = max(originSpread[bin], s1[bin])
            s1[bin] = first
            val second = max(first, s2[bin])
            s2[bin] = second
            s3[bin] = max(second, s3[bin])
        }
        System.arraycopy(originSpread, 0, spreadOutputs[spreadPos], 0, 1025)
        spreadPos = (spreadPos + 1) % spreadOutputs.size
        spreadWritten++
    }

    private fun doPeakRecognition() {
        val fftMinus46 = fftOutputs[(fftPos - 46).floorMod(fftOutputs.size)]
        val spreadMinus49 = spreadOutputs[(spreadPos - 49).floorMod(spreadOutputs.size)]
        val offsets = intArrayOf(-10, -7, -4, -3, 1, 2, 5, 8)
        val timeOffsets = intArrayOf(-53, -45, 165, 172, 179, 186, 193, 200, 214, 221, 228, 235, 242, 249)
        for (bin in 10..1014) {
            val energy = fftMinus46[bin]
            if (energy < 1.0 / 64.0 || energy < spreadMinus49[bin - 1]) continue
            var maxNeighbor = 0.0
            offsets.forEach { maxNeighbor = max(maxNeighbor, spreadMinus49[bin + it]) }
            if (energy <= maxNeighbor) continue
            var maxTimeNeighbor = maxNeighbor
            timeOffsets.forEach {
                maxTimeNeighbor = max(maxTimeNeighbor, spreadOutputs[(spreadPos + it).floorMod(spreadOutputs.size)][bin - 1])
            }
            if (energy <= maxTimeNeighbor) continue
            val fftNumber = spreadWritten - 46
            val peakMagnitude = ln(max(1.0 / 64.0, energy)) * 1477.3 + 6144.0
            val before = ln(max(1.0 / 64.0, fftMinus46[bin - 1])) * 1477.3 + 6144.0
            val after = ln(max(1.0 / 64.0, fftMinus46[bin + 1])) * 1477.3 + 6144.0
            val variation = peakMagnitude * 2.0 - before - after
            if (variation <= 0.0) continue
            val correctedBin = bin * 64.0 + (after - before) * 32.0 / variation
            val frequencyHz = correctedBin * (sampleRateHz / 2.0 / 1024.0 / 64.0)
            val band = when {
                frequencyHz in 250.0..520.0 -> FrequencyBand.HZ_250_520
                frequencyHz > 520.0 && frequencyHz <= 1450.0 -> FrequencyBand.HZ_520_1450
                frequencyHz > 1450.0 && frequencyHz <= 3500.0 -> FrequencyBand.HZ_1450_3500
                frequencyHz > 3500.0 && frequencyHz <= 5500.0 -> FrequencyBand.HZ_3500_5500
                else -> null
            } ?: continue
            bandToPeaks.getOrPut(band) { mutableListOf() }.add(
                FrequencyPeak(fftNumber, peakMagnitude.toInt(), correctedBin.toInt()),
            )
        }
    }

    private enum class FrequencyBand(val value: Int) {
        HZ_250_520(0), HZ_520_1450(1), HZ_1450_3500(2), HZ_3500_5500(3),
    }

    private data class FrequencyPeak(
        val fftPassNumber: Int,
        val peakMagnitude: Int,
        val correctedPeakFrequencyBin: Int,
    )

    private data class DecodedMessage(
        val sampleRateHz: Int,
        val numberSamples: Int,
        val frequencyBandToSoundPeaks: Map<FrequencyBand, List<FrequencyPeak>>,
    ) {
        fun encodeToUri(): String =
            "data:audio/vnd.shazam.sig;base64," +
                Base64.encode(encodeToBinary(), Base64.NO_WRAP).decodeToString()

        private fun encodeToBinary(): ByteArray {
            val contents = ByteArrayOutputStream()
            for ((band, peaks) in frequencyBandToSoundPeaks.entries.sortedBy { it.key.value }) {
                val peakBytes = ByteArrayOutputStream()
                var lastFftPass = 0
                for (peak in peaks) {
                    var delta = peak.fftPassNumber - lastFftPass
                    if (delta >= 255) {
                        peakBytes.write(0xFF)
                        peakBytes.writeLittleInt(peak.fftPassNumber)
                        lastFftPass = peak.fftPassNumber
                        delta = 0
                    }
                    peakBytes.write(delta.coerceIn(0, 254))
                    peakBytes.writeLittleShort(peak.peakMagnitude)
                    peakBytes.writeLittleShort(peak.correctedPeakFrequencyBin)
                    lastFftPass = peak.fftPassNumber
                }
                val value = peakBytes.toByteArray()
                contents.writeLittleInt(0x60030040 + band.value)
                contents.writeLittleInt(value.size)
                contents.write(value)
                repeat((-value.size).floorMod(4)) { contents.write(0) }
            }
            val contentsValue = contents.toByteArray()
            val sizeMinusHeader = contentsValue.size + 8
            val header = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN).apply {
                putInt(0xCAFE2580.toInt())
                putInt(0)
                putInt(sizeMinusHeader)
                putInt(0x94119C00.toInt())
                putInt(0)
                putInt(0)
                putInt(0)
                putInt(3 shl 27)
                putInt(0)
                putInt(0)
                putInt((numberSamples + sampleRateHz * 0.24).toInt())
                putInt((15 shl 19) + 0x40000)
            }
            val full = ByteArrayOutputStream()
            full.write(header.array())
            full.writeLittleInt(0x40000000)
            full.writeLittleInt(sizeMinusHeader)
            full.write(contentsValue)
            val bytes = full.toByteArray()
            val crc = java.util.zip.CRC32().apply { update(bytes, 8, bytes.size - 8) }.value.toInt()
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(4, crc)
            return bytes
        }
    }
}

private fun Int.floorMod(mod: Int): Int {
    val remainder = this % mod
    return if (remainder < 0) remainder + mod else remainder
}

private class IntArrayList(initialCapacity: Int = 8192) {
    private var array = IntArray(initialCapacity)
    var size: Int = 0
        private set

    operator fun get(index: Int): Int = array[index]

    fun add(value: Int) {
        if (size == array.size) array = array.copyOf(array.size * 2)
        array[size++] = value
    }
}

private fun ByteArrayOutputStream.writeLittleInt(value: Int) {
    write(value and 0xFF)
    write((value ushr 8) and 0xFF)
    write((value ushr 16) and 0xFF)
    write((value ushr 24) and 0xFF)
}

private fun ByteArrayOutputStream.writeLittleShort(value: Int) {
    write(value and 0xFF)
    write((value ushr 8) and 0xFF)
}

private class Fft(private val n: Int) {
    private val cosTable = DoubleArray(n / 2) { cos(2.0 * PI * it / n) }
    private val sinTable = DoubleArray(n / 2) { sin(2.0 * PI * it / n) }
    private val bitReversal = IntArray(n).also { output ->
        val bits = Integer.numberOfTrailingZeros(n)
        for (i in 0 until n) output[i] = i.reverseBits(bits)
    }

    fun fft(real: DoubleArray, imag: DoubleArray) {
        for (i in 0 until n) {
            val j = bitReversal[i]
            if (j > i) {
                var temp = real[i]
                real[i] = real[j]
                real[j] = temp
                temp = imag[i]
                imag[i] = imag[j]
                imag[j] = temp
            }
        }
        var length = 2
        while (length <= n) {
            val halfLength = length / 2
            val tableStep = n / length
            var start = 0
            while (start < n) {
                var offset = 0
                while (offset < halfLength) {
                    val left = start + offset
                    val right = left + halfLength
                    val cosine = cosTable[offset * tableStep]
                    val sine = sinTable[offset * tableStep]
                    val realRight = real[right]
                    val imagRight = imag[right]
                    val tempReal = realRight * cosine + imagRight * sine
                    val tempImag = -realRight * sine + imagRight * cosine
                    real[right] = real[left] - tempReal
                    imag[right] = imag[left] - tempImag
                    real[left] += tempReal
                    imag[left] += tempImag
                    offset++
                }
                start += length
            }
            length = length shl 1
        }
    }
}

private fun Int.reverseBits(bitCount: Int): Int {
    var input = this
    var output = 0
    repeat(bitCount) {
        output = (output shl 1) or (input and 1)
        input = input ushr 1
    }
    return output
}