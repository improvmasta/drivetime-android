package org.jupiterns.drivetime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.work.WorkManager
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/**
 * **Every** notification the app posts except the logger's ongoing drive card, which is a
 * different animal (persistent, foreground-service-bound, always on).
 *
 * Two families share this one door:
 *  - **Decision prompts** (NOTIFICATIONS.md P3/P4) — "drive completed, tag it", "gas-stop
 *    split detected", the weekly digest. One-shot, tappable, and **default OFF**.
 *  - **Health alerts** (P5) — [KIND_CHECK_ENGINE] and [KIND_TRACKING_HEALTH]. These used to
 *    post themselves from LocationService and Watchdog, each inventing its own channel, id
 *    scheme, and (non-)retraction. They now come through here, which is what buys them a
 *    channel in the group, a toggle, a deep link, and honest retraction.
 *
 * One OS channel per kind, so the system's own channel controls map 1:1 to the app's
 * toggles. [post] is the single gate: it silently no-ops unless the kind's toggle is on AND
 * notifications are actually postable, so callers never need to pre-check.
 *
 * **Retraction** happens two ways, because the two families know different things:
 *  - The decision prompts are retracted by the SPA, which pushes its pending-attention
 *    snapshot over the bridge once per sync tick ([setPendingAttention]). Every posted
 *    notification is remembered here, and any whose item the SPA no longer produces — the
 *    drive got tagged/merged, the pair dismissed — is cancelled. Matching is by timestamp
 *    with [MATCH_TOLERANCE_SEC] slack, because native stamps a drive's start at
 *    tier-detection time while the SPA's segmenter reads it off the first fix; the two can
 *    differ by a few seconds.
 *  - The health alerts are retracted by their own producer, which is the only thing that
 *    knows the fact: LocationService cancels a trouble code the moment the dongle stops
 *    reporting it, and the bridge cancels the kill warning when the user acknowledges it.
 *    Nothing about a DTC is visible to the SPA's replica, so routing these through the
 *    attention push would be inventing a fact the SPA does not have.
 */
object Notify {

    const val KIND_DRIVE_COMPLETE = "drive_complete"
    const val KIND_GAS_STOP = "gas_stop"
    const val KIND_WEEKLY_DIGEST = "weekly_digest"
    /** Channel id deliberately unchanged from LocationService's old ad-hoc `ALERT_CHANNEL`,
     *  so a user who already silenced or tuned check-engine alerts keeps that setting. */
    const val KIND_CHECK_ENGINE = "check_engine"
    const val KIND_TRACKING_HEALTH = "tracking_health"

    /** The kill warning is a singleton — one "we lost your drives" at a time, replaced in
     *  place rather than stacked, and cancelled by this id when the user acknowledges it. */
    const val HEALTH_ID = "1"

    /** Native drive boundaries vs the SPA's segmented ones: same drive, seconds apart. */
    const val MATCH_TOLERANCE_SEC = 180L

    private const val GROUP_ID = "drivetime_events"
    private const val PREFS = "drivetime_notify"
    private const val KEY_ATTENTION = "pending_attention"
    private const val KEY_TICK_AT = "spa_tick_at_ms"
    private const val KEY_POSTED = "posted"
    private const val POSTED_CAP = 40

    /** Ad-hoc channels the pre-P5 producers created for themselves. Deleted on sight: nothing
     *  posts to them any more, and leaving them behind would strand both an orphan row in the
     *  OS channel list and any notification still sitting on one (which no code could cancel,
     *  since the ids that addressed them are gone too). */
    private val RETIRED_CHANNELS = listOf("alerts", "drivetime-health")

    /** The retired server-poll AlertWorker's unique work. Cancelled by NAME (the class is
     *  gone) so a phone upgrading from an older APK stops waking every 15 minutes to poll an
     *  endpoint it no longer needs — check-engine is read straight off the dongle now. */
    private const val RETIRED_ALERT_WORK = "drivetime-alerts"

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun mgr(ctx: Context) =
        ctx.getSystemService(NotificationManager::class.java)

    private fun notifId(kind: String, id: String) = (kind + id).hashCode()

