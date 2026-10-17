package com.mcgeelee.photos.localization

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppLanguageSettingsTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun android13AndLaterTargetThisAppsLanguageWithSafeFallbacks() {
        val intents = AppLanguageSettings.intents(context.packageName, 33)
        assertEquals(listOf(Settings.ACTION_APP_LOCALE_SETTINGS,
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Settings.ACTION_SETTINGS), intents.map { it.action })
        intents.take(2).forEach {
            assertEquals("package", it.data?.scheme)
            assertEquals(context.packageName, it.data?.schemeSpecificPart)
        }
        assertNull(intents.last().data)
    }

    @Test fun android12UsesSystemLanguagesWithoutOfferingAnUnavailableAppPicker() {
        val intents = AppLanguageSettings.intents(context.packageName, 31)
        assertEquals(listOf(Settings.ACTION_LOCALE_SETTINGS, Settings.ACTION_SETTINGS), intents.map { it.action })
        assertTrue(intents.all { it.data == null })
        assertFalse(intents.any { it.action == Settings.ACTION_APP_LOCALE_SETTINGS })
    }

    @Test
    @SdkSuppress(minSdkVersion = 33)
    fun theCurrentSystemResolvesTheAppLanguageDestination() {
        val destination = AppLanguageSettings.intents(context.packageName).first()
        assertEquals(Settings.ACTION_APP_LOCALE_SETTINGS, destination.action)
        assertNotNull("System app-language settings must be launchable on this Android device",
            context.packageManager.resolveActivity(destination, PackageManager.MATCH_DEFAULT_ONLY))
    }

    @Test
    @SdkSuppress(minSdkVersion = 33)
    fun vendorActivityFailuresFallBackAndAllFailuresAreReported() =
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
        val attempts = mutableListOf<String?>()
        val fallbackActivity = object : Activity() {
            override fun getPackageName() = context.packageName
            override fun startActivity(intent: Intent) {
                attempts += intent.action
                when (attempts.size) {
                    1 -> throw ActivityNotFoundException("Dedicated picker missing")
                    2 -> throw SecurityException("Vendor details activity refuses external callers")
                }
            }
        }
        assertTrue(AppLanguageSettings.open(fallbackActivity))
        assertEquals(listOf(Settings.ACTION_APP_LOCALE_SETTINGS,
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Settings.ACTION_SETTINGS), attempts)

        val unavailableActivity = object : Activity() {
            override fun getPackageName() = context.packageName
            override fun startActivity(intent: Intent) { throw ActivityNotFoundException("No settings handler") }
        }
        assertFalse(AppLanguageSettings.open(unavailableActivity))
        }
}
