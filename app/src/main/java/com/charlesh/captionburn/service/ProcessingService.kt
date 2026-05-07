package com.charlesh.captionburn.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.charlesh.captionburn.MainActivity
import com.charlesh.captionburn.R
import com.charlesh.captionburn.domain.usecase.RunPipelineUseCase
import com.charlesh.captionburn.service.workers.PipelineWorkData
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Foreground host for long-running pipeline work (extract → transcribe → translate → burn).
 */
class ProcessingService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var workManager: WorkManager
    private var observeJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
        workManager = WorkManager.getInstance(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                val projectId = intent.getStringExtra(EXTRA_PROJECT_ID)
                val workName = intent.getStringExtra(EXTRA_WORK_NAME)
                if (!projectId.isNullOrBlank()) {
                    workManager.cancelUniqueWork(workName ?: RunPipelineUseCase.uniqueWorkName(projectId))
                }
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START, null -> {
                val projectId = intent?.getStringExtra(EXTRA_PROJECT_ID)
                if (projectId.isNullOrBlank()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                val workName = intent.getStringExtra(EXTRA_WORK_NAME)
                    ?: RunPipelineUseCase.uniqueWorkName(projectId)
                val notification = buildNotification(
                    content = getString(R.string.notif_processing_title),
                    projectId = projectId,
                    workName = workName,
                )
                val fgTypes =
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
                ServiceCompat.startForeground(this, NOTIF_ID, notification, fgTypes)
                observePipeline(projectId, workName)
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun observePipeline(projectId: String, workName: String) {
        observeJob?.cancel()
        observeJob = serviceScope.launch {
            workManager.getWorkInfosForUniqueWorkFlow(workName).collect { infos ->
                if (infos.isEmpty()) {
                    stopSelf()
                    return@collect
                }

                val running = infos.firstOrNull { it.state == WorkInfo.State.RUNNING }
                val notifText = when {
                    running != null -> {
                        val stage = running.progress.getString(PipelineWorkData.KEY_STAGE) ?: "processing"
                        val percent = (running.progress.getFloat(PipelineWorkData.KEY_PROGRESS, 0f) * 100).toInt()
                        "$stage ($percent%)"
                    }
                    infos.any { it.state == WorkInfo.State.FAILED } -> "Processing failed"
                    infos.any { it.state == WorkInfo.State.CANCELLED } -> "Processing cancelled"
                    infos.all { it.state == WorkInfo.State.SUCCEEDED } -> "Processing complete"
                    else -> getString(R.string.notif_processing_title)
                }
                notifySafely(buildNotification(content = notifText, projectId = projectId, workName = workName))

                if (infos.all { it.state.isFinished }) {
                    stopSelf()
                }
            }
        }
    }

    private fun buildNotification(
        content: CharSequence,
        projectId: String,
        workName: String = RunPipelineUseCase.uniqueWorkName(projectId),
    ): Notification {
        val tap = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val cancelWithWork = PendingIntent.getService(
            this,
            workName.hashCode(),
            Intent(this, ProcessingService::class.java)
                .setAction(ACTION_CANCEL)
                .putExtra(EXTRA_PROJECT_ID, projectId)
                .putExtra(EXTRA_WORK_NAME, workName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(content)
            .setContentIntent(tap)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, getString(R.string.notif_processing_cancel), cancelWithWork)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun notifySafely(notification: Notification) {
        val hasPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission) return
        runCatching {
            NotificationManagerCompat.from(this).notify(NOTIF_ID, notification)
        }
    }

    companion object {
        const val CHANNEL_ID = "captionburn.processing"
        const val NOTIF_ID = 1001
        const val ACTION_START = "com.charlesh.captionburn.service.action.START"
        const val ACTION_CANCEL = "com.charlesh.captionburn.service.action.CANCEL"
        const val EXTRA_PROJECT_ID = "project_id"
        const val EXTRA_WORK_NAME = "work_name"

        fun ensureChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val mgr = ctx.getSystemService(NotificationManager::class.java)
                if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                    val ch = NotificationChannel(
                        CHANNEL_ID,
                        ctx.getString(R.string.notif_channel_processing),
                        NotificationManager.IMPORTANCE_LOW,
                    )
                    mgr.createNotificationChannel(ch)
                }
            }
        }
    }
}
