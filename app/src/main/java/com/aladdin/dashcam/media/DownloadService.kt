package com.aladdin.dashcam.media

import android.app.*
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.aladdin.dashcam.AladdinApp
import com.aladdin.dashcam.R
import com.aladdin.dashcam.ui.MainActivity
import kotlinx.coroutines.*

class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("downloads", getString(R.string.download_background), NotificationManager.IMPORTANCE_LOW))
        fun notification(count: Int): Notification {
            val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val stop = PendingIntent.getService(this, 1, Intent(this, DownloadService::class.java).setAction("pause"), PendingIntent.FLAG_IMMUTABLE)
            return NotificationCompat.Builder(this, "downloads").setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(getString(R.string.download_background)).setContentText(if (count > 0) getString(R.string.download_waiting, count) else getString(R.string.download_finishing))
                .setContentIntent(open).setOngoing(true).addAction(0, getString(R.string.download_pause_all), stop).build()
        }
        startForeground(42, notification(0))
        scope.launch {
            val recorder = (application as AladdinApp).recorder
            kotlinx.coroutines.flow.combine(recorder.downloadTasks, recorder.downloadWorkCount) { tasks, work -> tasks to work }.collect { (tasks, work) ->
                val count = tasks.count { it.status == DownloadStatus.PENDING || it.status == DownloadStatus.DOWNLOADING }
                if (count == 0 && work == 0) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
                else manager.notify(42, notification(count))
            }
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "pause") (application as AladdinApp).recorder.pauseDownloads()
        return START_NOT_STICKY
    }
    override fun onTimeout(startId: Int, fgsType: Int) {
        (application as AladdinApp).recorder.pauseDownloads()
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }
    override fun onDestroy() { scope.cancel(); (application as AladdinApp).stopIdleRecorder(); super.onDestroy() }
}
