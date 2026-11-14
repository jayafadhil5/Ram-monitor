package com.example.rammonitor

import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process
import java.io.BufferedReader
import java.io.FileReader

data class ProcessMemory(
    val name: String,
    val pid: Int,
    val pssMb: Long
)

data class SystemMemory(
    val totalMb: Long,
    val availMb: Long,
    val usedMb: Long,
    val lowMemory: Boolean
)

object MemoryInfoHelper {

    fun getSystemMemory(context: Context): SystemMemory {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        val mb = 1024L * 1024L
        return SystemMemory(
            totalMb = info.totalMem / mb,
            availMb = info.availMem / mb,
            usedMb = (info.totalMem - info.availMem) / mb,
            lowMemory = info.lowMemory
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
        } catch (_: Exception) {
        }
        return result
    }

    fun getRecentApps(context: Context): List<String> {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val end = System.currentTimeMillis()
        val start = end - 24L * 60 * 60 * 1000
        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, end)
        return stats
            .filter { it.lastTimeUsed > 0 }
            .sortedByDescending { it.lastTimeUsed }
            .map { it.packageName }
            .distinct()
    }

    fun getProcessMemoryList(context: Context): List<ProcessMemory> {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val processes = am.runningAppProcesses ?: return emptyList()
        val pids = processes.map { it.pid }.toIntArray()
        val infos = am.getProcessMemoryInfo(pids)
        return processes.mapIndexed { index, proc ->
            ProcessMemory(
                name = proc.processName,
                pid = proc.pid,
                pssMb = infos[index].totalPss / 1024L
            )
        }.sortedByDescending { it.pssMb }
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
