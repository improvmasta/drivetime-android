package org.jupiterns.drivetime

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/**
 * Manufacturer-specific deep links into the OEM "auto-start / protected /
 * sleeping apps" settings — the single most common reason a background service
 * silently dies on a non-Pixel phone.
 *
 * Each known OEM has *one* page worth routing to. We try its known component, and
 * if the intent doesn't resolve (model differences, ROM changes), we fall back to
 * the standard battery-optimization exemption screen and ultimately the app-info
 * page so the user is never stranded.
 *
 * Detection is by `Build.MANUFACTURER` — coarse but sufficient: OEMs ship the
 * same battery-killer behaviour across their lineup. The advice strings explain
 * *what* the user is fixing in plain English, since "go to Settings → Apps →
 * drivetime → Battery → Don't kill" is impossible to guess.
 */
object OemBatteryLinks {

    data class Help(val label: String, val advice: String)

    /** What to call the "fix me" button + a one-liner explaining what to look for. */
    fun help(): Help = when (oem()) {
        Oem.SAMSUNG -> Help(
            "Open Samsung battery settings",
            "Settings → Battery → Background usage limits → Sleeping apps → " +
                "remove drivetime; also Never-sleeping apps → add drivetime.")
        Oem.XIAOMI -> Help(
            "Open Xiaomi auto-start",
            "Settings → Apps → Manage apps → drivetime → Autostart ON; " +
                "Battery saver → No restrictions.")
        Oem.HUAWEI -> Help(
            "Open Huawei protected apps",
            "Settings → Battery → App launch → drivetime → Manage manually → " +
                "all three switches ON.")
        Oem.ONEPLUS -> Help(
            "Open OnePlus battery optimisation",
            "Settings → Battery → Battery optimisation → drivetime → Don't optimise; " +
                "Auto-launch → ON.")
        Oem.OPPO, Oem.REALME -> Help(
            "Open ColorOS app settings",
            "Settings → Battery → App battery management → drivetime → " +
                "Allow background activity + Allow auto-launch.")
        Oem.VIVO -> Help(
            "Open Vivo background settings",
            "Settings → Battery → Background power consumption management → " +
                "drivetime → Allow.")
        Oem.ASUS -> Help(
            "Open Asus auto-start",
            "Settings → Power Master → Auto-start manager → drivetime → ON.")
        else -> Help(
            "Open battery settings",
            "Allow background activity for drivetime so the OS doesn't kill it.")
    }

    /** Try the OEM's protected-apps page; fall back to app-info if it isn't there. */
    fun openProtectedAppsPage(context: Context) {
        for (intent in candidates(context)) {
            if (canResolve(context, intent)) {
                runCatching {
                    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    return
                }
            }
        }
        Battery.requestExemption(context)
    }

    private fun candidates(context: Context): List<Intent> {
        val pkg = context.packageName
        return when (oem()) {
            Oem.SAMSUNG -> listOf(
                Intent().setComponent(ComponentName(
                    "com.samsung.android.lool",
                    "com.samsung.android.sm.ui.battery.BatteryActivity")),
                Intent().setComponent(ComponentName(
                    "com.samsung.android.sm_cn",
                    "com.samsung.android.sm.ui.battery.BatteryActivity")),
                appInfo(pkg),
            )
            Oem.XIAOMI -> listOf(
                Intent().setComponent(ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity")),
                Intent("miui.intent.action.OP_AUTO_START"),
                appInfo(pkg),
            )
            Oem.HUAWEI -> listOf(
                Intent().setComponent(ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")),
                Intent().setComponent(ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.optimize.process.ProtectActivity")),
                appInfo(pkg),
            )
            Oem.ONEPLUS -> listOf(
                Intent().setComponent(ComponentName(
                    "com.oneplus.security",
                    "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity")),
                appInfo(pkg),
            )
            Oem.OPPO, Oem.REALME -> listOf(
                Intent().setComponent(ComponentName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity")),
                Intent().setComponent(ComponentName(
                    "com.oppo.safe",
                    "com.oppo.safe.permission.startup.StartupAppListActivity")),
                appInfo(pkg),
            )
            Oem.VIVO -> listOf(
                Intent().setComponent(ComponentName(
                    "com.iqoo.secure",
                    "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager")),
                Intent().setComponent(ComponentName(
                    "com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")),
                appInfo(pkg),
            )
            Oem.ASUS -> listOf(
                Intent().setComponent(ComponentName(
                    "com.asus.mobilemanager",
                    "com.asus.mobilemanager.entry.FunctionActivity")),
                appInfo(pkg),
            )
            else -> listOf(appInfo(pkg))
        }
    }

    private fun appInfo(pkg: String) = Intent(
        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        android.net.Uri.parse("package:$pkg"))

    private fun canResolve(ctx: Context, intent: Intent): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ctx.packageManager.resolveActivity(intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())) != null
        } else {
            @Suppress("DEPRECATION")
            ctx.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null
        }
    } catch (_: Exception) { false }

    enum class Oem { SAMSUNG, XIAOMI, HUAWEI, ONEPLUS, OPPO, REALME, VIVO, ASUS, OTHER }

    fun oem(): Oem = when (Build.MANUFACTURER.lowercase()) {
        "samsung" -> Oem.SAMSUNG
        "xiaomi", "redmi", "poco" -> Oem.XIAOMI
        "huawei", "honor" -> Oem.HUAWEI
        "oneplus" -> Oem.ONEPLUS
        "oppo" -> Oem.OPPO
        "realme" -> Oem.REALME
        "vivo" -> Oem.VIVO
        "asus" -> Oem.ASUS
        else -> Oem.OTHER
    }
}
