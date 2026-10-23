package com.aladdin.dashcam.media

import android.app.Application
import android.media.MediaMetadataRetriever
import com.aladdin.dashcam.model.MediaItem
import com.aladdin.dashcam.network.DashCamClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

class MediaRepository(private val app: Application, private val client: DashCamClient,
                      private val mediaCacheManager: MediaCacheManager) {
    private val thumbnailMutex = Mutex()


    suspend fun thumbnail(item: MediaItem, local: File?, canFetch: () -> Boolean): File? = withContext(Dispatchers.IO) {
        thumbnailMutex.withLock {
        val thumbDir = File(app.cacheDir, "thumbnails")
        if (!thumbDir.exists()) thumbDir.mkdirs()
        val cacheKey = java.security.MessageDigest.getInstance("SHA-256")
            .digest(item.storageKey.toByteArray()).joinToString("") { "%02x".format(it) }
        val thumbFile = File(thumbDir, "thumb_$cacheKey.jpg")
        if (thumbFile.exists() && thumbFile.length() > 0) {
            mediaCacheManager.touchFile(thumbFile)
            return@withContext thumbFile
        }

        // 本機已下載或 LOCAL_ 項目直接於背景線程解碼，不發送遠端 GET（R10 閉合）
        if (item.isDownloaded || item.id.startsWith("LOCAL_")) {
            val localFile = local
            if (localFile != null && localFile.length() > 0) {
                val ok = generateLocalThumbnail(localFile, thumbFile, item.isVideo)
                if (ok) {
                    mediaCacheManager.touchFile(thumbFile)
                    mediaCacheManager.evictDirectoryIfExceeds(
                        thumbDir,
                        mediaCacheManager.thumbnailQuotaBytes,
                        mediaCacheManager.thumbnailLowWatermarkBytes
                    )
                    return@withContext thumbFile
                }
            }
            return@withContext null
        }

        if (!canFetch()) {
            return@withContext null
        }
        val image = client.getThumbnail(item.deviceIndex, expectedEpoch = item.sourceEpoch).getOrNull()
        if (image != null && image.size > 4 &&
            image[0] == 0xFF.toByte() && image[1] == 0xD8.toByte() &&
            image[image.lastIndex - 1] == 0xFF.toByte() && image[image.lastIndex] == 0xD9.toByte()
        ) {
            try {
                thumbFile.writeBytes(image)
                mediaCacheManager.touchFile(thumbFile)
                mediaCacheManager.evictDirectoryIfExceeds(
                    thumbDir,
                    mediaCacheManager.thumbnailQuotaBytes,
                    mediaCacheManager.thumbnailLowWatermarkBytes
                )
                return@withContext thumbFile
            } catch (_: java.io.IOException) {
                thumbFile.delete()
            }
        }
        null
        }
    }

    private fun generateLocalThumbnail(sourceFile: File, destThumbFile: File, isVideo: Boolean): Boolean {
        return try {
            val bitmap: android.graphics.Bitmap? = if (isVideo) {
                var retriever: MediaMetadataRetriever? = null
                try {
                    retriever = MediaMetadataRetriever()
                    retriever.setDataSource(sourceFile.absolutePath)
                    if (android.os.Build.VERSION.SDK_INT >= 27) {
                        retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 180)
                    } else retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                } finally {
                    retriever?.release()
                }
            } else {
                val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(sourceFile.absolutePath, options)
                options.inSampleSize = 1
                while (options.outWidth / options.inSampleSize > 640 || options.outHeight / options.inSampleSize > 640) {
                    options.inSampleSize *= 2
                }
                options.inJustDecodeBounds = false
                android.graphics.BitmapFactory.decodeFile(sourceFile.absolutePath, options)
            }
            if (bitmap != null) {
                java.io.FileOutputStream(destThumbFile).use { out ->
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out)
                }
                bitmap.recycle()
                true
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

}
