package com.aladdin.dashcam.gps

import android.app.*
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.aladdin.dashcam.R
import com.aladdin.dashcam.ui.RideHistoryActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChangedBy

class RideRecordingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var gps: GpsSpeedometer
    private lateinit var repository: RideRepository
    override fun onCreate() {
        super.onCreate()
        repository = RideRepository.get(this)
        gps = GpsSpeedometer(this, scope)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("ride", localized(R.string.ride_title), NotificationManager.IMPORTANCE_LOW))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { stopSelf(); return START_NOT_STICKY }
        val open = PendingIntent.getActivity(this, 0, Intent(this, RideHistoryActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, javaClass).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        startForeground(52, NotificationCompat.Builder(this, "ride").setSmallIcon(R.drawable.ic_tab_live)
            .setContentTitle(localized(R.string.ride_recording)).setContentText(localized(R.string.ride_notification))
            .setContentIntent(open).setOngoing(true).addAction(0, localized(R.string.ride_stop), stop).build())
        if (repository.activeId.value == null) {
            try {
                repository.start()
                gps.startListening()
                scope.launch {
                    try { gps.gpsStatus.distinctUntilChangedBy { it.lastUpdateElapsedRealtime }.collect { repository.append(it) } }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { recordingFailed(error) }
                }
            } catch (error: Exception) { recordingFailed(error) }
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        gps.stopListening(); scope.cancel()
        try { repository.stop() } catch (error: Exception) { recordingFailed(error) }
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    private fun localized(id: Int) = com.aladdin.dashcam.ui.AppAppearance.localizedContext(this).getString(id)
    private fun recordingFailed(error: Exception) {
        android.util.Log.e("RideRecordingService", "Cannot persist ride", error)
        getSystemService(NotificationManager::class.java).notify(53, NotificationCompat.Builder(this, "ride")
            .setSmallIcon(R.drawable.ic_tab_live).setContentTitle(localized(R.string.ride_title))
            .setContentText(localized(R.string.ride_storage_failed)).build())
        stopSelf()
    }
}
