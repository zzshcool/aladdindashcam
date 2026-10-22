package com.aladdin.dashcam.media

import android.graphics.Bitmap
import androidx.collection.LruCache
import java.io.File

/**
 * 媒體快取管理員：
 * 1. 提供縮圖與預覽快取的磁碟配額管理與 LRU 淘汰機制（Quota & Low-watermark Eviction）。
 * 2. 提供記憶體層級的縮圖 LruCache，消除相簿滑動時重複 decodeFile 造成的卡頓與 GC。
 * 3. 嚴格限定僅淘汰 cacheDir 內部暫存檔，絕對不觸及使用者永久下載的影片與照片。
 */
class MediaCacheManager(
    val thumbnailQuotaBytes: Long = DEFAULT_THUMBNAIL_QUOTA_BYTES,
    val thumbnailLowWatermarkBytes: Long = DEFAULT_THUMBNAIL_LOW_WATERMARK_BYTES,
    val previewQuotaBytes: Long = DEFAULT_PREVIEW_QUOTA_BYTES,
    val previewLowWatermarkBytes: Long = DEFAULT_PREVIEW_LOW_WATERMARK_BYTES,
    maxMemoryCacheKb: Int = ((Runtime.getRuntime().maxMemory() / 1024) / 8).toInt().coerceAtLeast(1024)
) {
    companion object {
        const val DEFAULT_THUMBNAIL_QUOTA_BYTES = 50L * 1024 * 1024 // 50MB
        const val DEFAULT_THUMBNAIL_LOW_WATERMARK_BYTES = 35L * 1024 * 1024 // 35MB
        const val DEFAULT_PREVIEW_QUOTA_BYTES = 200L * 1024 * 1024 // 200MB
        const val DEFAULT_PREVIEW_LOW_WATERMARK_BYTES = 150L * 1024 * 1024 // 150MB
    }

    private val memoryCache: LruCache<String, Bitmap> = object : LruCache<String, Bitmap>(maxMemoryCacheKb) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return (value.byteCount / 1024).coerceAtLeast(1)
        }
    }

    fun getBitmap(key: String): Bitmap? {
        if (key.isBlank()) return null
        return synchronized(memoryCache) {
            memoryCache.get(key)
        }
    }

    fun putBitmap(key: String, bitmap: Bitmap) {
        if (key.isBlank()) return
        synchronized(memoryCache) {
            memoryCache.put(key, bitmap)
        }
    }

    fun removeBitmap(key: String) {
        if (key.isBlank()) return
        synchronized(memoryCache) {
            memoryCache.remove(key)
        }
    }

    fun clearMemoryCache() {
        synchronized(memoryCache) {
            memoryCache.evictAll()
        }
    }

    /**
     * 更新檔案的 lastModified 時間，保持其在 LRU 中的最新狀態。
     */
    fun touchFile(file: File) {
        try {
            if (file.exists()) {
                file.setLastModified(System.currentTimeMillis())
            }
        } catch (_: Exception) {}
    }

    /**
     * 當指定目錄之檔案總大小超過 quotaBytes 時，按檔案 lastModified 由舊到新進行淘汰，
     * 直到總大小小於等於 targetBytes。
     * @return 釋放的位元組數
     */
    fun evictDirectoryIfExceeds(
        directory: File,
        quotaBytes: Long,
        targetBytes: Long
    ): Long {
        if (!directory.exists() || !directory.isDirectory) return 0L

        val allFiles = directory.walkTopDown().filter { it.isFile }.toList()
        val totalSize = allFiles.sumOf { it.length() }

        if (totalSize <= quotaBytes) {
            return 0L
        }

        var currentSize = totalSize
        var bytesFreed = 0L

        // 按 lastModified 升序（最舊的在前）排序
        val sortedFiles = allFiles.sortedBy { it.lastModified() }

        for (file in sortedFiles) {
            if (currentSize <= targetBytes) break
            val fileSize = file.length()
            if (file.delete()) {
                currentSize -= fileSize
                bytesFreed += fileSize
            }
        }

        return bytesFreed
    }
}
