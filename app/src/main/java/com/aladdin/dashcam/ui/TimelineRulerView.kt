package com.aladdin.dashcam.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.Scroller
import androidx.core.content.ContextCompat
import com.aladdin.dashcam.R
import com.aladdin.dashcam.model.MediaItem
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 自定義可拖曳 24 小時時間軸刻度尺 (Timeline Ruler View)
 * 支援錄影區間色彩標示 (綠色常規、紅色緊急)、無片空白提示、即時游標拖曳與播放進度同步。
 */
class TimelineRulerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // 每一分鐘佔用的像素寬度 (可微調縮放密度)
    private var pixelsPerMinute = 12f
    private val totalMinutesInDay = 24 * 60

    // 當天 00:00:00 的毫秒數
    private var startOfDayMillis: Long = 0L

    // 當前中央游標代表的毫秒時間戳
    var currentCursorMillis: Long = 0L
        private set

    private val mediaItems = mutableListOf<MediaItem>()

    // 繪圖物件
    private val rulerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val regularBlockPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val emergencyBlockPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val timeBubblePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val timeBubbleTextPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val scroller = Scroller(context)
    private var isUserInteracting = false
    private var isFlinging = false
    private var lastScrollerX = 0

    var onTimeSeekListener: ((targetMillis: Long, isFinished: Boolean) -> Unit)? = null

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val hourFormat = SimpleDateFormat("HH:00", Locale.getDefault())

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            scroller.forceFinished(true)
            isFlinging = false
            lastScrollerX = 0
            isUserInteracting = true
            return true
        }

        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float
        ): Boolean {
            val deltaMillis = (distanceX / pixelsPerMinute * 60_000).toLong()
            setCursorMillisInternal(currentCursorMillis + deltaMillis, notify = true, isFinished = false)
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val tappedMillis = currentCursorMillis + ((e.x - width / 2f) / pixelsPerMinute * 60_000).toLong()
            val nearest = mediaItems.minByOrNull { kotlin.math.abs(it.timestampMillis - tappedMillis) }
            val markerRadiusMillis = (24f * resources.displayMetrics.density / pixelsPerMinute * 60_000).toLong()
            val target = if (nearest != null && kotlin.math.abs(nearest.timestampMillis - tappedMillis) <= markerRadiusMillis) {
                nearest.timestampMillis
            } else tappedMillis
            setCursorMillisInternal(target, notify = true, isFinished = false)
            performClick()
            return true
        }

        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float
        ): Boolean {
            // 水平慣性滑動 (R11~R13: 嚴格使用逐幀差值計算)
            isFlinging = true
            lastScrollerX = 0
            scroller.fling(
                0, 0,
                (-velocityX).toInt(), 0,
                -50_000, 50_000,
                0, 0
            )
            invalidate()
            return true
        }
    })

    init {
        val dm = resources.displayMetrics
        pixelsPerMinute = 10f * dm.density

        // 刻度畫筆
        rulerPaint.color = ContextCompat.getColor(context, R.color.border_subtle)
        rulerPaint.strokeWidth = 1f * dm.density

        // 刻度文字畫筆
        textPaint.color = ContextCompat.getColor(context, R.color.text_secondary)
        textPaint.textSize = 11f * dm.scaledDensity
        textPaint.textAlign = Paint.Align.CENTER

        // 常規影片區間色塊 (綠色/藍綠色)
        regularBlockPaint.color = ContextCompat.getColor(context, R.color.accent_teal)
        regularBlockPaint.style = Paint.Style.FILL

        // 緊急影片區間色塊 (紅色)
        emergencyBlockPaint.color = ContextCompat.getColor(context, R.color.error_red)
        emergencyBlockPaint.style = Paint.Style.FILL

        // 中央游標畫筆
        cursorPaint.color = ContextCompat.getColor(context, R.color.record_active_red)
        cursorPaint.strokeWidth = 2.5f * dm.density

        // 游標頂部時間氣泡
        timeBubblePaint.color = ContextCompat.getColor(context, R.color.surface_elevated)
        timeBubblePaint.style = Paint.Style.FILL

        timeBubbleTextPaint.color = ContextCompat.getColor(context, R.color.text_primary)
        timeBubbleTextPaint.textSize = 12f * dm.scaledDensity
        timeBubbleTextPaint.textAlign = Paint.Align.CENTER
        timeBubbleTextPaint.isFakeBoldText = true

        initStartOfDay(System.currentTimeMillis())
    }

    private fun initStartOfDay(timestamp: Long) {
        val cal = Calendar.getInstance().apply {
            timeInMillis = timestamp
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        startOfDayMillis = cal.timeInMillis
        currentCursorMillis = startOfDayMillis
    }

    /**
     * 設定特定日期的媒體項目清單
     */
    fun setDayMedia(dayTimestamp: Long, items: List<MediaItem>) {
        val previousDay = startOfDayMillis
        val previousCursor = currentCursorMillis
        val hadMedia = mediaItems.isNotEmpty()
        initStartOfDay(dayTimestamp)
        scroller.forceFinished(true)
        isFlinging = false
        isUserInteracting = false
        mediaItems.clear()
        mediaItems.addAll(items)

        // 同一天更新保留游標；首次載入或換日定位到實際第一段影片。
        val endOfDayMillis = startOfDayMillis + 24 * 3600_000L
        currentCursorMillis = if (hadMedia && previousDay == startOfDayMillis && previousCursor in startOfDayMillis until endOfDayMillis) {
            previousCursor
        } else items.filter { it.timestampMillis in startOfDayMillis until endOfDayMillis }
            .minOfOrNull { it.timestampMillis } ?: startOfDayMillis
        invalidate()
    }

    /**
     * 更新當前播放進度 (若使用者正在手動拖曳則不覆蓋)
     */
    fun setPlaybackProgress(timeMillis: Long) {
        if (!isUserInteracting) {
            currentCursorMillis = timeMillis
            invalidate()
        }
    }

    fun seekToTime(timeMillis: Long) {
        setCursorMillisInternal(timeMillis, notify = true, isFinished = true)
    }

    private fun setCursorMillisInternal(targetMillis: Long, notify: Boolean, isFinished: Boolean) {
        val minTime = startOfDayMillis
        val maxTime = startOfDayMillis + 24 * 3600_000L - 1000L
        currentCursorMillis = targetMillis.coerceIn(minTime, maxTime)
        invalidate()

        if (notify) {
            onTimeSeekListener?.invoke(currentCursorMillis, isFinished)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(true)
        val result = gestureDetector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            parent?.requestDisallowInterceptTouchEvent(false)
            if (isUserInteracting && event.actionMasked == MotionEvent.ACTION_UP && !isFlinging) {
                isUserInteracting = false
                onTimeSeekListener?.invoke(currentCursorMillis, true)
            }
            if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
                scroller.forceFinished(true)
                isFlinging = false
                isUserInteracting = false
            }
        }
        return result || super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            val currX = scroller.currX
            val diffX = currX - lastScrollerX
            lastScrollerX = currX
            val deltaMillis = (diffX / pixelsPerMinute * 60_000).toLong()
            setCursorMillisInternal(currentCursorMillis + deltaMillis, notify = true, isFinished = false)
            postInvalidateOnAnimation()
        } else if (isFlinging) {
            isFlinging = false
            isUserInteracting = false
            lastScrollerX = 0
            onTimeSeekListener?.invoke(currentCursorMillis, true)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val centerX = w / 2f
        val rulerTop = 32f * resources.displayMetrics.density
        val rulerBottom = h - (12f * resources.displayMetrics.density)
        val rulerHeight = rulerBottom - rulerTop

        // 計算可見時間窗口 (毫秒)
        val cursorMinuteOffset = (currentCursorMillis - startOfDayMillis).toFloat() / 60_000f

        // 1. 繪製影片區間色塊或未確認時長事件標記 (N06)
        for (item in mediaItems) {
            if (item.timestampMillis <= 0L) continue
            val startMin = (item.timestampMillis - startOfDayMillis).toFloat() / 60_000f

            if (item.durationSeconds > 0) {
                val durationMin = item.durationSeconds / 60f
                val endMin = startMin + durationMin
                val blockLeft = centerX + (startMin - cursorMinuteOffset) * pixelsPerMinute
                val blockRight = centerX + (endMin - cursorMinuteOffset) * pixelsPerMinute

                if (blockRight >= 0 && blockLeft <= w) {
                    val blockPaint = if (item.isEmergency) emergencyBlockPaint else regularBlockPaint
                    val rect = RectF(blockLeft, rulerTop + 4f, blockRight.coerceAtLeast(blockLeft + 4f), rulerBottom - 4f)
                    canvas.drawRoundRect(rect, 4f, 4f, blockPaint)
                }
            } else {
                // 未知片長時呈現專用事件標記線與頂部圓點，不代表連續區間 (N06)
                val markerX = centerX + (startMin - cursorMinuteOffset) * pixelsPerMinute
                if (markerX in -10f..(w + 10f)) {
                    val markerPaint = if (item.isEmergency) emergencyBlockPaint else regularBlockPaint
                    canvas.drawLine(markerX, rulerTop + 2f, markerX, rulerBottom - 2f, markerPaint)
                    canvas.drawCircle(markerX, rulerTop + 6f, 4f, markerPaint)
                }
            }
        }

        // 2. 繪製時間軸刻度與標籤
        val startVisibleMinute = ((cursorMinuteOffset - centerX / pixelsPerMinute) - 5).toInt().coerceAtLeast(0)
        val endVisibleMinute = ((cursorMinuteOffset + (w - centerX) / pixelsPerMinute) + 5).toInt().coerceAtMost(totalMinutesInDay)

        for (m in startVisibleMinute..endVisibleMinute) {
            val x = centerX + (m - cursorMinuteOffset) * pixelsPerMinute

            if (m % 60 == 0) {
                // 每整點大刻度 (例如 14:00)
                canvas.drawLine(x, rulerTop, x, rulerBottom, rulerPaint)
                val hourStr = String.format(Locale.getDefault(), "%02d:00", m / 60)
                val bubbleHalfWidth = timeBubbleTextPaint.measureText(timeFormat.format(Date(currentCursorMillis))) / 2f + 12f
                if (kotlin.math.abs(x - centerX) > bubbleHalfWidth + textPaint.measureText(hourStr) / 2f) {
                    canvas.drawText(hourStr, x, rulerTop - 8f, textPaint)
                }
            } else if (m % 10 == 0) {
                // 每 10 分鐘中刻度
                canvas.drawLine(x, rulerBottom - rulerHeight * 0.4f, x, rulerBottom, rulerPaint)
            } else if (m % 5 == 0) {
                // 每 5 分鐘小刻度
                canvas.drawLine(x, rulerBottom - rulerHeight * 0.2f, x, rulerBottom, rulerPaint)
            }
        }

        // 3. 繪製中心游標指針 (紅色游標線)
        canvas.drawLine(centerX, rulerTop - 12f, centerX, rulerBottom + 6f, cursorPaint)

        // 4. 繪製中央游標頂部即時時間氣泡
        val timeStr = timeFormat.format(Date(currentCursorMillis))
        val textWidth = timeBubbleTextPaint.measureText(timeStr)
        val bubblePadding = 12f
        val bubbleRect = RectF(
            centerX - textWidth / 2f - bubblePadding,
            2f,
            centerX + textWidth / 2f + bubblePadding,
            rulerTop - 10f
        )
        canvas.drawRoundRect(bubbleRect, 8f, 8f, timeBubblePaint)
        canvas.drawText(timeStr, centerX, rulerTop - 16f, timeBubbleTextPaint)
    }

    /**
     * 尋找當前游標時間命中的 MediaItem (N06: 區間採用半開區間，未知片長不虛構 60 秒可播放覆蓋)
     */
    fun findItemAtCursor(): MediaItem? {
        val cursor = currentCursorMillis
        for (item in mediaItems) {
            val start = item.timestampMillis
            if (item.durationSeconds > 0) {
                val end = start + (item.durationSeconds * 1000L)
                if (cursor in start until end) {
                    return item
                }
            } else {
                // 未知片長僅以起點事件標記容差 1500ms 命中，不可視為長區間
                if (Math.abs(cursor - start) <= 1500L) {
                    return item
                }
            }
        }
        return null
    }

    /**
     * 尋找當前游標之後的第一個可用片段 (跳至下一段)
     */
    fun findNextItem(): MediaItem? {
        val cursor = currentCursorMillis
        return mediaItems
            .filter { it.timestampMillis > cursor }
            .minByOrNull { it.timestampMillis }
    }
}
