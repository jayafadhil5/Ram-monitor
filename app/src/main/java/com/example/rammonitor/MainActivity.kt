
package com.example.rammonitor

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale

enum class AppFilter(val label: String) {
    ALL("Semua"),
    USER("Pengguna"),
    SYSTEM("Sistem")
}

enum class SortMode(val label: String) {
    PSS("Memori"),
    NAME("Nama"),
    LAST_USED("Terakhir")
}

private val timeFormat = SimpleDateFormat("dd MMM HH:mm", Locale.forLanguageTag("id-ID"))

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    RamMonitorScreen()
                }
            }
        }
    }
}

fun usageColor(fraction: Float): Color = when {
    fraction < 0.6f -> Color(0xFF2E7D32)
    fraction < 0.85f -> Color(0xFFEF6C00)
    else -> Color(0xFFC62828)
}

private fun matches(app: AppMemory, filter: AppFilter, query: String): Boolean {
    val passFilter = when (filter) {
        AppFilter.ALL -> true
        AppFilter.USER -> !app.isSystem
        AppFilter.SYSTEM -> app.isSystem
    }
    if (!passFilter) return false
    if (query.isBlank()) return true
    val q = query.lowercase()
    return app.label.lowercase().contains(q) || app.packageName.lowercase().contains(q)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RamMonitorScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var system by remember { mutableStateOf<SystemMemory?>(null) }
    var kernel by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    var report by remember { mutableStateOf<AppReport?>(null) }
    var device by remember { mutableStateOf<DeviceInfo?>(null) }
    var prevUsed by remember { mutableStateOf<Long?>(null) }

    var filter by remember { mutableStateOf(AppFilter.ALL) }
    var sortMode by remember { mutableStateOf(SortMode.PSS) }
    var query by remember { mutableStateOf("") }
    var isRefreshing by remember { mutableStateOf(false) }

    suspend fun refreshOnce() {
        isRefreshing = true
        system = withContext(Dispatchers.IO) { MemoryInfoHelper.getSystemMemory(context) }
        kernel = withContext(Dispatchers.IO) { MemoryInfoHelper.getMeminfo() }
        report = withContext(Dispatchers.IO) { MemoryInfoHelper.collect(context) }
        device = withContext(Dispatchers.IO) { MemoryInfoHelper.getDeviceInfo(context) }
        prevUsed = system?.usedMb
        isRefreshing = false
    }

    LaunchedEffect(Unit) {
        while (true) {
            refreshOnce()
            delay(3000)
        }
    }

    val sys = system
    val rep = report
    val hasAccess = rep?.usageAccess ?: false

    val running = rep?.running.orEmpty()
        .filter { matches(it, filter, query) }
        .let { list ->
            when (sortMode) {
                SortMode.PSS -> list.sortedByDescending { it.pssMb }
                SortMode.NAME -> list.sortedBy { it.label.lowercase() }
                SortMode.LAST_USED -> list.sortedByDescending { it.lastUsed }
            }
        }
    val recent = rep?.recent.orEmpty().filter { matches(it, filter, query) }
    val maxPss = running.maxOfOrNull { it.pssMb }?.coerceAtLeast(1L) ?: 1L
    val totalPss = running.sumOf { it.pssMb }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "RAM Monitor",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = { scope.launch { refreshOnce() } }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                }
            }
        }

        if (!hasAccess) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Izin Akses Penggunaan diperlukan", fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Dibutuhkan untuk melihat aplikasi yang dipakai dan waktu fokusnya.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = {
                            context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                        }) { Text("Buka Pengaturan") }
                    }
                }
            }
        }

        if (sys != null) {
            item { SystemCard(sys, prevUsed) }
        }

        if (device != null) {
            item { DeviceCard(device!!) }
        }

        if (kernel.isNotEmpty() && sys != null) {
            item { KernelCard(kernel, sys.totalMb) }
        }

        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Cari aplikasi…") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { })
            )
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppFilter.values().forEach { f ->
                        FilterChip(
                            selected = filter == f,
                            onClick = { filter = f },
                            label = { Text(f.label) }
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SortMode.values().forEach { s ->
                        FilterChip(
                            selected = sortMode == s,
                            onClick = { sortMode = s },
                            label = { Text("Sort: ${s.label}") }
                        )
                    }
                }
            }
        }

        item {
            Text(
                "Aplikasi Berjalan",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "${running.size} proses • total PSS ${totalPss} MB",
                style = MaterialTheme.typography.bodySmall
            )
        }

        if (running.isEmpty()) {
            item { Text("Belum ada data proses.", style = MaterialTheme.typography.bodyMedium) }
        }

        items(running, key = { it.packageName + "_" + it.pid }) { app ->
            RunningAppCard(app, maxPss)
        }

        if (recent.isNotEmpty()) {
            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Dipakai 24 Jam Terakhir (tidak berjalan)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "Memori tidak tersedia untuk aplikasi ini.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            items(recent, key = { "recent_" + it.packageName }) { app -> RecentAppCard(app) }
        }
    }
}

