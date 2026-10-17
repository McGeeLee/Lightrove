package com.mcgeelee.photos.localization

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.annotation.MainThread
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.LocaleManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.os.ConfigurationCompat
import androidx.core.os.LocaleListCompat

/** Native app locales; AndroidX owns persistence and migration to the Android 13 framework. */
object AppLanguage {
    val supportedTags = listOf("en", "zh-CN", "zh-TW", "ja", "ko", "hi")

    // AndroidX persists API 31/32 changes asynchronously. Keep that brief interval consistent for
    // workers and Application-scoped services without maintaining a second persistent preference.
    @Volatile private var pendingCompatTag: String? = null

    fun selectedTag(context: Context): String = if (Build.VERSION.SDK_INT >= 33) {
        LocaleManagerCompat.getApplicationLocales(context).toLanguageTags().substringBefore(',')
    } else {
        pendingCompatTag ?: AppCompatDelegate.getApplicationLocales().toLanguageTags().ifEmpty {
            LocaleManagerCompat.getApplicationLocales(context).toLanguageTags()
        }.substringBefore(',')
    }

    @MainThread
    fun select(tag: String) {
        require(tag.isEmpty() || tag in supportedTags) { "Unsupported application locale: $tag" }
        if (Build.VERSION.SDK_INT <= 32) pendingCompatTag = tag
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
    }

    /** Resolves notifications, background work and retained ViewModels in the current app locale. */
    fun localizedContext(context: Context): Context {
        val locales = if (Build.VERSION.SDK_INT >= 33) {
            LocaleManagerCompat.getApplicationLocales(context)
        } else {
            val pending = pendingCompatTag ?: return ContextCompat.getContextForLanguage(context)
            LocaleListCompat.forLanguageTags(pending)
        }
        val effective = if (locales.isEmpty) LocaleManagerCompat.getSystemLocales(context) else locales
        val configuration = Configuration(context.resources.configuration)
        ConfigurationCompat.setLocales(configuration, effective)
        return context.createConfigurationContext(configuration)
    }
}
