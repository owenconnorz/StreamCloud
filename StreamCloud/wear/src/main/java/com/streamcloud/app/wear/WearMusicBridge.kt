package com.streamcloud.app.wear

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal object WearMusicBridge {
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()

    suspend fun request(context: Context, operation: String, values: JSONObject = JSONObject()): JSONObject {
        val appContext = context.applicationContext
        val requestId = UUID.randomUUID().toString()
        val response = CompletableDeferred<JSONObject>()
        pending[requestId] = response

        try {
            withContext(Dispatchers.IO) {
                val node = Tasks.await(Wearable.getNodeClient(appContext).connectedNodes)
                    .firstOrNull()
                    ?: error("Connect the watch to your phone to browse music.")
                val payload = JSONObject(values.toString())
                    .put("requestId", requestId)
                    .put("operation", operation)
                Tasks.await(
                    Wearable.getMessageClient(appContext)
                        .sendMessage(
                            node.id,
                            WearMusicProtocol.REQUEST_PATH,
                            payload.toString().toByteArray(Charsets.UTF_8),
                        ),
                )
            }
            return withTimeout(RESPONSE_TIMEOUT_MS) { response.await() }
        } finally {
            pending.remove(requestId)
        }
    }

    fun receive(event: MessageEvent) {
        if (event.path != WearMusicProtocol.RESPONSE_PATH) return
        val json = runCatching { JSONObject(String(event.data, Charsets.UTF_8)) }.getOrNull() ?: return
        pending.remove(json.optString("requestId"))?.complete(json)
    }

    private const val RESPONSE_TIMEOUT_MS = 15_000L
}
