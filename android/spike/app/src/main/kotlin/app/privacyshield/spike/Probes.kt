package app.privacyshield.spike

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.os.UserManager
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityManager
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.InvocationTargetException
import java.util.UUID

/**
 * Phase 0 verification probes (docs/phase0-spike-plan.md).
 *
 * PRIVACY: probes emit only aggregate counts, histograms and booleans. They never emit package names
 * of other apps (only installer-store names, which identify stores, not people), device serials or
 * identifiers. Output goes to logcat tag [TAG], one JSON object per line, kept well under logcat's
 * ~4 KB line limit.
 *
 * STATUS: written without access to an Android toolchain. It has NOT been compiled or run. The first
 * CI run is the first test.
 */
object Probes {
    const val TAG = "PS_PROBE"
    private const val SCHEMA = 1

    private val APP_OPS = linkedMapOf(
        "OVERLAY" to "android:system_alert_window",
        "USAGE_ACCESS" to "android:get_usage_stats",
        "INSTALL_UNKNOWN" to "android:request_install_packages",
        "ALL_FILES" to "android:manage_external_storage",
    )

    private val SENSITIVE_PERMISSIONS = listOf(
        "android.permission.CAMERA",
        "android.permission.RECORD_AUDIO",
        "android.permission.READ_SMS",
        "android.permission.READ_CONTACTS",
        "android.permission.READ_CALL_LOG",
        "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.ACCESS_BACKGROUND_LOCATION",
    )

    /** Deep links the product wants to use (S16). Literals, so old compile constants are not needed. */
    private val SETTINGS_INTENTS = linkedMapOf(
        "ACCESSIBILITY" to Intent("android.settings.ACCESSIBILITY_SETTINGS"),
        "NOTIFICATION_LISTENER" to Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"),
        "OVERLAY" to Intent("android.settings.action.MANAGE_OVERLAY_PERMISSION"),
        "USAGE_ACCESS" to Intent("android.settings.USAGE_ACCESS_SETTINGS"),
        "INSTALL_UNKNOWN" to Intent("android.settings.MANAGE_UNKNOWN_APP_SOURCES"),
        "ALL_FILES" to Intent("android.settings.MANAGE_ALL_FILES_ACCESS_PERMISSION"),
        "SECURITY" to Intent("android.settings.SECURITY_SETTINGS"),
        "APP_DETAILS" to Intent("android.settings.APPLICATION_DETAILS_SETTINGS", Uri.parse("package:com.android.settings")),
    )

    /** Runs all probes; emits one logcat line each and returns the lines. */
    fun runAll(ctx: Context): List<String> {
        val run = UUID.randomUUID().toString().take(8)
        val lines = mutableListOf<String>()

        fun emit(probe: String, block: () -> JSONObject) {
            val data = try { block() } catch (t: Throwable) { errorJson(t) }
            val line = JSONObject().put("schema", SCHEMA).put("run", run).put("probe", probe).put("data", data).toString()
            lines += line
            Log.i(TAG, line)
        }

        var packages: List<PackageInfo> = emptyList()
        emit("DEVICE") { device() }
        emit("S1") { val (json, pkgs) = s1Inventory(ctx); packages = pkgs; json }
        emit("S2") { s2Installers(ctx, packages) }
        emit("S3") { s3AppOps(ctx, packages) }
        emit("S4") { s4Accessibility(ctx) }
        emit("S5") { s5NotificationListeners(ctx) }
        emit("S6") { s6PrivilegedAppOps(ctx) }
        emit("S10") { s10UsageStats(ctx) }
        emit("S13") { s13OwnPermissions(ctx) }
        emit("S16") { s16DeepLinks(ctx) }
        emit("S18") { s18Profiles(ctx) }
        emit("S19") { s19PreinstalledSensitive(packages) }
        emit("S20") { s20Resources(ctx) }
        emit("S21") { s21Services(ctx) }
        return lines
    }

    // ---------------------------------------------------------------- helpers

    private fun errorJson(t: Throwable): JSONObject {
        val root = if (t is InvocationTargetException && t.cause != null) t.cause!! else t
        return JSONObject().put("error", root.javaClass.name).put("message", (root.message ?: "").take(120))
    }

    private fun hist(map: Map<String, Int>, top: Int = 15): JSONObject {
        val sorted = map.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        val out = JSONObject()
        sorted.take(top).forEach { out.put(it.key, it.value) }
        val rest = sorted.drop(top).sumOf { it.value }
        if (rest > 0) out.put("_other", rest)
        return out
    }

    private fun MutableMap<String, Int>.inc(key: String) { this[key] = (this[key] ?: 0) + 1 }

