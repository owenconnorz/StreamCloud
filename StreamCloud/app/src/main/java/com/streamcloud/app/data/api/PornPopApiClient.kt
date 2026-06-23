package com.streamcloud.app.data.api

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

@Serializable
data class PornPopTaskResponse(
    val taskId: String?    = null,
    val task_id: String?   = null,
    val id: String?        = null,
    val status: String?    = null,
    val result: String?    = null,
    val resultUrl: String? = null,
    val result_url: String? = null,
    val imageUrl: String?  = null,
    val image_url: String? = null,
    val videoUrl: String?  = null,
    val video_url: String? = null,
    val error: String?     = null,
    val message: String?   = null,
    val credits: Int?      = null,
)

/**
 * API client for pornpop.ai AI generation tools.
 * Endpoint stubs are wired to https://pornpop.ai/api — they will work
 * automatically once the backend endpoints are deployed.
 */
object PornPopApiClient {

    private const val BASE = "https://pornpop.ai/api"
    private const val UA   = "StreamCloud/1.0 (Android)"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)   // generation can take a while
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient          = true
        coerceInputValues  = true
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private fun uriToBytes(uri: Uri, context: Context): ByteArray {
        val stream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("Cannot open image")
        return stream.use { it.readBytes() }
    }

    private fun Request.Builder.withCookie(cookie: String?) = apply {
        cookie?.takeIf { it.isNotBlank() }?.let { header("Cookie", it) }
        header("User-Agent", UA)
    }

    private fun String?.parse(): PornPopTaskResponse =
        json.decodeFromString(this ?: "{}")

    // ── public API ─────────────────────────────────────────────────────────

    /**
     * Generate an AI image from an optional photo + text prompt + style.
     */
    suspend fun generateImage(
        prompt: String,
        style: String,
        imageUri: Uri? = null,
        context: Context,
        cookie: String? = null,
    ): PornPopTaskResponse = withContext(Dispatchers.IO) {
        val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("prompt", prompt.ifBlank { "beautiful, high quality" })
            .addFormDataPart("style",  style)
        imageUri?.let {
            val bytes = uriToBytes(it, context)
            builder.addFormDataPart("image", "image.jpg",
                bytes.toRequestBody("image/jpeg".toMediaType()))
        }
        val req = Request.Builder()
            .url("$BASE/generate/image")
            .post(builder.build())
            .withCookie(cookie)
            .build()
        runCatching { client.newCall(req).execute().body?.string().parse() }
            .getOrElse { PornPopTaskResponse(error = it.message) }
    }

    /**
     * Generate an AI video from a photo + optional prompt + style.
     * Returns a task ID to poll for progress.
     */
    suspend fun generateVideo(
        imageUri: Uri,
        style: String,
        prompt: String = "",
        templateId: String? = null,
        context: Context,
        cookie: String? = null,
    ): PornPopTaskResponse = withContext(Dispatchers.IO) {
        val bytes = uriToBytes(imageUri, context)
        val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("style", style)
            .addFormDataPart("image", "image.jpg",
                bytes.toRequestBody("image/jpeg".toMediaType()))
        if (prompt.isNotBlank())   builder.addFormDataPart("prompt",      prompt)
        templateId?.let {          builder.addFormDataPart("template_id", it) }

        val req = Request.Builder()
            .url("$BASE/generate/video")
            .post(builder.build())
            .withCookie(cookie)
            .build()
        runCatching { client.newCall(req).execute().body?.string().parse() }
            .getOrElse { PornPopTaskResponse(error = it.message) }
    }

    /**
     * Remove clothing from a photo using AI.
     */
    suspend fun undress(
        imageUri: Uri,
        context: Context,
        cookie: String? = null,
    ): PornPopTaskResponse = withContext(Dispatchers.IO) {
        val bytes = uriToBytes(imageUri, context)
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("image", "image.jpg",
                bytes.toRequestBody("image/jpeg".toMediaType()))
            .build()
        val req = Request.Builder()
            .url("$BASE/undress")
            .post(body)
            .withCookie(cookie)
            .build()
        runCatching { client.newCall(req).execute().body?.string().parse() }
            .getOrElse { PornPopTaskResponse(error = it.message) }
    }

    /**
     * Poll a task until it finishes (or returns the current status).
     */
    suspend fun checkTask(
        taskId: String,
        cookie: String? = null,
    ): PornPopTaskResponse = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$BASE/task/$taskId")
            .get()
            .withCookie(cookie)
            .build()
        runCatching { client.newCall(req).execute().body?.string().parse() }
            .getOrElse { PornPopTaskResponse(error = it.message) }
    }

    /**
     * Fetch the authenticated user's previous generation tasks.
     */
    suspend fun myTasks(
        cookie: String? = null,
    ): List<PornPopTaskResponse> = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$BASE/tasks")
            .get()
            .withCookie(cookie)
            .build()
        runCatching {
            val body = client.newCall(req).execute().body?.string() ?: "[]"
            json.decodeFromString<List<PornPopTaskResponse>>(body)
        }.getOrElse { emptyList() }
    }

    // ── result URL helper ─────────────────────────────────────────────────

    fun PornPopTaskResponse.resultMediaUrl(): String? =
        result ?: resultUrl ?: result_url ?: imageUrl ?: image_url ?: videoUrl ?: video_url

    val COMPLETED_STATUSES = setOf("completed", "done", "success", "finished")
    val FAILED_STATUSES    = setOf("failed", "error", "cancelled")
}
