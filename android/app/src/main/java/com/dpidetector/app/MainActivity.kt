package com.dpidetector.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.dpidetector.app.data.DpiEngine
import com.dpidetector.app.ui.screens.HostProbeScreen
import com.dpidetector.app.ui.screens.ReportsScreen
import com.dpidetector.app.ui.screens.ScannerScreen
import com.dpidetector.app.ui.screens.SettingsScreen
import com.dpidetector.app.ui.theme.DpiTheme
import com.dpidetector.app.ui.theme.Palette
import com.dpidetector.app.util.ExportFormat
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* optional notification permission */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)

        requestNotificationPermissionIfNeeded()

        val engine = (application as DpiApp).engine
        setContent {
            DpiTheme {
                DpiDetectorApp(
                    engine = engine,
                    onShareReport = { file -> shareFile(file, "application/json") },
                    onShareFormattedFile = ::shareFile,
                    onInstallApk = ::installApkFile,
                )
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                runCatching { notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
            }
        }
    }

    private fun shareFile(file: File, mimeType: String) {
        if (!file.exists()) return
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "DPI Detector (${file.name})")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Поделиться файлом"))
    }

    private fun installApkFile(file: File) {
        if (!file.exists()) return
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { startActivity(intent) }
    }
}

private data class NavTab(val label: String, val icon: ImageVector)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DpiDetectorApp(
    engine: DpiEngine,
    onShareReport: (File) -> Unit,
    onShareFormattedFile: (File, String) -> Unit = { f, _ -> onShareReport(f) },
    onInstallApk: (File) -> Unit = {},
) {
    val state by engine.state.collectAsState()
    val net by engine.networkMonitor.state.collectAsState()
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableIntStateOf(0) }

    val tabs = listOf(
        NavTab("Сканер", Icons.Default.PlayArrow),
        NavTab("Хост", Icons.Default.Search),
        NavTab("Отчёты", Icons.AutoMirrored.Filled.List),
        NavTab("Настройки", Icons.Default.Settings),
    )

    val exportReportHandler: (File, ExportFormat) -> Unit = { reportFile, fmt ->
        scope.launch {
            engine.exportReportFile(reportFile, fmt).onSuccess { out ->
                onShareFormattedFile(out, fmt.mime)
            }
        }
    }

    val exportBatchHandler: (ExportFormat) -> Unit = { fmt ->
        scope.launch {
            engine.exportBatchResults(fmt).onSuccess { out ->
                onShareFormattedFile(out, fmt.mime)
            }
        }
    }

    Scaffold(
        containerColor = Palette.Bg,
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = "DPI Детектор",
                                style = MaterialTheme.typography.titleLarge,
                            )
                            Text(
                                text = "dpi-checkers · v${BuildConfig.VERSION_NAME} · by Ivan Tokarev",
                                style = MaterialTheme.typography.labelSmall,
                                color = Palette.TextMuted,
                                fontSize = 11.sp,
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Palette.Bg,
                        titleContentColor = Palette.TextPrimary,
                    ),
                )
                HorizontalDivider(color = Palette.Border, thickness = 1.dp)
            }
        },
        bottomBar = {
            Column {
                HorizontalDivider(color = Palette.Border, thickness = 1.dp)
                NavigationBar(containerColor = Palette.Surface) {
                    tabs.forEachIndexed { idx, item ->
                        NavigationBarItem(
                            selected = tab == idx,
                            onClick = { tab = idx },
                            icon = { Icon(item.icon, contentDescription = item.label) },
                            label = { Text(item.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Palette.Good,
                                selectedTextColor = Palette.Good,
                                indicatorColor = Palette.GoodBg,
                                unselectedIconColor = Palette.TextSecondary,
                                unselectedTextColor = Palette.TextSecondary,
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (tab) {
                0 -> ScannerScreen(
                    state = state,
                    net = net,
                    onToggleChecker = engine::toggleChecker,
                    onToggleSection = engine::toggleSection,
                    onStart = engine::startCheck,
                    onCancel = engine::cancelCheck,
                    onClearError = engine::clearError,
                    onShareReport = onShareReport,
                    onExportReport = exportReportHandler,
                    onRetryGeo = { engine.loadGeo(force = true, allowOnline = true) },
                )

                1 -> HostProbeScreen(
                    state = state,
                    onProbe = engine::probeCustomHost,
                    onClearHistory = engine::clearHostProbes,
                    onStartBatch = { raw, port, sni, mode -> engine.startBatchScan(raw, port, sni, mode) },
                    onPauseBatch = engine::pauseBatchScan,
                    onResumeBatch = { engine.resumeBatchScan() },
                    onRetryFailedBatch = { engine.retryFailedBatchItems() },
                    onStopBatch = engine::stopBatchScan,
                    onExportBatch = exportBatchHandler,
                )

                2 -> ReportsScreen(
                    reports = state.reports,
                    onRefresh = engine::refreshReports,
                    onLoadReport = engine::loadReport,
                    onDeleteReport = engine::deleteReport,
                    onShareReport = onShareReport,
                    onExportReport = exportReportHandler,
                )

                3 -> SettingsScreen(
                    state = state,
                    onUpdateGeo = { engine.loadGeo(force = true, allowOnline = true) },
                    onSaveConfig = engine::saveUserConfig,
                    onLoadDefaultYaml = engine::loadDefaultConfigYaml,
                    onCheckUpdates = { engine.checkForUpdates() },
                    onDownloadUpdate = engine::downloadUpdate,
                    onInstallUpdate = { path -> onInstallApk(File(path)) },
                )
            }
        }
    }
}
