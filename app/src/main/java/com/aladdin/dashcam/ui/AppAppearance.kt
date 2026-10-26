package com.aladdin.dashcam.ui

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate

object AppAppearance {
    private const val PREFERENCES = "app_appearance"
    private const val THEME = "theme"
    private var cachedLocaleContext: Context? = null
    private var cachedLocaleTags = ""
    private var cachedConfiguration: Configuration? = null

    fun localizedContext(context: Context): Context {
        val application = context.applicationContext
        val tags = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        if (tags.isEmpty()) return application
        return synchronized(this) {
            val configuration = application.resources.configuration
            if (cachedLocaleContext == null || cachedLocaleTags != tags || cachedConfiguration != configuration) {
                cachedConfiguration = Configuration(configuration)
                cachedLocaleContext = application.createConfigurationContext(Configuration(configuration).apply {
                    setLocales(android.os.LocaleList.forLanguageTags(tags))
                })
                cachedLocaleTags = tags
            }
            checkNotNull(cachedLocaleContext)
        }
    }

    fun nightMode(context: Context): Int = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        .getInt(THEME, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)

    fun apply(context: Context) {
        AppCompatDelegate.setDefaultNightMode(nightMode(context))
    }

    fun setNightMode(context: Context, mode: Int) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putInt(THEME, mode).apply()
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    fun applyContentInsets(view: android.view.View, leftDp: Int, topDp: Int, rightDp: Int, bottomDp: Int) {
        val density = view.resources.displayMetrics.density
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(view) { content, insets ->
            val safe = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                androidx.core.view.WindowInsetsCompat.Type.displayCutout() or androidx.core.view.WindowInsetsCompat.Type.ime())
            content.setPadding((leftDp * density).toInt() + safe.left, (topDp * density).toInt() + safe.top,
                (rightDp * density).toInt() + safe.right, (bottomDp * density).toInt() + safe.bottom)
            insets
        }
        androidx.core.view.ViewCompat.requestApplyInsets(view)
    }
}
