package org.jupiterns.drivetime

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Batches GPS fixes and POSTs them to drivetime's /api/ingest.
 * Fixes that fail to send are appended to an on-disk queue and retried on the
 * next flush, so tunnels / dead-zones don't lose data.
 *
 * Payload is OwnTracks-shaped so it works with the existing server endpoint:
 *   {"_type":"location","lat":..,"lon":..,"tst":<epoch_s>,"vel":<km/h>,"acc":..,"cog":..}
 */
class Uploader(context: Context, private val settings: Settings) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()
    private val queueFile = File(context.filesDir, "queue.jsonl")
    private val JSON = "application/json".toMediaType()

    @Synchronized
    fun enqueue(lat: Double, lon: Double, epochSec: Long, speedMps: Float?, accuracyM: Float?, courseDeg: Float?) {
        val o = JSONObject()
            .put("_type", "location")
            .put("lat", lat).put("lon", lon).put("tst", epochSec)
        if (speedMps != null) o.put("vel", Math.round(speedMps * 3.6))   // km/h, OwnTracks units
        if (accuracyM != null) o.put("acc", Math.round(accuracyM))
        if (courseDeg != null) o.put("cog", Math.round(courseDeg))
        queueFile.appendText(o.toString() + "\n")
    }

    /** Try to send everything queued. Returns true if the queue is now empty. */
    @Synchronized
    fun flush(): Boolean {
        if (!settings.isConfigured || !queueFile.exists()) return true
        val lines = queueFile.readLines().filter { it.isNotBlank() }
        if (lines.isEmpty()) { queueFile.delete(); return true }

        val batch = JSONArray()
        lines.forEach { batch.put(JSONObject(it)) }
        val req = Request.Builder()
            .url(settings.ingestUrl)
            .post(batch.toString().toRequestBody(JSON))
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) { queueFile.delete(); true } else false
            }
        } catch (e: Exception) {
            false   // keep the queue; retry next flush
        }
    }

    fun queuedCount(): Int =
        if (queueFile.exists()) queueFile.readLines().count { it.isNotBlank() } else 0
}
