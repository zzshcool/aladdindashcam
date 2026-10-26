package com.aladdin.dashcam.rain

/** A cancelled or multi-pointer hold cannot resume until a new single-pointer down. */
class RainUnlockGesture(private val movementLimit: Float, val durationMs: Long = 3000L) {
    private var started: Long? = null
    private var x = 0f
    private var y = 0f
    val tracking: Boolean get() = started != null
    fun begin(time: Long, x: Float, y: Float, pointers: Int) {
        cancel()
        if (pointers == 1) { started = time; this.x = x; this.y = y }
    }
    fun move(x: Float, y: Float, pointers: Int) {
        if (pointers != 1 || kotlin.math.hypot(x - this.x, y - this.y) > movementLimit) cancel()
    }
    fun progress(time: Long): Float = started?.let { ((time - it).toFloat() / durationMs).coerceIn(0f, 1f) } ?: 0f
    fun ready(time: Long) = started != null && progress(time) >= 1f
    fun cancel() { started = null }
}
