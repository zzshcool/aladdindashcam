package com.aladdin.dashcam.ui

import android.os.Bundle
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.aladdin.dashcam.R
import com.aladdin.dashcam.backup.LocalBackup
import kotlinx.coroutines.*
import java.io.File
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LocalBackupActivity : AppCompatActivity() {
    private lateinit var export: Button
    private lateinit var restore: Button
    private lateinit var status: TextView
    private var pendingExport: File? = null
    private var preview: LocalBackup.Preview? = null
    private var confirmation: AlertDialog? = null
    private val createDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val file = pendingExport
        pendingExport = null
        if (uri == null || file == null) {
            file?.delete(); busy(false); status.setText(R.string.backup_cancelled)
            return@registerForActivityResult
        }
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    requireNotNull(contentResolver.openOutputStream(uri, "w")).use { out -> file.inputStream().use { it.copyTo(out) } }
                }
                status.setText(R.string.backup_export_done)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { fail(error, R.string.backup_export_failed) }
            finally { file.delete(); busy(false) }
        }
    }
    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        busy(true)
        lifecycleScope.launch {
            try {
                val data = withContext(Dispatchers.IO) {
                    requireNotNull(contentResolver.openInputStream(uri)).use { LocalBackup.inspect(this@LocalBackupActivity, it) }
                }
                preview?.discard(); preview = data
                confirm(data)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { fail(error, R.string.backup_invalid) }
            finally { busy(false) }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingExport = savedInstanceState?.getString("export_file")?.let { File(it) }
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        setContentView(ScrollView(this).apply { addView(rows) })
        AppAppearance.applyContentInsets(rows, 20, 20, 20, 20)
        fun button(label: Int, click: () -> Unit) = Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            setText(label); isAllCaps = false; minHeight = (48 * resources.displayMetrics.density).toInt(); setOnClickListener { click() }
        }
        rows.addView(button(R.string.backup_title) { finish() }.apply { text = "‹ ${getString(R.string.backup_title)}" })
        rows.addView(TextView(this).apply { setText(R.string.backup_description); textSize = 15f })
        export = button(R.string.backup_export) {
            busy(true)
            lifecycleScope.launch {
                try {
                    val file = withContext(Dispatchers.IO) { LocalBackup.export(this@LocalBackupActivity) }
                    pendingExport = file
                    val date = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                    createDocument.launch("AladdinDashCam-$date.zip")
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { busy(false); fail(error, R.string.backup_export_failed) }
            }
        }
        restore = button(R.string.backup_import) { openDocument.launch(arrayOf("application/zip", "application/octet-stream", "application/x-zip-compressed")) }
        rows.addView(export); rows.addView(restore)
        status = TextView(this).apply { textSize = 14f }
        rows.addView(status)
        busy(pendingExport != null)
    }
    private fun busy(value: Boolean) {
        export.isEnabled = !value; restore.isEnabled = !value
        if (value) status.setText(R.string.backup_working)
    }
    private fun confirm(data: LocalBackup.Preview) {
        val settings = CheckBox(this).apply { setText(R.string.backup_restore_settings); isChecked = true }
        var started = false
        confirmation = AlertDialog.Builder(this).setTitle(R.string.backup_import)
            .setMessage(getString(R.string.backup_preview,
                DateFormat.getDateTimeInstance().format(Date(data.createdAt)), data.records.size, data.rides.size, data.plates.size))
            .setView(settings).setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.backup_merge) { _, _ ->
                started = true; preview = null; busy(true)
                lifecycleScope.launch {
                    try {
                        val result = withContext(Dispatchers.IO) { LocalBackup.mergeImport(this@LocalBackupActivity, data) }
                        status.text = getString(R.string.backup_import_done, result.maintenance, result.rides, result.skipped, result.plates)
                        Toast.makeText(this@LocalBackupActivity, status.text, Toast.LENGTH_LONG).show()
                        if (settings.isChecked) LocalBackup.applyPreferences(this@LocalBackupActivity, data.settings)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { fail(error, R.string.backup_import_failed) }
                    finally { data.discard(); busy(false) }
                }
            }.create().also { dialog ->
                dialog.setOnDismissListener {
                    if (!started) { data.discard(); preview = null; status.setText(R.string.backup_cancelled) }
                }
                dialog.show()
            }
    }
    private fun fail(error: Exception, message: Int) {
        android.util.Log.e("LocalBackup", "Local backup operation failed", error)
        status.setText(if (error.message == "ride_active") R.string.backup_ride_active else message)
    }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("export_file", pendingExport?.absolutePath)
    }
    override fun onDestroy() {
        confirmation?.dismiss(); preview?.discard()
        if (isFinishing) pendingExport?.delete()
        super.onDestroy()
    }
}
