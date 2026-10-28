package com.aladdin.dashcam.ui

import android.content.*
import android.net.Uri
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.aladdin.dashcam.gps.RideRepository
import com.aladdin.dashcam.media.MediaFiles
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** One persistent evidence draft per source video; GPS is sampled at the selected event time. */
class ReportDraftEditor(private val activity: AppCompatActivity, private val source: File) {
    private val prefs = activity.getSharedPreferences("report_drafts", 0)
    private val draftKey = source.absolutePath
    private fun load() = JSONObject(prefs.getString(draftKey, "{}") ?: "{}")
    private fun save(draft: JSONObject) { check(prefs.edit().putString(draftKey, draft.toString()).commit()) }

    var isOpen = false
        private set

    private var captureEditingState: (() -> Unit)? = null
    fun saveEditingState() { captureEditingState?.invoke() }

    fun attach(file: File, position: Long) {
        val draft = load()
        val files = draft.optJSONArray("attachments") ?: JSONArray()
        files.put(JSONObject().put("path", file.absolutePath).put("positionMs", position))
        draft.put("attachments", files)
        save(draft)
    }

    fun open(eventTime: Long, rideId: String?) {
        activity.lifecycleScope.launch {
            val draft = load()
            val initialTime = draft.optString("違規時間").takeIf { it.isNotBlank() }?.let {
                runCatching { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).parse(it)?.time }.getOrNull()
            } ?: eventTime
            var point = withContext(Dispatchers.IO) {
                val repo = RideRepository.get(activity)
                if (rideId != null) repo.read(rideId)?.pointAt(initialTime)
                else if (initialTime > 0) repo.summaries().filter { it.started <= initialTime && (it.ended == null || it.ended >= initialTime - 3500) }
                    .mapNotNull { repo.read(it.id)?.pointAt(initialTime) }.singleOrNull()
                else null
            }
            var matchedTime = initialTime
            val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 12, 32, 12) }
            val fields = linkedMapOf<String, EditText>()
            fun field(name: String, initial: String = ""): EditText {
                body.addView(TextView(activity).apply { text = name })
                return EditText(activity).apply { setText(draft.optString(name, initial)); body.addView(this); fields[name] = this }
            }
            val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { isLenient = false }
            field("違規時間", if (eventTime > 0) format.format(Date(eventTime)) else "")
            val gpsStatus = TextView(activity).apply { text = point?.let { "事件 GPS：${it.lat}, ${it.lon}（精度 ±${it.accuracy.toInt()}m）" } ?: "無對應 GPS；請手動填寫地點或座標。" }; body.addView(gpsStatus)
            val lat = field("緯度", point?.lat?.toString() ?: "")
            val lon = field("經度", point?.lon?.toString() ?: "")
            body.addView(Button(activity).apply {
                text = "帶入目前事件時間與 GPS"
                setOnClickListener {
                    fields.getValue("違規時間").setText(if (eventTime > 0) format.format(Date(eventTime)) else "")
                    lat.setText(point?.lat?.toString() ?: ""); lon.setText(point?.lon?.toString() ?: "")
                }
            })
            fun coordinates(): Pair<Double, Double>? {
                val a = lat.text.toString().toDoubleOrNull(); val b = lon.text.toString().toDoubleOrNull()
                return if (a != null && b != null && a.isFinite() && b.isFinite() && a in -90.0..90.0 && b in -180.0..180.0) a to b else null
            }
            body.addView(Button(activity).apply { text = "在地圖確認位置"; setOnClickListener {
                val p = coordinates()
                if (p == null) Toast.makeText(activity, "請填寫有效經緯度", Toast.LENGTH_SHORT).show()
                else runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:${p.first},${p.second}?q=${p.first},${p.second}"))) }
                    .onFailure { Toast.makeText(activity, "手機沒有可開啟地圖的應用程式", Toast.LENGTH_SHORT).show() }
            } })
            field("違規地點（道路／路口／方向）")
            body.addView(TextView(activity).apply { text = "檢舉縣市（依事件座標建議，請確認）" })
            val authorities = listOf("請選擇縣市") + ReportJurisdiction.regions
            val authority = Spinner(activity).apply {
                adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, authorities)
                setSelection(authorities.indexOf(draft.optString("jurisdiction")).coerceAtLeast(0))
            }
            body.addView(authority)
            val jurisdictionStatus = TextView(activity); body.addView(jurisdictionStatus)
            var lookupJob: Job? = null
            var manualAuthority = draft.optBoolean("jurisdictionManual", false) && draft.optString("jurisdiction") in ReportJurisdiction.regions
            authority.setOnTouchListener { _, event ->
                if (event.action == android.view.MotionEvent.ACTION_DOWN) manualAuthority = true
                false
            }
            body.addView(Button(activity).apply { text = "手動選擇檢舉機關"; setOnClickListener {
                AlertDialog.Builder(activity).setItems(authorities.drop(1).toTypedArray()) { _, index ->
                    manualAuthority = true; authority.setSelection(index + 1)
                    jurisdictionStatus.text = "已手動指定；可按依座標重新判斷恢復建議。"
                }.show()
            } })
            fun lookup(force: Boolean) {
                lookupJob?.cancel()
                val coordinates = coordinates()
                if (force) manualAuthority = false
                if (!manualAuthority) authority.setSelection(0)
                if (coordinates == null) { jurisdictionStatus.text = "尚無有效事件座標，請手動選擇。"; return }
                lookupJob = activity.lifecycleScope.launch {
                    delay(600)
                    jurisdictionStatus.text = "正在依事件座標判斷縣市…"
                    val region = try { withTimeoutOrNull(12000) { ReportJurisdiction.resolve(activity, coordinates.first, coordinates.second) } }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { null }
                    if (region != null) {
                        if (!manualAuthority) authority.setSelection(authorities.indexOf(region))
                        jurisdictionStatus.text = "座標位於 $region；請確認檢舉縣市。"
                    } else jurisdictionStatus.text = "無法確認縣市（可能離線或位於邊界），請手動選擇。"
                }
            }
            val coordinateWatcher = object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { lookup(false) }
                override fun afterTextChanged(s: android.text.Editable?) {}
            }
            lat.addTextChangedListener(coordinateWatcher); lon.addTextChangedListener(coordinateWatcher)
            body.addView(Button(activity).apply { text = "依座標重新判斷縣市"; setOnClickListener { lookup(true) } })
            body.addView(Button(activity).apply { text = "開啟官方檢舉入口"; setOnClickListener {
                val selected = authority.selectedItem.toString()
                if (authority.selectedItemPosition == 0) { Toast.makeText(activity, "請先確認檢舉機關", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                val url = ReportJurisdiction.portal(selected)
                if (url == ReportJurisdiction.DIRECTORY) Toast.makeText(activity, "此縣市請由官方目錄確認入口：$selected", Toast.LENGTH_LONG).show()
                runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                    .onFailure { Toast.makeText(activity, "無法開啟瀏覽器", Toast.LENGTH_SHORT).show() }
            } })
            lookup(false)
            field("車牌號碼")
            field("車種")
            val situation = Spinner(activity).apply { adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item,
                listOf("選擇常用描述", "未依號誌行駛", "違規變換車道", "違規停車", "未禮讓行人", "其他")) }
            body.addView(situation)
            val description = field("違規事項與經過")
            situation.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) {}
                override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                    if (position > 0) description.setText(listOf(description.text.toString(), situation.selectedItem.toString()).filter { it.isNotBlank() }.joinToString("\n"))
                }
            }
            val profile = activity.getSharedPreferences("report_profile", 0)
            listOf("姓名", "電話", "電子郵件", "聯絡地址").forEach { field(it, profile.getString(it, "") ?: "") }
            val remember = CheckBox(activity).apply { text = "記住聯絡資料供下次帶入" }
            body.addView(remember)
            body.addView(TextView(activity).apply { text = "附件（點選可預覽或從草稿移除）" })
            val attachments = draft.optJSONArray("attachments") ?: JSONArray()
            val removedPaths = mutableSetOf<String>()
            val attachmentRows = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }; body.addView(attachmentRows)
            fun renderAttachments() {
                attachmentRows.removeAllViews()
                for (i in 0 until attachments.length()) {
                    val item = attachments.getJSONObject(i); val file = File(item.getString("path"))
                    attachmentRows.addView(Button(activity).apply {
                        text = "${file.name} · ${item.optLong("positionMs") / 1000.0}s${if (!file.isFile) "（檔案已遺失）" else ""}"
                        setOnClickListener {
                            AlertDialog.Builder(activity).setItems(arrayOf("預覽", "從草稿移除")) { _, action ->
                                if (action == 1) { removedPaths.add(file.absolutePath); attachments.remove(i); load().also { it.put("attachments", attachments); save(it) }; renderAttachments() }
                                else runCatching {
                                    val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", file)
                                    activity.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, if (file.extension == "jpg") "image/jpeg" else "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                                }.onFailure { Toast.makeText(activity, "無法開啟附件", Toast.LENGTH_SHORT).show() }
                            }.show()
                        }
                    })
                }
            }
            renderAttachments()
            fun persist(): Boolean {
                val date = fields.getValue("違規時間")
                if (date.text.isNotBlank() && runCatching { val p = java.text.ParsePosition(0); format.parse(date.text.toString(), p) != null && p.index == date.text.length }.getOrDefault(false).not()) {
                    date.error = "格式：yyyy-MM-dd HH:mm:ss"; return false
                }
                if ((lat.text.isNotBlank() || lon.text.isNotBlank()) && coordinates() == null) { lat.error = "請填寫有效經緯度"; return false }
                fields.forEach { (name, input) -> draft.put(name, input.text.toString().trim()) }
                val currentPoint = point
                val gpsMatched = currentPoint != null && coordinates() == (currentPoint.lat to currentPoint.lon) &&
                    date.text.toString() == format.format(Date(matchedTime))
                draft.put("locationSource", if (gpsMatched) "recorded_gps" else if (coordinates() != null) "manual" else "unavailable")
                draft.put("gpsFixTime", if (gpsMatched) currentPoint!!.time else JSONObject.NULL)
                draft.put("gpsAccuracyMeters", if (gpsMatched) currentPoint!!.accuracy.toDouble() else JSONObject.NULL)
                val merged = load().optJSONArray("attachments") ?: attachments
                val kept = JSONArray()
                for (i in 0 until merged.length()) if (merged.getJSONObject(i).getString("path") !in removedPaths) kept.put(merged.getJSONObject(i))
                draft.put("source", source.name).put("attachments", kept)
                draft.put("jurisdiction", if (authority.selectedItemPosition > 0) authority.selectedItem.toString() else "")
                    .put("jurisdictionManual", manualAuthority)
                save(draft)
                if (remember.isChecked) profile.edit().apply { listOf("姓名", "電話", "電子郵件", "聯絡地址").forEach { putString(it, fields.getValue(it).text.toString()) } }.apply()
                return true
            }
            fun reportText() = fields.entries.joinToString("\n") { "${it.key}：${it.value.text}" } + "\n檢舉機關：${if (authority.selectedItemPosition > 0) authority.selectedItem else "尚未確認"}\n原始影片：${source.name}" +
                (coordinates()?.let { "\n地圖：https://maps.google.com/?q=${it.first},${it.second}" } ?: "")
            body.addView(Button(activity).apply { text = "複製檢舉文字"; setOnClickListener {
                if (persist()) { (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("檢舉草稿", reportText())); Toast.makeText(activity, "已複製", Toast.LENGTH_SHORT).show() }
            } })
            val status = TextView(activity); body.addView(status)
            body.addView(Button(activity).apply { text = "匯出草稿與附件 ZIP"; setOnClickListener {
                if (!persist()) return@setOnClickListener
                val text = reportText(); val json = draft.toString(2)
                val files = (0 until attachments.length()).map { File(attachments.getJSONObject(it).getString("path")) }
                isEnabled = false
                activity.lifecycleScope.launch {
                    val output = File(MediaFiles.moviesDir(activity), "REPORT_${UUID.randomUUID()}.zip")
                    try {
                        withContext(Dispatchers.IO) {
                            check(files.all { it.isFile }) { "附件遺失，請移除或重新擷取" }
                            ZipOutputStream(output.outputStream()).use { zip ->
                                fun entry(name: String, bytes: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
                                entry("report.txt", text.toByteArray(Charsets.UTF_8)); entry("draft.json", json.toByteArray(Charsets.UTF_8))
                                files.forEach { file -> zip.putNextEntry(ZipEntry("attachments/${file.name}")); file.inputStream().use { it.copyTo(zip) }; zip.closeEntry() }
                            }
                        }
                        status.text = "已匯出：${output.name}"
                        MediaFiles.share(activity, output, "application/zip")
                    } catch (error: Exception) { output.delete(); status.text = "匯出失敗：${error.message}" }
                    finally { isEnabled = true }
                }
            } })
            val dialog = AlertDialog.Builder(activity).setTitle("檢舉草稿")
                .setView(ScrollView(activity).apply { addView(body) })
                .setPositiveButton("儲存草稿", null).setNegativeButton("返回", null).create()
            dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (persist()) { Toast.makeText(activity, "草稿已儲存", Toast.LENGTH_SHORT).show(); dialog.dismiss() }
            } }
            var gpsJob: Job? = null
            val autosave = object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    val latest = load()
                    fields.forEach { (name, input) -> latest.put(name, input.text.toString()) }
                    save(latest)
                }
            }
            fields.values.forEach { it.addTextChangedListener(autosave) }
            fields.getValue("違規時間").addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    gpsJob?.cancel()
                    point = null
                    gpsStatus.text = "時間已變更，正在重新比對 GPS…"
                    lat.setText(""); lon.setText("")
                    val time = runCatching { val pos = java.text.ParsePosition(0); format.parse(s.toString(), pos)?.time?.takeIf { pos.index == s.toString().length } }.getOrNull()
                    if (time == null) { gpsStatus.text = "請輸入完整時間後比對 GPS"; return }
                    gpsJob = activity.lifecycleScope.launch {
                        delay(500)
                        val found = withContext(Dispatchers.IO) {
                            val repo = RideRepository.get(activity)
                            if (rideId != null) repo.read(rideId)?.pointAt(time)
                            else repo.summaries().filter { it.started <= time && (it.ended == null || it.ended >= time - 3500) }
                                .mapNotNull { repo.read(it.id)?.pointAt(time) }.singleOrNull()
                        }
                        point = found; matchedTime = time
                        lat.setText(found?.lat?.toString() ?: ""); lon.setText(found?.lon?.toString() ?: "")
                        gpsStatus.text = found?.let { "事件 GPS：${it.lat}, ${it.lon}（精度 ±${it.accuracy.toInt()}m）" } ?: "此事件時間無對應 GPS；請手動填寫地點。"
                    }
                }
            })
            captureEditingState = {
                val latest = load()
                fields.forEach { (name, input) -> latest.put(name, input.text.toString()) }
                latest.put("jurisdiction", if (authority.selectedItemPosition > 0) authority.selectedItem.toString() else "")
                    .put("jurisdictionManual", manualAuthority)
                save(latest)
            }
            dialog.setOnDismissListener { captureEditingState = null; isOpen = false; lookupJob?.cancel(); gpsJob?.cancel() }
            isOpen = true
            dialog.show()
        }
    }
}
