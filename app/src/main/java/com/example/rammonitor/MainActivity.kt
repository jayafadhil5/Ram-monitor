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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

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

@Composable
fun RamMonitorScreen() {
    val context = LocalContext.current
    var system by remember { mutableStateOf<SystemMemory?>(null) }
    var processes by remember { mutableStateOf<List<ProcessMemory>>(emptyList()) }
    var kernel by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    var recentApps by remember { mutableStateOf<List<String>>(emptyList()) }
    var hasAccess by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            system = MemoryInfoHelper.getSystemMemory(context)
            processes = MemoryInfoHelper.getProcessMemoryList(context)
            kernel = MemoryInfoHelper.getMeminfo()
            hasAccess = MemoryInfoHelper.hasUsageAccess(context)
            if (hasAccess) {
                recentApps = MemoryInfoHelper.getRecentApps(context)
            }
            delay(2000)
        }
    }

    val sys = system
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text("Monitor RAM", style = MaterialTheme.typography.headlineMedium)
        }

        if (!hasAccess) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Izin Akses Penggunaan diperlukan untuk melihat aplikasi yang aktif.")
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
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Total RAM: ${sys.totalMb} MB")
                        Text("Terpakai: ${sys.usedMb} MB")
                        Text("Tersedia: ${sys.availMb} MB")
                        Text("Status memori rendah: ${if (sys.lowMemory) "Ya" else "Tidak"}")
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { sys.usedMb.toFloat() / sys.totalMb.toFloat() },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }

        if (kernel.isNotEmpty()) {
            item {
                Text("Rincian Kernel (/proc/meminfo)", style = MaterialTheme.typography.titleMedium)
            }
            val keys = listOf("MemTotal", "MemFree", "Cached", "Buffers", "Shmem", "SReclaimable", "AnonPages")
            items(keys.filter { kernel.containsKey(it) }) { key ->
                Text("$key: ${kernel[key]} MB")
            }
        }

        item {
            Text("Penggunaan Memori per Aplikasi (PSS)", style = MaterialTheme.typography.titleMedium)
        }
        items(processes) { p ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    Modifier.padding(12.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(p.name, maxLines = 1)
                    Text("${p.pssMb} MB")
                }
            }
        }

        if (recentApps.isNotEmpty()) {
            item {
                Text("Aplikasi Aktif 24 Jam Terakhir", style = MaterialTheme.typography.titleMedium)
            }
            items(recentApps.take(20)) { pkg ->
                Text(pkg, maxLines = 1)
            }
        }
    }
}
