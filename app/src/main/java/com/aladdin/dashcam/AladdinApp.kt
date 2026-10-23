package com.aladdin.dashcam

import android.app.Application
import android.util.Log

class AladdinApp : Application() {
    override fun onCreate() {
        super.onCreate()
        com.aladdin.dashcam.ui.AppAppearance.apply(this)
        Log.i(TAG, "AladdinDashCam application initialized")
    }

    companion object {
        private const val TAG = "AladdinApp"
    }
}
