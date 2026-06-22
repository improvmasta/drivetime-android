package org.jupiterns.drivetime

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import java.util.concurrent.TimeUnit

/** Polls /api/alerts for unread alerts, posts notifications, marks them read. */
class AlertWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        val s = Settings(applicationContext)
        if (!s.isConfigured || !s.alertsEnabled) return Result.success()
        val client = OkHttpClient()
        return try {
            val resp = client.newCall(
                Request.Builder().url("${s.serverUrl}/api/alerts?unread_only=true").build()
            ).execute()
            val arr = JSONArray(resp.body?.string() ?: "[]")
            resp.close()
            if (arr.length() == 0) return Result.success()
            ensureChannel()
            for (i in 0 until arr.length()) {
                val a = arr.getJSONObject(i)
                notify(a.optInt("id", i), a.optString("title"), a.optString("body"))
            }
            client.newCall(
                Request.Builder().url("${s.serverUrl}/api/alerts/read?key=${s.token}")
                    .post(ByteArray(0).toRequestBody()).build()
            ).execute().close()
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = applicationContext.getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(CHANNEL) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(CHANNEL, "drivetime alerts", NotificationManager.IMPORTANCE_HIGH))
            }
        }
    }

    private fun notify(id: Int, title: String, body: String) {
        val n = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title).setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true).build()
        applicationContext.getSystemService(NotificationManager::class.java).notify(1000 + id, n)
    }

    companion object {
        private const val CHANNEL = "alerts"
        private const val WORK = "drivetime-alerts"

        fun schedule(ctx: Context) {
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                WORK, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<AlertWorker>(15, TimeUnit.MINUTES).build())
        }

        fun cancel(ctx: Context) {
            WorkManager.getInstance(ctx).cancelUniqueWork(WORK)
        }
    }
}