    private fun isSystem(p: PackageInfo): Boolean =
        (p.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM != 0

    @Suppress("DEPRECATION")
    private fun ownRequestedPermissions(ctx: Context): List<String> =
        ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions?.toList() ?: emptyList()

    // ---------------------------------------------------------------- probes

    private fun device(): JSONObject {
        val emulator = Build.FINGERPRINT.startsWith("generic") || Build.FINGERPRINT.contains("emulator") ||
            Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish")
        return JSONObject()
            .put("manufacturer", Build.MANUFACTURER)
            .put("brand", Build.BRAND)
            .put("model", Build.MODEL)
            .put("sdkInt", Build.VERSION.SDK_INT)
            .put("release", Build.VERSION.RELEASE)
            .put("codename", Build.VERSION.CODENAME)
            .put("securityPatch", Build.VERSION.SECURITY_PATCH)
            .put("hardware", Build.HARDWARE)
            .put("isEmulator", emulator)
    }

    /** S1: package inventory, with and without GET_PERMISSIONS (large inventories can hit binder limits). */
    @Suppress("DEPRECATION")
    private fun s1Inventory(ctx: Context): Pair<JSONObject, List<PackageInfo>> {
        val pm = ctx.packageManager
        val out = JSONObject()
        out.put("holdsQueryAllPackages", ownRequestedPermissions(ctx).contains("android.permission.QUERY_ALL_PACKAGES"))

        val t0 = SystemClock.elapsedRealtime()
        val plain: List<PackageInfo> = try { pm.getInstalledPackages(0) } catch (t: Throwable) {
            out.put("plainError", t.javaClass.name); emptyList()
        }
        val t1 = SystemClock.elapsedRealtime()
        val withPerms: List<PackageInfo> = try { pm.getInstalledPackages(PackageManager.GET_PERMISSIONS) } catch (t: Throwable) {
            out.put("permsError", t.javaClass.name); emptyList()
        }
        val t2 = SystemClock.elapsedRealtime()

        val used = if (withPerms.isNotEmpty()) withPerms else plain
        out.put("countNoFlags", plain.size)
        out.put("countWithPermissions", withPerms.size)
        out.put("systemCount", used.count { isSystem(it) })
        out.put("userCount", used.count { !isSystem(it) })
        out.put("msNoFlags", t1 - t0)
        out.put("msWithPermissions", t2 - t1)
        return out to used
    }

    /** S2: where user apps were installed from. Installer names identify stores, not people. */
    @Suppress("DEPRECATION")
    private fun s2Installers(ctx: Context, pkgs: List<PackageInfo>): JSONObject {
        val pm = ctx.packageManager
        val installers = mutableMapOf<String, Int>()
        val sources = mutableMapOf<String, Int>()
        var errors = 0
        for (p in pkgs) {
            if (isSystem(p)) continue
            try {
                if (Build.VERSION.SDK_INT >= 30) {
                    val info = pm.getInstallSourceInfo(p.packageName)
                    installers.inc(info.installingPackageName ?: "null")
                    if (Build.VERSION.SDK_INT >= 33) sources.inc(info.packageSource.toString())
                } else {
                    installers.inc(pm.getInstallerPackageName(p.packageName) ?: "null")
                }
            } catch (t: Throwable) {
                errors++
            }
        }
        return JSONObject()
            .put("api", if (Build.VERSION.SDK_INT >= 30) "getInstallSourceInfo" else "getInstallerPackageName")
            .put("installers", hist(installers))
            .put("packageSource", hist(sources))
            .put("errors", errors)
    }

    /** S3: can we read special-access state for OTHER apps? Modes: 0 allowed, 1 ignored, 2 errored, 3 default, 4 foreground. */
    @Suppress("DEPRECATION")
    private fun s3AppOps(ctx: Context, pkgs: List<PackageInfo>): JSONObject {
        val aom = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val out = JSONObject()
        for ((name, op) in APP_OPS) {
            val user = mutableMapOf<String, Int>()
            val system = mutableMapOf<String, Int>()
            for (p in pkgs) {
                val target = if (isSystem(p)) system else user
                val uid = p.applicationInfo?.uid
                if (uid == null) { target.inc("exc:NoUid"); continue }
                try {
                    val mode = if (Build.VERSION.SDK_INT >= 29) {
                        aom.unsafeCheckOpNoThrow(op, uid, p.packageName)
                    } else {
                        aom.checkOpNoThrow(op, uid, p.packageName)
                    }
                    target.inc(mode.toString())
                } catch (t: Throwable) {
                    target.inc("exc:" + t.javaClass.simpleName)
                }
            }
            out.put(name, JSONObject().put("user", hist(user)).put("system", hist(system)))
        }
        return out
    }

    /** S4: enabled accessibility services and capability flags. Counts only. */
    private fun s4Accessibility(ctx: Context): JSONObject {
        val am = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabled = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        val canReadContent = enabled.count { (it.capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT) != 0 }
        val out = JSONObject()
            .put("installedCount", am.installedAccessibilityServiceList.size)
            .put("enabledCount", enabled.size)
            .put("enabledCanReadWindowContent", canReadContent)
        if (Build.VERSION.SDK_INT >= 34) {
            out.put("enabledMarkedAccessibilityTool", enabled.count { it.isAccessibilityTool })
        } else {
            out.put("enabledMarkedAccessibilityTool", JSONObject.NULL)
        }
        return out
    }

    /** S5: is the notification-listener setting readable, and how many listeners are enabled? */
    private fun s5NotificationListeners(ctx: Context): JSONObject {
        val raw = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")
        val count = raw?.split(":")?.count { it.isNotBlank() } ?: 0
        return JSONObject().put("readable", raw != null).put("enabledCount", count)
    }

    /** S6: per-app historical AppOps data must NOT be available without privileged access. */
    private fun s6PrivilegedAppOps(ctx: Context): JSONObject {
        val aom = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val method = AppOpsManager::class.java.getMethod("getPackagesForOps", Array<String>::class.java)
        val result = method.invoke(aom, arrayOf("android:camera")) as? List<*>
        return JSONObject().put("blocked", false).put("returnedEntries", result?.size ?: -1)
    }

    /** S10: does UsageStatsManager return data without the usage-access grant? */
    private fun s10UsageStats(ctx: Context): JSONObject {
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - 3_600_000L, now)
        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 86_400_000L, now)
        return JSONObject().put("eventsAvailableWithoutGrant", events.hasNextEvent()).put("dailyStatsCountWithoutGrant", stats?.size ?: 0)
    }

    /** S13: the permissions the merged manifest actually contains. */
    private fun s13OwnPermissions(ctx: Context): JSONObject =
        JSONObject().put("requested", JSONArray(ownRequestedPermissions(ctx).sorted()))

    /** S16: do the settings deep links we want to use resolve on this device? */
    private fun s16DeepLinks(ctx: Context): JSONObject {
        val pm = ctx.packageManager
        val out = JSONObject()
        for ((name, intent) in SETTINGS_INTENTS) {
            out.put(name, pm.resolveActivity(intent, 0) != null)
        }
        return out
    }

    /** S18: number of user profiles visible (work profile, Private Space, etc.). Cloned-app spaces need manual checks. */
    private fun s18Profiles(ctx: Context): JSONObject {
        val um = ctx.getSystemService(Context.USER_SERVICE) as UserManager
        return JSONObject().put("userProfilesVisible", um.userProfiles.size)
    }

    /** S19: how many preinstalled apps hold sensitive permissions, versus user apps. Aggregate only. */
    private fun s19PreinstalledSensitive(pkgs: List<PackageInfo>): JSONObject {
        fun tally(group: List<PackageInfo>): JSONObject {
            val perPermission = mutableMapOf<String, Int>()
            var holdingAny = 0
            for (p in group) {
                val names = p.requestedPermissions ?: continue
                val flags = p.requestedPermissionsFlags ?: continue
                var any = false
                for (i in names.indices) {
                    if (i < flags.size && names[i] in SENSITIVE_PERMISSIONS &&
                        (flags[i] and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
                    ) {
                        perPermission.inc(names[i].substringAfterLast('.'))
                        any = true
                    }
                }
                if (any) holdingAny++
            }
            return JSONObject().put("apps", group.size).put("holdingAnyGranted", holdingAny).put("grantedPerPermission", hist(perPermission))
        }
        return JSONObject().put("system", tally(pkgs.filter { isSystem(it) })).put("user", tally(pkgs.filter { !isSystem(it) }))
    }

    /** S20: memory class of the device, to decide scan budgets for low-RAM phones. */
    private fun s20Resources(ctx: Context): JSONObject {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return JSONObject()
            .put("isLowRamDevice", am.isLowRamDevice)
            .put("memoryClassMb", am.memoryClass)
            .put("largeMemoryClassMb", am.largeMemoryClass)
            .put("totalMemMb", mi.totalMem / 1_048_576L)
    }

    /** S21: are Google or Huawei mobile services present? The app must work with neither. */
    @Suppress("DEPRECATION")
    private fun s21Services(ctx: Context): JSONObject {
        val pm = ctx.packageManager
        fun present(pkg: String) = try { pm.getPackageInfo(pkg, 0); true } catch (t: PackageManager.NameNotFoundException) { false }
        return JSONObject()
            .put("googlePlayServices", present("com.google.android.gms"))
            .put("googlePlayStore", present("com.android.vending"))
            .put("huaweiMobileServices", present("com.huawei.hwid"))
    }
}
