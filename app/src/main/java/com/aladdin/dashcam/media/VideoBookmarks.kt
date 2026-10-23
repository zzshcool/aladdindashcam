package com.aladdin.dashcam.media

import android.content.Context
import org.json.JSONArray

class VideoBookmarks(context: Context) {
    private val prefs = context.getSharedPreferences("video_bookmarks", Context.MODE_PRIVATE)
    fun list(key: String): List<Long> {
        val array = JSONArray(prefs.getString(key, "[]"))
        return (0 until array.length()).map { array.getLong(it) }.sorted()
    }
    fun add(key: String, position: Long) = save(key, (list(key) + position.coerceAtLeast(0)).distinctBy { it / 1000 })
    fun remove(key: String, position: Long) = save(key, list(key).filter { it != position })
    private fun save(key: String, points: List<Long>) {
        prefs.edit().putString(key, JSONArray(points).toString()).apply()
    }
}
