package com.aladdin.dashcam.media

import android.content.Context
import android.graphics.*
import com.aladdin.dashcam.gps.Ride
import kotlinx.coroutines.*
import java.io.File

class VideoExporter {
    companion object { init { System.loadLibrary("ffmpeg"); System.loadLibrary("dashcam_export") } }
    private external fun nativeExport(source: String, destination: String, startMs: Long, endMs: Long,
                                     overlay: Boolean, limit720p: Boolean, callback: Callback): String?

    class Callback(private val ride: Ride?, private val videoTime: Long, private val job: Job,
                   private val progress: (Long) -> Unit) {
        private var bitmap: Bitmap? = null
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val routePath = Path()
        private var routeBitmap: Bitmap? = null
        private val behind = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OVER) }
        private val overlayTypeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        private var minLat = ride?.points?.minOfOrNull { it.lat } ?: 0.0
        private var minLon = ride?.points?.minOfOrNull { it.lon } ?: 0.0
        private var spanLat = ((ride?.points?.maxOfOrNull { it.lat } ?: 0.0) - minLat).coerceAtLeast(0.0001)
        private var spanLon = ((ride?.points?.maxOfOrNull { it.lon } ?: 0.0) - minLon).coerceAtLeast(0.0001)
        fun isCancelled(): Boolean = !job.isActive
        fun onProgress(position: Long) = progress(position)
        /** The route is a GPS schematic, not a Google basemap or a synthetic location. */
        fun renderOverlay(position: Long, width: Int, height: Int): Bitmap? {
            val trip = ride ?: return null
            val w = minOf(width, 440); val h = minOf(height, 180)
            val image = bitmap ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bitmap = it }
            image.eraseColor(Color.TRANSPARENT)
            val canvas = Canvas(image)
            paint.style = Paint.Style.FILL; paint.color = Color.argb(210, 12, 18, 22)

            val point = trip.pointAt(videoTime + position)
            paint.color = Color.WHITE; paint.typeface = overlayTypeface
            paint.textSize = h * 0.24f
            canvas.drawText("${point?.speed?.toString() ?: "--"} km/h", 20f, h * 0.42f, paint)
            paint.textSize = h * 0.10f; paint.color = Color.LTGRAY
            canvas.drawText("GPS  ${point?.accuracy?.toInt()?.let { "±${it}m" } ?: "--"}", 20f, h * 0.64f, paint)
            val left = w * 0.58f; val top = 24f
            val routeWidth = w * 0.35f; val routeHeight = h - 48f
            fun x(lon: Double) = left + ((lon - minLon) / spanLon * routeWidth).toFloat()
            fun y(lat: Double) = top + routeHeight - ((lat - minLat) / spanLat * routeHeight).toFloat()
            if (routeBitmap?.width != w || routeBitmap?.height != h) {
                routeBitmap?.recycle()
                routeBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val fixed = Canvas(routeBitmap!!)
                paint.style = Paint.Style.FILL; paint.color = Color.argb(210, 12, 18, 22)
                fixed.drawRoundRect(8f, 8f, w - 8f, h - 8f, 14f, 14f, paint)
                routePath.reset()
                trip.points.forEachIndexed { index, p ->
                    if (index == 0 || p.elapsed - trip.points[index - 1].elapsed > Ride.MAX_GAP_MS) routePath.moveTo(x(p.lon), y(p.lat))
                    else routePath.lineTo(x(p.lon), y(p.lat))
                }
                paint.style = Paint.Style.STROKE; paint.strokeWidth = 3f; paint.color = Color.CYAN
                fixed.drawPath(routePath, paint)
            }
            // Static background/route is composited beneath the dynamic text.
            val background = routeBitmap!!
            val staticCanvas = Canvas(image)
            paint.style = Paint.Style.FILL
            staticCanvas.drawBitmap(background, 0f, 0f, behind)
            if (point != null) { paint.style = Paint.Style.FILL; paint.color = Color.WHITE; canvas.drawCircle(x(point.lon), y(point.lat), 5f, paint) }
            return image
        }
        fun close() { bitmap?.recycle(); bitmap = null; routeBitmap?.recycle(); routeBitmap = null }
    }

    suspend fun export(context: Context, source: File, destination: File, startMs: Long, endMs: Long,
                       ride: Ride?, videoTime: Long, limit720p: Boolean = false, progress: (Long) -> Unit): File = withContext(Dispatchers.IO) {
        require(source.isFile && source.length() > 0 && startMs >= 0 && endMs > startMs)
        val partial = File(destination.parentFile, destination.name + ".partial")
        val callback = Callback(ride, videoTime, currentCoroutineContext().job, progress)
        try {
            val error = nativeExport(source.absolutePath, partial.absolutePath, startMs, endMs, ride != null, limit720p, callback)
            currentCoroutineContext().ensureActive()
            check(error.isNullOrEmpty()) { error.orEmpty() }
            check(partial.length() > 0 && partial.renameTo(destination)) { "Cannot finalize video" }
            destination
        } finally { callback.close(); partial.delete() }
    }
}
