package com.example.rammonitor

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale

enum class AppFilter(val label: String) {
    ALL("Semua"),
    USER("Pengguna"),
    SYSTEM("Sistem")
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

private fun matches(app: AppMemory, filter: AppFilter): Boolean = when (filter) {
    AppFilter.ALL -> true
    AppFilter.USER -> !app.isSystem
    AppFilter.SYSTEM -> app.isSystem
}

@Composable
fun RamMonitorScreen() {
    val context = LocalContext.current
    var system by remember { mutableStateOf<SystemMemory?>(null) }
    var kernel by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    var report by remember { mutableStateOf<AppReport?>(null) }
    var filter by remember { mutableStateOf(AppFilter.ALL) }

    LaunchedEffect(Unit) {
        while (true) {
            system = withContext(Dispatchers.IO) { MemoryInfoHelper.getSystemMemory(context) }
            kernel = withContext(Dispatchers.IO) { MemoryInfoHelper.getMeminfo() }
            report = withContext(Dispatchers.IO) { MemoryInfoHelper.collect(context) }
            delay(3000)
        }
    }

    val sys = system
    val rep = report
    val hasAccess = rep?.usageAccess ?: false
    val running = rep?.running.orEmpty().filter { matches(it, filter) }
    val recent = rep?.recent.orEmpty().filter { matches(it, filter) }
    val maxPss = running.maxOfOrNull { it.pssMb }?.coerceAtLeast(1L) ?: 1L
    val totalPss = running.sumOf { it.pssMb }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text("RAM Monitor", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
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
                        }) {
                            Text("Buka Pengaturan")
                        }
                    }
                }
            }
        }

        if (sys != null) {
            item { SystemCard(sys) }
        }

        if (kernel.isNotEmpty() && sys != null) {
            item { KernelCard(kernel, sys.totalMb) }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppFilter.values().forEach { f ->
                    FilterChip(
                        selected = filter == f,
                        onClick = { filter = f },
                        label = { Text(f.label) }
                    )
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
            item {
                Text("Belum ada data proses.", style = MaterialTheme.typography.bodyMedium)
            }
        }

        items(running) { app -> RunningAppCard(app, maxPss) }

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
            items(recent) { app -> RecentAppCard(app) }
        }
    }
}

@Composable
fun SystemCard(sys: SystemMemory) {
    val fraction = if (sys.totalMb > 0) sys.usedMb.toFloat() / sys.totalMb.toFloat() else 0f
    val color = usageColor(fraction)

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
                progress = { fraction },
                color = color,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                StatItem("Tersedia", "${sys.availMb} MB")
                StatItem("Ambang", "${sys.thresholdMb} MB")
                StatItem("Memori rendah", if (sys.lowMemory) "Ya" else "Tidak")
            }
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
                    progress = { fraction },
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
                }
                Text(
                    "${app.pssMb} MB",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { app.pssMb.toFloat() / maxPss.toFloat() },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "PID ${app.pid} • Private ${app.privateDirtyMb} MB • Fokus ${app.foregroundMinutes} menit",
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
                "Terakhir: ${timeFormat.format(app.lastUsed)} • Fokus ${app.foregroundMinutes} menit",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