@Composable
fun SystemCard(sys: SystemMemory, prevUsed: Long?) {
    val fraction = if (sys.totalMb > 0) sys.usedMb.toFloat() / sys.totalMb.toFloat() else 0f
    val color = usageColor(fraction)
    val delta = prevUsed?.let { sys.usedMb - it }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Memori Terpakai", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "${sys.usedMb} / ${sys.totalMb} MB",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    if (delta != null && delta != 0L) {
                        Text(
                            if (delta > 0) "▲ +$delta MB" else "▼ $delta MB",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (delta > 0) Color(0xFFC62828) else Color(0xFF2E7D32)
                        )
                    }
                }
                Text(
                    "${(fraction * 100).toInt()}%",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = color
                )
            }
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = fraction,
                color = color,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                StatItem("Tersedia", "${sys.availMb} MB")
                StatItem("Cache", "${sys.cachedMb} MB")
                StatItem("Free", "${sys.freeMb} MB")
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                StatItem("Ambang", "${sys.thresholdMb} MB")
                StatItem("Memori rendah", if (sys.lowMemory) "Ya" else "Tidak")
            }
            if (sys.lowMemory) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "⚠ Memori rendah! Sistem mungkin menutup aplikasi latar.",
                    color = Color(0xFFC62828),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
fun DeviceCard(device: DeviceInfo) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Perangkat", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                StatItem("CPU", "${device.cpuCores} core")
                StatItem("Uptime", "${device.uptimeMinutes} menit")
                StatItem(
                    "Baterai",
                    "${device.batteryPercent}% ${if (device.batteryCharging) "⚡" else ""}"
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(device.androidVersion, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun StatItem(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun KernelCard(kernel: Map<String, Long>, totalMb: Long) {
    val rows = listOf(
        "MemFree" to "Bebas",
        "Cached" to "Cache",
        "Buffers" to "Buffer",
        "Shmem" to "Shared memory",
        "SReclaimable" to "Reclaimable",
        "AnonPages" to "Anonim (aplikasi)"
    )
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Rincian Kernel", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            rows.forEach { (key, label) ->
                val value = kernel[key] ?: return@forEach
                val fraction = if (totalMb > 0) value.toFloat() / totalMb.toFloat() else 0f
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                    Text("$value MB", fontWeight = FontWeight.SemiBold)
                }
                LinearProgressIndicator(
                    progress = fraction,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

@Composable
fun RunningAppCard(app: AppMemory, maxPss: Long) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            app.label,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Spacer(Modifier.width(6.dp))
                        AssistChip(
                            onClick = { },
                            label = { Text(app.category.label, fontSize = 10.sp) }
                        )
                    }
                    Text(
                        app.packageName,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    "${app.pssMb} MB",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = (app.pssMb.toFloat() / maxPss.toFloat()).coerceIn(0f, 1f),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "PID ${app.pid} • Private ${app.privateDirtyMb} MB • Fokus ${app.foregroundMinutes} mnt",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
fun RecentAppCard(app: AppMemory) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(
                app.label,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                app.packageName,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Terakhir: ${timeFormat.format(app.lastUsed)} • Fokus ${app.foregroundMinutes} mnt • ${app.category.label}",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}