package com.aladdin.dashcam.rain

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.aladdin.dashcam.R
import com.aladdin.dashcam.ui.AppAppearance
import com.aladdin.dashcam.ui.RainModeActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class RainGuardService : Service() {
    enum class State { OFF, ARMING, LOCKED }
    companion object {
        const val PREFERENCES = "rain_mode"
        const val NAVIGATION_ENABLED = "navigation_enabled"
        const val ACTION_STOP = "com.aladdin.dashcam.rain.STOP"
        private const val NOTIFICATION = 56
        private const val CHANNEL = "rain_guard"
        private val mutableState = MutableStateFlow(State.OFF)
        val state = mutableState.asStateFlow()
        fun notificationsReady(context: Context) = NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            context.getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
        fun ready(context: Context) = Settings.canDrawOverlays(context) && notificationsReady(context)
        fun arm(context: Context, delayMs: Long = 3000L) {
            check(ready(context))
            ContextCompat.startForegroundService(context, Intent(context, RainGuardService::class.java).putExtra("delay", delayMs))
        }
    }
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var manager: WindowManager
    private lateinit var hold: RainUnlockGesture
    private var shield: View? = null
    private var badge: TextView? = null
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { stopSelf() }
    }
    private fun localized(id: Int) = AppAppearance.localizedContext(this).getString(id)
    private val unlockTick = object : Runnable {
        override fun run() {
            if (shield == null) return
            if (hold.ready(SystemClock.uptimeMillis())) {
                android.widget.Toast.makeText(this@RainGuardService, localized(R.string.rain_unlocked), android.widget.Toast.LENGTH_SHORT).show()
                stopSelf()
            } else {
                badge?.text = AppAppearance.localizedContext(this@RainGuardService).getString(R.string.rain_unlock_progress,
                    (hold.progress(SystemClock.uptimeMillis()) * 100).toInt())
                handler.postDelayed(this, 50L)
            }
        }
    }
    private val show = Runnable {
        try { showShield() }
        catch (error: Exception) {
            android.util.Log.e("RainGuard", "Cannot create touch guard", error)
            android.widget.Toast.makeText(this, localized(R.string.rain_start_failed), android.widget.Toast.LENGTH_LONG).show()
            stopSelf()
        }
    }
    private val permissionCheck = object : Runnable {
        override fun run() {
            if (!ready(this@RainGuardService)) { stopSelf(); return }
            handler.postDelayed(this, 1000L)
        }
    }
    override fun onCreate() {
        super.onCreate()
        manager = getSystemService(WindowManager::class.java)
        hold = RainUnlockGesture(ViewConfiguration.get(this).scaledTouchSlop.toFloat())
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, localized(R.string.rain_title), NotificationManager.IMPORTANCE_LOW))
        ContextCompat.registerReceiver(this, screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
    }
    private fun notification(): Notification {
        val stop = PendingIntent.getService(this, 0, Intent(this, javaClass).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 1, Intent(this, RainModeActivity::class.java).putExtra("stop", true), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_live_rain)
            .setContentTitle(localized(if (mutableState.value == State.LOCKED) R.string.rain_locked else R.string.rain_arming))
            .setContentText(localized(R.string.rain_notification)).setOngoing(true).setOnlyAlertOnce(true)
            .setContentIntent(open).addAction(0, localized(R.string.rain_stop), stop).build()
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (!ready(this)) { stopSelf(); return START_NOT_STICKY }
        if (shield != null) return START_NOT_STICKY
        mutableState.value = State.ARMING
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIFICATION, notification())
        handler.removeCallbacks(show)
        handler.postDelayed(show, (intent?.getLongExtra("delay", 3000L) ?: 3000L).coerceIn(0L, 3000L))
        handler.removeCallbacks(permissionCheck); handler.post(permissionCheck)
        return START_NOT_STICKY
    }
    private fun showShield() {
        if (!ready(this)) { stopSelf(); return }
        val context = AppAppearance.localizedContext(this)
        fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
        val root = object : FrameLayout(context) {
            private var backBlocker: AutoCloseable? = null
            override fun onAttachedToWindow() {
                super.onAttachedToWindow()
                if (Build.VERSION.SDK_INT >= 33) backBlocker = Api33BackBlocker(this)
            }
            override fun onDetachedFromWindow() { backBlocker?.close(); super.onDetachedFromWindow() }
        }.apply {
            keepScreenOn = true
            isFocusableInTouchMode = true
            setOnTouchListener { _, _ -> true }
            setOnKeyListener { _, key, _ -> key == KeyEvent.KEYCODE_BACK }
        }
        val unlock = TextView(context).apply {
            text = localized(R.string.rain_hold_unlock)
            contentDescription = text
            textSize = 11f; gravity = Gravity.CENTER
            setTextColor(android.graphics.Color.WHITE)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xdd17232b.toInt()); cornerRadius = dp(18).toFloat()
            }
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        hold.begin(event.eventTime, event.rawX, event.rawY, event.pointerCount)
                        handler.removeCallbacks(unlockTick); handler.post(unlockTick)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        hold.move(event.rawX, event.rawY, event.pointerCount)
                        if (event.x !in 0f..view.width.toFloat() || event.y !in 0f..view.height.toFloat()) hold.cancel()
                        if (!hold.tracking) cancelHold()
                    }
                    MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> cancelHold()
                }
                true
            }
        }
        root.addView(unlock, FrameLayout.LayoutParams(dp(128), dp(48), Gravity.BOTTOM or Gravity.START).apply {
            marginStart = dp(12); bottomMargin = dp(12)
        })
        badge = unlock
        val params = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT).apply {
            title = "AladdinRainGuard"
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
        }
        shield = root
        manager.addView(root, params)
        root.requestFocus()
        mutableState.value = State.LOCKED
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification())
    }
    private fun cancelHold() {
        hold.cancel(); handler.removeCallbacks(unlockTick)
        badge?.text = localized(R.string.rain_hold_unlock)
    }
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig); cancelHold()
    }
    override fun onTaskRemoved(rootIntent: Intent?) { stopSelf() }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null); hold.cancel()
        shield?.let { try { manager.removeViewImmediate(it) } catch (_: IllegalArgumentException) {} }
        shield = null; badge = null; mutableState.value = State.OFF
        unregisterReceiver(screenOff)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    @androidx.annotation.RequiresApi(33)
    private class Api33BackBlocker(view: View) : AutoCloseable {
        private val dispatcher = view.findOnBackInvokedDispatcher()
        private val callback = android.window.OnBackInvokedCallback { }
        init { dispatcher?.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback) }
        override fun close() { dispatcher?.unregisterOnBackInvokedCallback(callback) }
    }
}
