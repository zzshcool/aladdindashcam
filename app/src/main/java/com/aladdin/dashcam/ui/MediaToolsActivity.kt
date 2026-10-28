package com.aladdin.dashcam.ui

import android.graphics.Color
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.aladdin.dashcam.R
import com.aladdin.dashcam.gps.Ride
import com.aladdin.dashcam.gps.RideRepository
import com.aladdin.dashcam.media.*
import com.google.android.material.slider.RangeSlider
import kotlinx.coroutines.*
import java.io.File
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.*

class MediaToolsActivity : AppCompatActivity() {
    private var player: NativeFfmpegPlayer? = null
    private var exportJob: Job? = null
    private var positionMs = 0L
    private var durationMs = 0L
    private var pauseAt: Pair<Long, CompletableDeferred<Unit>>? = null
    private lateinit var source: File
    private lateinit var key: String
    private lateinit var rows: LinearLayout
    private lateinit var range: RangeSlider
    private lateinit var rangeLabel: TextView
    private lateinit var progress: TextView
    private lateinit var export: Button
    private lateinit var rideSpinner: Spinner
    private lateinit var offset: EditText
    private lateinit var limit720p: CheckBox
    private var rides = emptyList<com.aladdin.dashcam.gps.RideSummary>()
    private var selectedRide: Ride? = null
    private var rideLoadJob: Job? = null
    private var routeMap: com.google.android.gms.maps.GoogleMap? = null
    private var routeMarker: com.google.android.gms.maps.model.Marker? = null
    private lateinit var mapContainer: FrameLayout
    private lateinit var syncStatus: TextView
    private lateinit var positionSeek: SeekBar
    private var draggingPosition = false

