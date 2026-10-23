package com.aladdin.dashcam.media

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.ArrayDeque

/** Bounded operation metadata only; no credentials, raw settings or location data. */
object Diagnostics {
    private val events = ArrayDeque<String>()
    @Synchronized fun record(operation: String, result: String) {
        if (events.size >= 500) events.removeFirst()
        events.addLast(JSONObject().put("time", Instant.now().toString())
            .put("operation", operation).put("result", result.take(160)).toString())
    }
    @Synchronized fun export(context: Context): File {
        val dir = File(context.filesDir, "diagnostics").apply { mkdirs() }
        return File(dir, "diagnostics_${System.currentTimeMillis()}.jsonl").apply {
            writeText(JSONObject().put("platform", "Android ${android.os.Build.VERSION.SDK_INT}")
                .put("appVersion", context.packageManager.getPackageInfo(context.packageName, 0).versionName).toString() + "\n" + events.joinToString("\n"))
        }
    }
}
