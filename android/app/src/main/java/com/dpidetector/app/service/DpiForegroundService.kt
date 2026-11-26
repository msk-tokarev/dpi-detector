// Author: by Ivan Tokarev
package com.dpidetector.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.dpidetector.app.DpiApp
import com.dpidetector.app.MainActivity
import com.dpidetector.app.data.BatchJobStatus
import com.dpidetector.app.data.EngineUiState

class DpiForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as? DpiApp
        val engine = app?.engine

        when (intent?.action) {
            ACTION_PAUSE_BATCH -> engine?.pauseBatchScan()
            ACTION_RESUME_BATCH -> engine?.resumeBatchScan()
            ACTION_STOP_ALL -> {
                engine?.cancelCheck()
                engine?.stopBatchScan()
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }
        }

        val state = engine?.state?.value
        if (state == null || (!state.running && state.batch.status != BatchJobStatus.RUNNING && state.batch.status != BatchJobStatus.PAUSED)) {
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = buildNotification(state)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
    }

    private fun stopForegroundCompat() {
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java) ?: return
            val ch = NotificationChannel(
                CHANNEL_ID,
                "Фоновое сканирование DPI",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Прогресс проверки ТСПУ/DPI и пакетного сканирования подсетей"
                setShowBadge(false)
            }
            mgr.createNotificationChannel(ch)
        }
    }

    internal fun buildNotification(state: EngineUiState): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, DpiForegroundService::class.java).setAction(ACTION_STOP_ALL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)

        if (state.batch.status == BatchJobStatus.RUNNING || state.batch.status == BatchJobStatus.PAUSED) {
            val b = state.batch
            val isPaused = b.status == BatchJobStatus.PAUSED
            val etaStr = b.etaSeconds?.let { " · ~${it}с" } ?: ""
            builder
                .setContentTitle(if (isPaused) "Пакетный скан на паузе" else "Пакетное сканирование DPI")
                .setContentText("Проверено: ${b.completed} / ${b.total} · Проблем: ${b.issues}$etaStr")
                .setProgress(b.total.coerceAtLeast(1), b.completed, false)

            val toggleAction = if (isPaused) ACTION_RESUME_BATCH else ACTION_PAUSE_BATCH
            val toggleLabel = if (isPaused) "Продолжить" else "Пауза"
            val toggleIntent = PendingIntent.getService(
                this,
                2,
                Intent(this, DpiForegroundService::class.java).setAction(toggleAction),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(0, toggleLabel, toggleIntent)
            builder.addAction(0, "Стоп", stopIntent)
        } else {
            val doneSections = state.results.sections.count { it.state != com.dpidetector.app.model.TaskState.RUNNING && it.state != com.dpidetector.app.model.TaskState.PENDING }
            val totalSections = state.results.sections.size
            builder
                .setContentTitle("DPI Детектор — идёт диагностика")
                .setContentText(
                    if (totalSections > 0) "Секции Webhost: $doneSections / $totalSections"
                    else "Проверка Whoami, CIDR, DNS и ТСПУ…",
                )
                .setProgress(totalSections, doneSections, totalSections == 0)
                .addAction(0, "Стоп", stopIntent)
        }

        return builder.build()
    }

    companion object {
        const val CHANNEL_ID = "dpi_scan_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_SYNC = "com.dpidetector.app.action.SYNC"
        const val ACTION_PAUSE_BATCH = "com.dpidetector.app.action.PAUSE_BATCH"
        const val ACTION_RESUME_BATCH = "com.dpidetector.app.action.RESUME_BATCH"
        const val ACTION_STOP_ALL = "com.dpidetector.app.action.STOP_ALL"

        fun sync(context: Context, state: EngineUiState) {
            runCatching {
                val active = state.running || state.batch.status == BatchJobStatus.RUNNING || state.batch.status == BatchJobStatus.PAUSED
                val intent = Intent(context, DpiForegroundService::class.java).setAction(ACTION_SYNC)
                if (active) {
                    ContextCompat.startForegroundService(context, intent)
                } else {
                    context.stopService(intent)
                }
            }
        }
    }
}