    private val bookmarks by lazy { VideoBookmarks(this) }
    private val videoTimestamp get() = intent.getLongExtra("time", 0L)
    private val reportDraft by lazy { ReportDraftEditor(this, source) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        source = File(intent.getStringExtra("path") ?: "")
        if (!source.isFile || !source.canonicalPath.startsWith(MediaFiles.moviesDir(this).canonicalPath + File.separator)) { finish(); return }
        key = intent.getStringExtra("key") ?: source.name
        positionMs = savedInstanceState?.getLong("position") ?: intent.getLongExtra("position", 0L)
        rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 16, 24, 24) }
        setContentView(ScrollView(this).apply { addView(rows) })
        AppAppearance.applyContentInsets(rows, 16, 12, 16, 16)
        rows.addView(button("‹ ${getString(R.string.video_tools)}") { finish() })
        rows.addView(label(source.name))
        val gl = GLSurfaceView(this)
        val videoContainer = FrameLayout(this)
        videoContainer.addView(gl, FrameLayout.LayoutParams(-1, -1))
        rows.addView(videoContainer, LinearLayout.LayoutParams(-1, (220 * resources.displayMetrics.density).toInt()))
        val transport = LinearLayout(this)
        transport.addView(button(getString(R.string.video_play_pause)) {
            player?.let { native ->
                if (!native.hasSource) lifecycleScope.launch { native.start(source.absolutePath, streaming = false, startPositionMs = (range.values[0] * 1000).toLong()) }
                else native.setPaused(native.isPlaying)
            }
        })
        transport.addView(button(getString(R.string.bookmark_add)) { bookmarks.add(key, positionMs); showBookmarks() })
        rows.addView(transport)
        rows.addView(button("擷取目前畫面為照片") { capturePhoto() })
        val fineSeek = LinearLayout(this)
        listOf(-100L, 100L).forEach { delta ->
            fineSeek.addView(button(if (delta < 0) "前移 0.1 秒" else "後移 0.1 秒") {
                if (durationMs > 0 && exportJob?.isActive != true) lifecycleScope.launch {
                    seekPreview((positionMs + delta).coerceIn(0L, (durationMs - 1).coerceAtLeast(0)), pauseAfter = true)
                }
            })
        }
        rows.addView(fineSeek)
        rangeLabel = label(getString(R.string.video_loading)); rows.addView(rangeLabel)
        range = RangeSlider(this).apply { valueFrom = 0f; valueTo = 1f; values = listOf(0f, 1f); isEnabled = false }
        rows.addView(range)
        range.addOnChangeListener { _, _, fromUser ->
            if (fromUser) {
                rangeLabel.text = getString(R.string.video_range, range.values[0], range.values[1])
            }
        }
        range.addOnSliderTouchListener(object : RangeSlider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: RangeSlider) {}
            override fun onStopTrackingTouch(slider: RangeSlider) {
                lifecycleScope.launch { seekPreview((slider.values[0] * 1000).toLong()) }
            }
        })
        rows.addView(label(getString(R.string.overlay_ride)))
        rideSpinner = Spinner(this); rows.addView(rideSpinner)
        offset = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = getString(R.string.overlay_offset); setSingleLine(); setText("0")
        }
        rows.addView(offset)
        offset.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { updateSynchronizedPreview() }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        rows.addView(label(getString(R.string.overlay_alignment)))
        val calibration = LinearLayout(this)
        listOf(-1, 1).forEach { step -> calibration.addView(button(if (step < 0) "−1s" else "+1s") {
            val seconds = offset.text.toString().toDoubleOrNull() ?: 0.0
            offset.setText((seconds + step).toString()); updateSynchronizedPreview()
        }) }
        rows.addView(calibration)
        syncStatus = label(getString(R.string.sync_no_fix)); rows.addView(syncStatus)
        positionSeek = SeekBar(this).apply { max = 1000 }
        positionSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(seek: SeekBar) { draggingPosition = true }
            override fun onProgressChanged(seek: SeekBar, value: Int, user: Boolean) {}
            override fun onStopTrackingTouch(seek: SeekBar) {
                draggingPosition = false
                lifecycleScope.launch { seekPreview(seek.progress * durationMs / 1000) }
            }
        })
        rows.addView(positionSeek)
        mapContainer = FrameLayout(this).apply { id = savedInstanceState?.getInt("routeContainer") ?: View.generateViewId(); visibility = View.GONE }
        rows.addView(mapContainer, LinearLayout.LayoutParams(-1, (200 * resources.displayMetrics.density).toInt()))

        limit720p = CheckBox(this).apply { text = getString(R.string.video_fast_720p) }
        rows.addView(limit720p)
        progress = label(""); rows.addView(progress)
        export = button(getString(R.string.video_export)) { startExport() }; export.isEnabled = false; rows.addView(export)
        rows.addView(button(getString(R.string.export_cancel)) { exportJob?.cancel() })
        rows.addView(button(getString(R.string.bookmark_list)) { showBookmarks() })
        rows.addView(button(getString(R.string.event_export)) { exportEvent() })

        // Keep the evidence workflow together; optional ride tools remain expandable.
        rows.removeView(positionSeek)
        rows.addView(positionSeek, rows.indexOfChild(transport))
        val endpoints = LinearLayout(this)
        endpoints.addView(button("設為開始") { markEndpoint(true) })
        endpoints.addView(button("設為結束") { markEndpoint(false) })
        rows.addView(endpoints, rows.indexOfChild(rangeLabel))
        val advanced = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val firstAdvanced = rows.indexOfChild(range) + 1
        while (rows.getChildAt(firstAdvanced) != progress) {
            val child = rows.getChildAt(firstAdvanced)
            rows.removeView(child)
            advanced.addView(child)
        }
        rows.addView(button("騎乘疊圖與進階選項 ▾") {
            advanced.visibility = if (advanced.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        })
        rows.addView(advanced)
        export.text = "儲存選取片段"
        rows.addView(button("填寫／繼續檢舉草稿") {
            if (exportJob?.isActive == true) return@button
            player?.setPaused(true)
            reportDraft.open(if (selectedRide != null || videoTimestamp > 0) alignedVideoTime() + positionMs else 0L,
                selectedRide?.id)
        }, rows.indexOfChild(transport))

        if (savedInstanceState?.getBoolean("reportOpen") == true) reportDraft.open(
            if (videoTimestamp > 0) videoTimestamp + positionMs else 0L, savedInstanceState.getString("ride"))

        player = NativeFfmpegPlayer(gl, onProgress = { pos, duration ->
            positionMs = pos
            pauseAt?.takeIf { pos in it.first..(it.first + 250) }?.let {
                player?.setPaused(true); it.second.complete(Unit)
            }
            if (!draggingPosition && duration > 0) positionSeek.progress = (pos * 1000 / duration).coerceIn(0, 1000).toInt()
            updateSynchronizedPreview()
            if (duration > 0 && durationMs == 0L) {
                durationMs = duration
                range.valueTo = duration / 1000f
                range.values = listOf(0f, duration / 1000f)
                range.isEnabled = true; export.isEnabled = true
                rangeLabel.text = getString(R.string.video_range, 0f, duration / 1000f)
            }
        }, onError = { error -> progress.text = error })
        lifecycleScope.launch { player?.start(source.absolutePath, streaming = false, startPositionMs = positionMs) }
        lifecycleScope.launch {
            rides = withContext(Dispatchers.IO) { RideRepository.get(this@MediaToolsActivity).summaries().filter { it.pointCount > 0 } }
            rideSpinner.adapter = ArrayAdapter(this@MediaToolsActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf(getString(R.string.overlay_none)) + rides.map { DateFormat.getDateTimeInstance().format(Date(it.started)) })
            rideSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) {}
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val summary = rides.getOrNull(position - 1)
                    offset.isEnabled = summary != null
                    offset.setText(if (summary?.id == savedInstanceState?.getString("ride")) savedInstanceState?.getString("alignment") ?: "0"
                        else if (summary != null && videoTimestamp > 0) ((videoTimestamp - summary.started) / 1000.0).toString() else "0")
                    rideLoadJob?.cancel()
                    selectedRide = null
                    routeMap?.clear(); routeMarker = null
                    mapContainer.visibility = if (summary == null) View.GONE else View.VISIBLE
                    rideLoadJob = lifecycleScope.launch {
                        selectedRide = summary?.let { withContext(Dispatchers.IO) { RideRepository.get(this@MediaToolsActivity).read(it.id) } }
                        configureRouteMap()
                        updateSynchronizedPreview()
                    }
                }
            }
            savedInstanceState?.getString("ride")?.let { id ->
                val index = rides.indexOfFirst { it.id == id }
                if (index >= 0) rideSpinner.setSelection(index + 1)
            }
        }
    }

    private fun label(value: String) = TextView(this).apply { text = value; textSize = 14f; setPadding(0, 8, 0, 8) }
    private fun button(value: String, action: () -> Unit) = Button(this, null, android.R.attr.borderlessButtonStyle).apply {
        text = value; isAllCaps = false; setOnClickListener { action() }
    }
    private fun showBookmarks() {
        val points = bookmarks.list(key)
        if (points.isEmpty()) { Toast.makeText(this, R.string.bookmark_empty, Toast.LENGTH_SHORT).show(); return }
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle(R.string.bookmark_list)
            .setItems(points.map { "%02d:%02d".format(it / 60000, it / 1000 % 60) }.toTypedArray()) { _, which ->
                androidx.appcompat.app.AlertDialog.Builder(this).setItems(arrayOf(getString(R.string.bookmark_seek), getString(R.string.bookmark_remove))) { _, action ->
                    if (action == 0) lifecycleScope.launch { seekPreview(points[which]) }
                    else bookmarks.remove(key, points[which])
                }.show()
            }.show()
    }
    private suspend fun seekPreview(position: Long, pauseAfter: Boolean = false) {
        val native = player ?: return
        val reached = if (pauseAfter) CompletableDeferred<Unit>() else null
        if (reached != null) pauseAt = position to reached
        if (!native.hasSource || !native.seekTo(position)) native.start(source.absolutePath, streaming = false, startPositionMs = position)
        else native.setPaused(false)
        if (reached != null) {
            try { withTimeoutOrNull(3000) { reached.await() } }
            finally { native.setPaused(true); pauseAt = null }
        }
    }
    private fun startExport() {
        if (exportJob?.isActive == true) return
        val start = (range.values[0] * 1000).toLong(); val end = (range.values[1] * 1000).toLong()
        if (end - start < 500) { Toast.makeText(this, R.string.video_invalid_range, Toast.LENGTH_LONG).show(); return }
        val ride = selectedRide
        val seconds = offset.text.toString().toDoubleOrNull()
        if (ride != null && (seconds == null || !seconds.isFinite())) { offset.error = getString(R.string.overlay_offset); return }
        val videoTime = ride?.let { it.started + ((seconds ?: 0.0) * 1000).toLong() } ?: videoTimestamp
        if (ride != null && ride.points.none { it.time in (videoTime + start)..(videoTime + end) }) {
            Toast.makeText(this, R.string.overlay_no_overlap, Toast.LENGTH_LONG).show(); return
        }
        export.isEnabled = false; range.isEnabled = false; rideSpinner.isEnabled = false; offset.isEnabled = false; limit720p.isEnabled = false
        player?.setPaused(true)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        exportJob = lifecycleScope.launch {
            val date = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(Date(if (videoTimestamp > 0) videoTimestamp + start else System.currentTimeMillis()))
            val output = File(MediaFiles.moviesDir(this@MediaToolsActivity), "${date}_CLIP_${UUID.randomUUID().toString().take(8)}.mp4")
            try {
                VideoExporter().export(this@MediaToolsActivity, source, output, start, end, ride, videoTime, limit720p.isChecked) { done ->
                    runOnUiThread { progress.text = getString(R.string.video_export_progress, (done * 100 / (end - start)).coerceIn(0, 100).toInt()) }
                }
                val published = withContext(Dispatchers.IO) { runCatching { MediaFiles.publish(this@MediaToolsActivity, output, true) }.isSuccess }
                progress.text = getString(if (published) R.string.video_export_done else R.string.video_export_app_only, output.name)
                reportDraft.attach(output, start)
                rows.addView(button(getString(R.string.video_share)) { MediaFiles.share(this@MediaToolsActivity, output, "video/mp4") })
                showSaved(output, "video/mp4", published)
            } catch (cancelled: CancellationException) { progress.text = getString(R.string.export_cancelled); throw cancelled }
            catch (error: Throwable) { progress.text = getString(R.string.video_export_failed, error.message) }
            finally {
                export.isEnabled = true; range.isEnabled = true; rideSpinner.isEnabled = true
                limit720p.isEnabled = true
                offset.isEnabled = rideSpinner.selectedItemPosition > 0
                window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }
    private fun capturePhoto() {
        if (exportJob?.isActive == true || pauseAt != null || durationMs <= 0) return
        val native = player ?: return
        native.setPaused(true)
        exportJob = lifecycleScope.launch {
            val output = File(MediaFiles.moviesDir(this@MediaToolsActivity),
                "${source.nameWithoutExtension}_${positionMs}ms_${UUID.randomUUID().toString().take(8)}.jpg")
            try {
                native.saveSnapshot(output).getOrThrow()
                val published = withContext(Dispatchers.IO) {
                    if (android.os.Build.VERSION.SDK_INT < 29) false
                    else runCatching { MediaFiles.publish(this@MediaToolsActivity, output, false) }.isSuccess
                }
                progress.text = if (published) "照片已存入相簿：${output.name}" else "照片已儲存在 App，可使用下方分享：${output.name}"
                reportDraft.attach(output, positionMs)
                rows.addView(button("分享照片：${output.name}") {
                    MediaFiles.share(this@MediaToolsActivity, output, "image/jpeg")
                })
                showSaved(output, "image/jpeg", published)
            } catch (cancelled: CancellationException) {
                output.delete()
                throw cancelled
            } catch (error: Exception) {
                output.delete()
                progress.text = "照片擷取失敗：${error.message}"
            }
        }
    }
    private fun markEndpoint(start: Boolean) {
        if (durationMs <= 0 || exportJob?.isActive == true) return
        player?.setPaused(true)
        val seconds = (positionMs / 1000f).coerceIn(0f, range.valueTo)
        val values = range.values
        if ((start && seconds >= values[1]) || (!start && seconds <= values[0])) {
            Toast.makeText(this, "結束時間必須晚於開始時間", Toast.LENGTH_SHORT).show()
            return
        }
        range.values = if (start) listOf(seconds, values[1]) else listOf(values[0], seconds)
        rangeLabel.text = getString(R.string.video_range, range.values[0], range.values[1])
    }
    private fun showSaved(file: File, mime: String, published: Boolean) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(if (mime.startsWith("image")) "照片已儲存" else "片段已儲存")
            .setMessage(if (published) "已存入手機相簿，可在檢舉網站選取上傳。" else "已存入 App，可透過分享取出檔案。")
            .setPositiveButton("分享") { _, _ -> MediaFiles.share(this, file, mime) }
            .setNegativeButton("繼續擷取", null).show()
    }
    private fun alignedVideoTime(): Long {
        val ride = selectedRide ?: return videoTimestamp
        val seconds = offset.text.toString().toDoubleOrNull()?.takeIf { it.isFinite() } ?: return 0L
        return ride.started + (seconds * 1000).toLong()
    }
    private fun configureRouteMap() {
        val ride = selectedRide ?: return
        var fragment = supportFragmentManager.findFragmentByTag("sync-route") as? com.google.android.gms.maps.SupportMapFragment
        if (fragment == null) {
            fragment = com.google.android.gms.maps.SupportMapFragment.newInstance()
            supportFragmentManager.beginTransaction().replace(mapContainer.id, fragment, "sync-route").commitNow()
        }
        fragment.getMapAsync { map ->
            if (selectedRide?.id != ride.id) return@getMapAsync
            routeMap = map; map.clear(); routeMarker = null
            var segment = mutableListOf<com.google.android.gms.maps.model.LatLng>()
            fun draw() { if (segment.size > 1) map.addPolyline(com.google.android.gms.maps.model.PolylineOptions().addAll(segment).color(0xff00bfa5.toInt()).width(6f)) }
            ride.points.forEachIndexed { index, point ->
                if (index > 0 && point.elapsed - ride.points[index-1].elapsed > Ride.MAX_GAP_MS) { draw(); segment = mutableListOf() }
                segment.add(com.google.android.gms.maps.model.LatLng(point.lat, point.lon))
            }
            draw()
            ride.points.firstOrNull()?.let { map.moveCamera(com.google.android.gms.maps.CameraUpdateFactory.newLatLngZoom(com.google.android.gms.maps.model.LatLng(it.lat, it.lon), 14f)) }
            map.setOnMapClickListener { location ->
                val point = ride.points.minByOrNull { (it.lat - location.latitude) * (it.lat - location.latitude) + (it.lon - location.longitude) * (it.lon - location.longitude) }
                if (point != null) { offset.setText(((point.time - ride.started - positionMs) / 1000.0).toString()); updateSynchronizedPreview() }
            }
            updateSynchronizedPreview()
        }
    }
    private fun updateSynchronizedPreview() {
        if (!::syncStatus.isInitialized) return
        val point = selectedRide?.pointAt(alignedVideoTime() + positionMs)
        syncStatus.text = point?.let { getString(R.string.sync_position, it.speed?.toString() ?: "--", it.accuracy.toInt()) } ?: getString(R.string.sync_no_fix)
        routeMarker?.isVisible = point != null
        if (point != null) {
            val location = com.google.android.gms.maps.model.LatLng(point.lat, point.lon)
            if (routeMarker == null) routeMarker = routeMap?.addMarker(com.google.android.gms.maps.model.MarkerOptions().position(location))
            else routeMarker?.position = location
        }
    }
    private fun exportEvent() {
        if (exportJob?.isActive == true) return
        val input = EditText(this).apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER; setText("30") }
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle(R.string.event_window).setView(input)
            .setMessage(R.string.event_local_coverage)
            .setNegativeButton(android.R.string.cancel, null).setPositiveButton(R.string.event_export) { _, _ ->
                val seconds = input.text.toString().toLongOrNull()?.takeIf { it in 1..300 } ?: return@setPositiveButton
                if (videoTimestamp <= 0) { progress.text = getString(R.string.event_unknown_time); return@setPositiveButton }
                player?.setPaused(true)
                exportJob = lifecycleScope.launch {
                    val snapshot = File(cacheDir, "event_snapshot_${UUID.randomUUID()}.jpg")
                    try {
                        player?.saveSnapshot(snapshot)
                        val zip = EventPackage.create(this@MediaToolsActivity, source, videoTimestamp + positionMs,
                            seconds * 1000, seconds * 1000, selectedRide, snapshot, alignedVideoTime() - videoTimestamp) { done, total ->
                            runOnUiThread { progress.text = getString(R.string.event_progress, done, total) }
                        }
                        rows.addView(button(getString(R.string.event_share)) { MediaFiles.share(this@MediaToolsActivity, zip, "application/zip") })
                        progress.text = getString(R.string.event_ready)
                    } catch (error: CancellationException) { throw error }
                    catch (error: Exception) { progress.text = getString(R.string.video_export_failed, error.message) }
                    finally { snapshot.delete() }
                }
            }.show()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        reportDraft.saveEditingState()
        outState.putBoolean("reportOpen", reportDraft.isOpen)
        outState.putLong("position", positionMs); outState.putInt("routeContainer", mapContainer.id)
        outState.putString("ride", selectedRide?.id); outState.putString("alignment", offset.text.toString())
        super.onSaveInstanceState(outState)
    }
    override fun onPause() { player?.setPaused(true); super.onPause() }
    override fun onDestroy() { exportJob?.cancel(); player?.release(); super.onDestroy() }
}
