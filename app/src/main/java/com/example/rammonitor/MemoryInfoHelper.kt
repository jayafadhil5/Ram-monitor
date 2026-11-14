package com.example.rammonitor

import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import java.io.BufferedReader
import java.io.FileReader

private const val DAY_MS = 24L * 60 * 60 * 1000

data class SystemMemory(
    val totalMb: Long,
    val availMb: Long,
    val usedMb: Long,
    val thresholdMb: Long,
    val lowMemory: Boolean,
    val cachedMb: Long = 0,
    val freeMb: Long = 0
)

data class AppMemory(
    val label: String,
    val packageName: String,
    val pid: Int,
    val pssMb: Long,
    val privateDirtyMb: Long,
    val foregroundMinutes: Long,
    val lastUsed: Long,
    val isSystem: Boolean,
    val category: AppCategory = AppCategory.UNKNOWN
)

data class AppReport(
    val running: List<AppMemory>,
    val recent: List<AppMemory>,
    val usageAccess: Boolean,
    val totalPssMb: Long = 0,
    val timestamp: Long = System.currentTimeMillis()
)

enum class AppCategory(val label: String) {
    GAME("Game"),
    SOCIAL("Sosmed"),
    MEDIA("Media"),
    PRODUCTIVITY("Produktivitas"),
    SYSTEM("Sistem"),
    UNKNOWN("Lainnya")
}

data class DeviceInfo(
    val cpuCores: Int,
    val uptimeMinutes: Long,
    val batteryPercent: Int,
    val batteryCharging: Boolean,
    val androidVersion: String
)

object MemoryInfoHelper {

    fun getSystemMemory(context: Context): SystemMemory {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        val mb = 1024L * 1024L
        val kernel = getMeminfo()
        return SystemMemory(
            totalMb = info.totalMem / mb,
            availMb = info.availMem / mb,
            usedMb = (info.totalMem - info.availMem) / mb,
            thresholdMb = info.threshold / mb,
            lowMemory = info.lowMemory,
            cachedMb = kernel["Cached"] ?: 0L,
            freeMb = kernel["MemFree"] ?: 0L
        )
    }

    fun getMeminfo(): Map<String, Long> {
        val result = mutableMapOf<String, Long>()
        try {
            BufferedReader(FileReader("/proc/meminfo")).use { reader ->
                reader.forEachLine { line ->
                    val parts = line.split(Regex("\\s+"))
                    if (parts.size >= 2) {
                        val key = parts[0].removeSuffix(":")
                        val valueKb = parts[1].toLongOrNull() ?: return@forEachLine
                        result[key] = valueKb / 1024L
                    }
                }
            }
        } catch (_: Exception) { /* abaikan */ }
        return result
    }

    fun getDeviceInfo(context: Context): DeviceInfo {
        val cores = Runtime.getRuntime().availableProcessors()
        val uptime = SystemClock.elapsedRealtime() / 60000L
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val battery = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val intent: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        return DeviceInfo(
            cpuCores = cores,
            uptimeMinutes = uptime,
            batteryPercent = battery,
            batteryCharging = charging,
            androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        )
    }

    fun collect(context: Context): AppReport {
        val pm = context.packageManager
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val hasAccess = hasUsageAccess(context)

        val usage: Map<String, UsageStats> = if (hasAccess) {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val end = System.currentTimeMillis()
            usm.queryAndAggregateUsageStats(end - DAY_MS, end) ?: emptyMap()
        } else emptyMap()

        val processes = am.runningAppProcesses ?: emptyList()
        val pids = processes.map { it.pid }.toIntArray()
        val infos: Array<Debug.MemoryInfo> =
            if (pids.isNotEmpty()) am.getProcessMemoryInfo(pids) else emptyArray()

        val running = processes.mapIndexed { i, proc ->
            val pkg = proc.pkgList?.firstOrNull() ?: proc.processName
            val info = infos.getOrNull(i)
            buildApp(
                pm = pm,
                pkg = pkg,
                pid = proc.pid,
                pssKb = info?.totalPss?.toLong() ?: 0L,
                dirtyKb = info?.totalPrivateDirty?.toLong() ?: 0L,
                stats = usage[pkg]
            )
        }.sortedByDescending { it.pssMb }

        val runningPkgs = running.map { it.packageName }.toSet()
        val recent = usage.values
            .filter { it.totalTimeInForeground > 0 && it.packageName !in runningPkgs }
            .map { buildApp(pm, it.packageName, -1, 0L, 0L, it) }
            .sortedByDescending { it.lastUsed }

        return AppReport(
            running = running,
            recent = recent,
            usageAccess = hasAccess,
            totalPssMb = running.sumOf { it.pssMb }
        )
    }

    private fun buildApp(
        pm: PackageManager,
        pkg: String,
        pid: Int,
        pssKb: Long,
        dirtyKb: Long,
        stats: UsageStats?
    ): AppMemory {
        val appInfo: ApplicationInfo? = try {
            pm.getApplicationInfo(pkg, 0)
        } catch (_: Exception) { null }
        val label = appInfo?.loadLabel(pm)?.toString() ?: pkg
        val isSystem = appInfo?.let {
            (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        } ?: false
        val category = categorize(appInfo?.category ?: -1, pkg, isSystem)
        return AppMemory(
            label = label,
            packageName = pkg,
            pid = pid,
            pssMb = pssKb / 1024L,
            privateDirtyMb = dirtyKb / 1024L,
            foregroundMinutes = (stats?.totalTimeInForeground ?: 0L) / 60000L,
            lastUsed = stats?.lastTimeUsed ?: System.currentTimeMillis(),
            isSystem = isSystem,
            category = category
        )
    }

    private fun categorize(apiCategory: Int, pkg: String, isSystem: Boolean): AppCategory {
        if (isSystem) return AppCategory.SYSTEM
        return when {
            pkg.contains("game") -> AppCategory.GAME
            pkg.contains("social") || pkg.contains("facebook") ||
                    pkg.contains("instagram") || pkg.contains("twitter") ||
                    pkg.contains("tiktok") || pkg.contains("whatsapp") -> AppCategory.SOCIAL
            pkg.contains("video") || pkg.contains("music") ||
                    pkg.contains("youtube") || pkg.contains("spotify") -> AppCategory.MEDIA
            apiCategory == ApplicationInfo.CATEGORY_GAME -> AppCategory.GAME
            apiCategory == ApplicationInfo.CATEGORY_VIDEO ||
                    apiCategory == ApplicationInfo.CATEGORY_AUDIO -> AppCategory.MEDIA
            apiCategory == ApplicationInfo.CATEGORY_PRODUCTIVITY ||
                    apiCategory == ApplicationInfo.CATEGORY_NEWS -> AppCategory.PRODUCTIVITY
            else -> AppCategory.UNKNOWN
        }
    }

    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }
}