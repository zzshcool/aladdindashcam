package com.aladdin.dashcam.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.aladdin.dashcam.R

/**
 * 專用圓弧時速儀表 (Speedometer Gauge View)
 * 刻度 0 ~ 160 km/h，中心大字顯示目前速度與 km/h，動態漸層圓弧。
 */
class SpeedGaugeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val maxSpeed = 160f
    private var currentSpeed: Float? = null

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val speedTextPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val unitTextPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tickTextPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val arcRect = RectF()

    init {
        val dm = resources.displayMetrics

        // 背景圓弧
        arcPaint.color = ContextCompat.getColor(context, R.color.surface_elevated)
        arcPaint.style = Paint.Style.STROKE
        arcPaint.strokeWidth = 14f * dm.density
        arcPaint.strokeCap = Paint.Cap.ROUND

        // 速度進度彩弧
        progressPaint.color = ContextCompat.getColor(context, R.color.accent_teal)
        progressPaint.style = Paint.Style.STROKE
        progressPaint.strokeWidth = 14f * dm.density
        progressPaint.strokeCap = Paint.Cap.ROUND

        // 刻度畫筆
        tickPaint.color = ContextCompat.getColor(context, R.color.border_subtle)
        tickPaint.strokeWidth = 2f * dm.density

        tickTextPaint.color = ContextCompat.getColor(context, R.color.text_secondary)
        tickTextPaint.textSize = 10f * dm.scaledDensity
        tickTextPaint.textAlign = Paint.Align.CENTER

        // 中央速度文字
        speedTextPaint.color = ContextCompat.getColor(context, R.color.text_primary)
        speedTextPaint.textSize = 44f * dm.scaledDensity
        speedTextPaint.textAlign = Paint.Align.CENTER
        speedTextPaint.isFakeBoldText = true

        // 速度單位文字
        unitTextPaint.color = ContextCompat.getColor(context, R.color.accent_teal)
        unitTextPaint.textSize = 13f * dm.scaledDensity
        unitTextPaint.textAlign = Paint.Align.CENTER
    }

    fun setSpeed(speedKmh: Int?) {
        if (currentSpeed == speedKmh?.toFloat()) return
        currentSpeed = speedKmh?.toFloat()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val padding = 24f * resources.displayMetrics.density
        val size = minOf(w, h) - padding * 2
        val left = (w - size) / 2f
        val top = (h - size) / 2f
        arcRect.set(left, top, left + size, top + size)

        // 儀表圓弧角度：從 140° 到 400° (掃過 260°)
        val startAngle = 140f
        val sweepAngle = 260f

        // 1. 繪製底層刻度弧
        canvas.drawArc(arcRect, startAngle, sweepAngle, false, arcPaint)

        // 2. 繪製當前時速進度弧
        val speedVal = currentSpeed
        if (speedVal != null && speedVal > 0f) {
            val progressSweep = (speedVal.coerceIn(0f, maxSpeed) / maxSpeed) * sweepAngle
            canvas.drawArc(arcRect, startAngle, progressSweep, false, progressPaint)
        }

        // 3. 繪製中心速度數值
        val centerX = w / 2f
        val centerY = h / 2f
        val speedStr = if (speedVal != null) String.format("%.0f", speedVal) else "--"
        canvas.drawText(speedStr, centerX, centerY + 8f * resources.displayMetrics.density, speedTextPaint)

        // 4. 繪製單位 km/h
        canvas.drawText("km/h", centerX, centerY + 30f * resources.displayMetrics.density, unitTextPaint)
    }
}
