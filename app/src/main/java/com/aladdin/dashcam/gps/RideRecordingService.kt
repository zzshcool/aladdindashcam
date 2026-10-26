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
    companion object {
        const val DESTINATION_LATITUDE = "destination_latitude"
        const val DESTINATION_LONGITUDE = "destination_longitude"
        const val DESTINATION_LABEL = "destination_label"
        const val ARRIVAL_RIDE_ID = "arrival_ride_id"
        const val ARRIVAL_NOTIFICATION = 54
        internal fun announceArrival(context: android.content.Context, rideId: String, label: String) {
            if (RideRepository.get(context).activeId.value != rideId) return
            val intent = Intent(context, RideHistoryActivity::class.java)
                .putExtra(ARRIVAL_RIDE_ID, rideId).putExtra(DESTINATION_LABEL, label)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            val options = ActivityOptions.makeBasic().setLaunchDisplayId(android.view.Display.DEFAULT_DISPLAY).toBundle()
            val pending = PendingIntent.getActivity(context, ARRIVAL_NOTIFICATION, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE, options)
            try {
                context.getSystemService(NotificationManager::class.java).notify(ARRIVAL_NOTIFICATION,
                    NotificationCompat.Builder(context, "ride").setSmallIcon(R.drawable.ic_tab_live)
                        .setContentTitle(com.aladdin.dashcam.ui.AppAppearance.localizedContext(context).getString(R.string.arrival_title)).setContentText(com.aladdin.dashcam.ui.AppAppearance.localizedContext(context).getString(R.string.arrival_notification))
                        .setContentIntent(pending).setAutoCancel(true).build())
            } catch (error: SecurityException) { android.util.Log.w("RideRecordingService", "Arrival notification unavailable", error) }
            try { context.startActivity(intent, options) }
            catch (error: RuntimeException) { android.util.Log.w("RideRecordingService", "Use notification to return after arrival", error) }
        }
    }
    private data class ArrivalSession(val rideId: String, val detector: RideArrivalDetector, val label: String)
    @Volatile private var arrival: ArrivalSession? = null
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
        val review = Intent(this, RideHistoryActivity::class.java).putExtra("finish_ride", true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        val stop = PendingIntent.getActivity(this, 1, review, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ActivityOptions.makeBasic().setLaunchDisplayId(android.view.Display.DEFAULT_DISPLAY).toBundle())
        startForeground(52, NotificationCompat.Builder(this, "ride").setSmallIcon(R.drawable.ic_tab_live)
            .setContentTitle(localized(R.string.ride_recording)).setContentText(localized(R.string.ride_notification))
            .setContentIntent(open).setOngoing(true).addAction(0, localized(R.string.ride_stop), stop).build())
        if (repository.activeId.value == null) {
            try {
                repository.start()
                gps.startListening()
                scope.launch {
                    try {
                        gps.gpsStatus.distinctUntilChangedBy { it.lastUpdateElapsedRealtime }.collect { status ->
                            repository.append(status)
                            val session = arrival
                            if (session != null && session.rideId == repository.activeId.value &&
                                session.detector.observe(status, android.os.SystemClock.elapsedRealtime()) && arrival === session) {
                                withContext(Dispatchers.Main) {
                                    try { announceArrival(this@RideRecordingService, session.rideId, session.label) }
                                    catch (error: RuntimeException) { android.util.Log.w("RideRecordingService", "Arrival prompt unavailable", error) }
                                }
                            }
                        }
                    }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { recordingFailed(error) }
                }
            } catch (error: Exception) { recordingFailed(error) }
        }
        val id = repository.activeId.value
        if (id != null && intent?.hasExtra(DESTINATION_LATITUDE) == true && intent.hasExtra(DESTINATION_LONGITUDE)) {
            val lat = intent.getDoubleExtra(DESTINATION_LATITUDE, Double.NaN)
            val lon = intent.getDoubleExtra(DESTINATION_LONGITUDE, Double.NaN)
            if (lat.isFinite() && lat in -90.0..90.0 && lon.isFinite() && lon in -180.0..180.0) {
                arrival = ArrivalSession(id, RideArrivalDetector(lat, lon), intent.getStringExtra(DESTINATION_LABEL).orEmpty())
            }
        } else if (intent?.getBooleanExtra("clear_arrival", false) == true) arrival = null
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        arrival = null
        getSystemService(NotificationManager::class.java).cancel(ARRIVAL_NOTIFICATION)
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
