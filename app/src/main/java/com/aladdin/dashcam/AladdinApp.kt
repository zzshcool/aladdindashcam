package com.aladdin.dashcam

import android.app.Application
import android.util.Log

class AladdinApp : Application() {
    private val recorderStore = androidx.lifecycle.ViewModelStore()
    private val recorderDelegate = lazy {
        androidx.lifecycle.ViewModelProvider(recorderStore, androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory(this))[com.aladdin.dashcam.ui.DashCamViewModel::class.java]
    }
    val recorder: com.aladdin.dashcam.ui.DashCamViewModel by recorderDelegate
    private var visibleActivities = 0
    fun stopIdleRecorder() {
        if (visibleActivities == 0 && recorderDelegate.isInitialized() && recorder.downloadWorkCount.value == 0 && recorder.downloadTasks.value.none {
            it.status == com.aladdin.dashcam.media.DownloadStatus.PENDING || it.status == com.aladdin.dashcam.media.DownloadStatus.DOWNLOADING
        }) { recorder.client.stop() }
    }

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: android.app.Activity) {
                visibleActivities++
                if (recorderDelegate.isInitialized()) recorder.client.start()
            }
            override fun onActivityStopped(activity: android.app.Activity) { visibleActivities--; stopIdleRecorder() }
            override fun onActivityCreated(activity: android.app.Activity, state: android.os.Bundle?) {}
            override fun onActivityResumed(activity: android.app.Activity) {}
            override fun onActivityPaused(activity: android.app.Activity) {}
            override fun onActivitySaveInstanceState(activity: android.app.Activity, state: android.os.Bundle) {}
            override fun onActivityDestroyed(activity: android.app.Activity) {}
        })
        com.aladdin.dashcam.ui.AppAppearance.apply(this)
        Log.i(TAG, "AladdinDashCam application initialized")
    }

    companion object {
        private const val TAG = "AladdinApp"
    }
}