    /**
     * The per-kind master toggle. Every *decision prompt* ships default OFF (they are nags,
     * and the app should be quiet until asked). The two health alerts keep the defaults they
     * already had before P5 folded them in here: check-engine off (it is meaningless without
     * a paired dongle), tracking-health **on** — see [Settings.notifyTrackingHealth].
     */
    fun enabledFor(s: Settings, kind: String): Boolean = when (kind) {
        KIND_DRIVE_COMPLETE -> s.notifyDriveComplete
        KIND_GAS_STOP -> s.notifyGasStop
        KIND_WEEKLY_DIGEST -> s.notifyDigest
        KIND_CHECK_ENGINE -> s.alertsEnabled
        KIND_TRACKING_HEALTH -> s.notifyTrackingHealth
        else -> false
    }

    private fun canPost(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return false
        return mgr(ctx).areNotificationsEnabled()
    }

    private fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val m = mgr(ctx)
        // Every kind, every time — NOT "return early if the first channel exists". Creating a
        // channel that already exists is a no-op that preserves the user's own channel
        // settings, whereas the early return would mean an app that adds a kind (the P4 digest)
        // never creates its channel on an install that already has the older ones — and
        // notifying on a channel that doesn't exist is silently dropped.
        m.createNotificationChannelGroup(NotificationChannelGroup(GROUP_ID, "Events"))
        // A decision prompt is DEFAULT (it can wait for the next glance at the phone); a
        // health alert is HIGH (a fault light and a tracker the OS killed are both "you are
        // losing data right now" — they earn the heads-up).
        for ((id, spec) in listOf(
            KIND_DRIVE_COMPLETE to ("Drive completed" to NotificationManager.IMPORTANCE_DEFAULT),
            KIND_GAS_STOP to ("Gas-stop detected" to NotificationManager.IMPORTANCE_DEFAULT),
            KIND_WEEKLY_DIGEST to ("Weekly digest" to NotificationManager.IMPORTANCE_DEFAULT),
            KIND_CHECK_ENGINE to ("Check-engine alerts" to NotificationManager.IMPORTANCE_HIGH),
            KIND_TRACKING_HEALTH to ("Tracking health" to NotificationManager.IMPORTANCE_HIGH),
        )) {
            val ch = NotificationChannel(id, spec.first, spec.second)
            ch.group = GROUP_ID
            m.createNotificationChannel(ch)
        }
        for (old in RETIRED_CHANNELS) runCatching { m.deleteNotificationChannel(old) }
    }

    /** One-shot cleanup for phones upgrading across P5: drop the retired poll's background
     *  work. Safe to call repeatedly (cancelling unknown unique work is a no-op). */
    fun cancelRetiredAlertPoll(ctx: Context) {
        runCatching { WorkManager.getInstance(ctx).cancelUniqueWork(RETIRED_ALERT_WORK) }
    }

    /**
     * Post one event notification. No-op (false) unless [kind]'s toggle is on and
     * POST_NOTIFICATIONS is granted. [id] is the item's stable key — a drive's start_ts, a
     * pair's right_ts (epoch seconds as a string), a DTC code, [HEALTH_ID] for the singleton
     * kill warning. It dedupes re-posts (same notif id) and is what retraction addresses.
     * Tapping deep-links the SPA to [route] via the `dt_route` extra.
     */
    fun post(ctx: Context, kind: String, id: String, title: String, body: String, route: String): Boolean {
        val s = Settings(ctx)
        if (!enabledFor(s, kind)) return false
        if (!canPost(ctx)) return false
        ensureChannels(ctx)
        val nid = notifId(kind, id)
        // requestCode = nid: each notification needs its OWN PendingIntent — with a shared
        // requestCode, FLAG_UPDATE_CURRENT would stamp every pending tap with the newest route.
        val open = PendingIntent.getActivity(
            ctx, nid,
            Intent(ctx, WebViewActivity::class.java)
                .putExtra(WebViewActivity.EXTRA_ROUTE, route)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(ctx, kind)
            .setSmallIcon(R.drawable.ic_notif_driving)
            .setColor(ctx.getColor(R.color.dt_accent))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        val ok = runCatching { mgr(ctx).notify(nid, n) }.isSuccess
        if (ok) {
            remember(ctx, kind, id)
            EventLog.info("Notification posted: $kind $id")
        }
        return ok
    }

    fun cancel(ctx: Context, kind: String, id: String) {
        runCatching { mgr(ctx).cancel(notifId(kind, id)) }
        forget(ctx, kind, id)
    }

    // ---- pending attention (the SPA's once-per-tick push) ----

    class TagPrompt(val startTs: Long, val label: String, val miles: Double)

    class Attention(
        val tickAtMs: Long,
        val tagPrompts: List<TagPrompt>,
        val gasPairs: List<Long>,
        val untagged: Int,
        val suggested: Int,
    )

    /**
     * Store the SPA's pending-attention snapshot ({tagPrompts, gasPairs, untagged,
     * suggested}) + the tick time, then cancel any posted notification whose item is no
     * longer pending — this is the retraction channel: tag or merge a drive anywhere and
     * the stale notification disappears on the next tick.
     */
    fun setPendingAttention(ctx: Context, json: String) {
        val att = parse(json, System.currentTimeMillis()) ?: return
        prefs(ctx).edit()
            .putString(KEY_ATTENTION, json)
            .putLong(KEY_TICK_AT, att.tickAtMs)
            .apply()
        for (entry in postedList(ctx)) {
            val parts = entry.split("|")
            if (parts.size < 2) continue
            val kind = parts[0]
            val id = parts[1]
            val ts = id.toLongOrNull()
            val still = when (kind) {
                // A timestamp-keyed item is still pending if the SPA still lists it. A
                // non-numeric id can't be matched at all, so it's left alone (?: true).
                KIND_DRIVE_COMPLETE -> ts?.let { t ->
                    att.tagPrompts.any { abs(it.startTs - t) <= MATCH_TOLERANCE_SEC }
                } ?: true
                KIND_GAS_STOP -> ts?.let { t ->
                    att.gasPairs.any { abs(it - t) <= MATCH_TOLERANCE_SEC }
                } ?: true
                // The digest isn't about one drive: it stands until the backlog is empty, so
                // tagging the last untagged drive clears last week's digest from the shade.
                KIND_WEEKLY_DIGEST -> att.untagged > 0
                // The health alerts are none of the SPA's business — its replica has no idea
                // whether a trouble code is still standing or the logger is running. Their
                // own producers retract them; the attention push must never touch them.
                KIND_CHECK_ENGINE, KIND_TRACKING_HEALTH -> true
                else -> true
            }
            if (!still) {
                cancel(ctx, kind, id)
                EventLog.info("Notification retracted: $kind $id")
            }
        }
    }

    /** The last snapshot the SPA pushed, or null if it never has (or it won't parse). */
    fun attention(ctx: Context): Attention? {
        val p = prefs(ctx)
        val raw = p.getString(KEY_ATTENTION, null) ?: return null
        return parse(raw, p.getLong(KEY_TICK_AT, 0L))
    }

    private fun parse(json: String, tickAtMs: Long): Attention? = runCatching {
        val o = JSONObject(json)
        val prompts = mutableListOf<TagPrompt>()
        val pArr = o.optJSONArray("tagPrompts") ?: JSONArray()
        for (i in 0 until pArr.length()) {
            val e = pArr.getJSONObject(i)
            prompts.add(TagPrompt(e.optLong("start_ts"), e.optString("label"), e.optDouble("miles", 0.0)))
        }
        val pairs = mutableListOf<Long>()
        val gArr = o.optJSONArray("gasPairs") ?: JSONArray()
        for (i in 0 until gArr.length()) pairs.add(gArr.getLong(i))
        Attention(tickAtMs, prompts, pairs, o.optInt("untagged"), o.optInt("suggested"))
    }.getOrNull()

    // ---- posted registry (what retraction scans) ----

    private fun postedList(ctx: Context): List<String> = runCatching {
        val arr = JSONArray(prefs(ctx).getString(KEY_POSTED, "[]") ?: "[]")
        (0 until arr.length()).map { arr.getString(it) }
    }.getOrDefault(emptyList())

    private fun writePosted(ctx: Context, list: List<String>) {
        val arr = JSONArray()
        // Cap by dropping oldest: entries the user swiped away are never cancelled, so
        // without a cap the registry would only ever grow.
        for (e in list.takeLast(POSTED_CAP)) arr.put(e)
        prefs(ctx).edit().putString(KEY_POSTED, arr.toString()).apply()
    }

    private fun remember(ctx: Context, kind: String, id: String) {
        val key = "$kind|$id"
        val list = postedList(ctx).filter { it != key } + key
        writePosted(ctx, list)
    }

    private fun forget(ctx: Context, kind: String, id: String) {
        val key = "$kind|$id"
        writePosted(ctx, postedList(ctx).filter { it != key })
    }
}
