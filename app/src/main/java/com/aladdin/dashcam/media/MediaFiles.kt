package com.aladdin.dashcam.media

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.ClipData
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File

object MediaFiles {
    fun moviesDir(context: Context): File = (context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
        ?: File(context.filesDir, "movies")).apply { mkdirs() }

    fun share(context: Context, file: File, mime: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = mime; putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, null))
    }

    /** Only finalized files are published. A failed copy never leaves a visible partial item. */
    fun publish(context: Context, file: File, video: Boolean) {
        if (Build.VERSION.SDK_INT < 29) return
        val collection = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val resolver = context.contentResolver
        val uri = resolver.insert(collection, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, if (video) "video/mp4" else "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, if (video) "Movies/AladdinDashCam" else "Pictures/AladdinDashCam")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }) ?: error("Cannot create gallery item")
        try {
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                ?: error("Cannot open gallery item")
            check(resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1)
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        }
    }
}
