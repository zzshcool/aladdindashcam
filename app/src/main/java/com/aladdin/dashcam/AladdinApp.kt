package com.aladdin.dashcam

import android.app.Application
import android.util.Log

class AladdinApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "AladdinDashCam application initialized")
    }

    companion object {
        private const val TAG = "AladdinApp"
    }
}
