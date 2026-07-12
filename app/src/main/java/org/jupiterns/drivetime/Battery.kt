package org.jupiterns.drivetime

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.BatteryManager
import android.os.PowerManager
import android.provider.Settings

/**
 * Helpers to keep the logger alive. Android Doze — and Samsung's "Sleeping
 * apps" / "Deep sleeping apps" lists in particular — will silently kill a
 * background foreground service, dropping fixes mid-drive. We detect the
 * battery-optimization state and route the user to fix it.
 */
object Battery {

    fun isExempt(ctx: Context): Boolean {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(ctx.packageName)
    }

    /** Current battery charge as a whole percent (0–100), or null when unavailable. Used to
     *  record how much a drive cost the battery ([LocationService] stamps start/end). */
    fun levelPct(ctx: Context): Int? {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return null
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return if (pct in 0..100) pct else null
    }

    /** System dialog to whitelist the app from battery optimization. */
    @SuppressLint("BatteryLife")
    fun requestExemption(ctx: Context) {
        try {
            ctx.startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${ctx.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            openAppSettings(ctx)
        }
    }

    /** App info screen — manual path, and where Samsung's battery toggle lives. */
    fun openAppSettings(ctx: Context) {
        try {
            ctx.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${ctx.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {
        }
    }
}
