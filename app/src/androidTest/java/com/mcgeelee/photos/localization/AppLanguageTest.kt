package com.mcgeelee.photos.localization

import android.app.LocaleConfig
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.LocaleManagerCompat
import androidx.core.os.ConfigurationCompat
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.mcgeelee.photos.MainActivity
import com.mcgeelee.photos.PhotosViewModel
import com.mcgeelee.photos.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class AppLanguageTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test
    fun englishIsTheDefaultAndUnsupportedLanguagesFallBackToIt() {
        val english = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
            setLocales(LocaleList(Locale.ENGLISH))
        })
        val unsupported = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
            setLocales(LocaleList(Locale.forLanguageTag("fr-FR")))
        })
        assertEquals("Lightrove", english.getString(R.string.app_name))
        assertEquals("Search", english.getString(R.string.ui_nav_search))
        assertEquals("Search", unsupported.getString(R.string.ui_nav_search))
        assertEquals("Lightrove", unsupported.getString(R.string.app_name))
    }

    @Test
    @SdkSuppress(minSdkVersion = 33)
    fun systemAppLanguageSettingsAdvertiseExactlyTheTranslatedLanguages() {
        val config = LocaleConfig(context)
        assertEquals(LocaleConfig.STATUS_SUCCESS, config.status)
        val tags = requireNotNull(config.supportedLocales)
        assertEquals(AppLanguage.supportedTags.toSet(), (0 until tags.size()).map { tags[it].toLanguageTag() }.toSet())
    }

    @Test
    @SdkSuppress(minSdkVersion = 33)
    fun changingLanguageRecreatesActivityAndRetainsTheSearch() = withActivity { scenario ->
        val query = "庭園 café फ़ोटो"
        scenario.onActivity { activity ->
            ViewModelProvider(activity)[PhotosViewModel::class.java].apply {
                queryChanged(query)
                showError(runtimeText(RuntimeMessage.MEDIA_REQUIRED).encode())
            }
            AppLanguage.select("zh-CN")
        }
        awaitLocaleAppliedAndStored(scenario, "zh-CN")
        awaitActivity(scenario) { activity ->
            ViewModelProvider(activity)[PhotosViewModel::class.java].state.value.error ==
                activity.getString(R.string.runtime_media_required)
        }
        scenario.recreate()
        awaitLocaleAppliedAndStored(scenario, "zh-CN")
        scenario.onActivity { activity ->
            assertEquals(query, ViewModelProvider(activity)[PhotosViewModel::class.java].state.value.query)
            assertEquals("zh-CN", AppLanguage.selectedTag(activity))
            assertEquals("搜索", AppLanguage.localizedContext(activity.applicationContext).getString(R.string.ui_nav_search))
            AppLanguage.select("en")
        }
        awaitLocaleAppliedAndStored(scenario, "en")
        awaitActivity(scenario) { activity ->
            ViewModelProvider(activity)[PhotosViewModel::class.java].state.value.error ==
                activity.getString(R.string.runtime_media_required)
        }
        scenario.onActivity { assertEquals(query, ViewModelProvider(it)[PhotosViewModel::class.java].state.value.query) }
    }

    /** Per-app overrides belong to Android 13+; Android 12 now follows the device language. */
    @Test
    @SdkSuppress(minSdkVersion = 33)
    fun followingSystemRestoresActivityAndBackgroundResources() = withActivity { scenario ->
        val systemStrings = stringsForTag("")
        val alternateTag = if (stringsForTag("zh-CN") == systemStrings) "en" else "zh-CN"
        scenario.onActivity { AppLanguage.select(alternateTag) }
        awaitLocaleAppliedAndStored(scenario, alternateTag)
        scenario.onActivity { activity ->
            assertTrue("The override must differ from the system language before clearing it",
                probeStrings(activity) != systemStrings)
            AppLanguage.select("")
        }
        awaitLocaleAppliedAndStored(scenario, "")
        scenario.recreate()
        awaitLocaleAppliedAndStored(scenario, "")
        // This validates native app-locale storage and activity recreation, not process death.
    }

    @Test
    @SdkSuppress(minSdkVersion = 33)
    fun systemLanguageChangesReachTheAppAndCanReturnToSystemDefault() = withActivity { scenario ->
        val manager = context.getSystemService(LocaleManager::class.java)
        instrumentation.runOnMainSync { manager.applicationLocales = LocaleList.forLanguageTags("ja") }
        awaitLocaleAppliedAndStored(scenario, "ja")
        scenario.onActivity { activity ->
            assertEquals("ja", AppLanguage.selectedTag(activity))
            AppLanguage.select("")
        }
        awaitLocaleAppliedAndStored(scenario, "")
        assertTrue(manager.applicationLocales.isEmpty)
    }

    private fun withActivity(block: (ActivityScenario<MainActivity>) -> Unit) {
        val previous = LocaleManagerCompat.getApplicationLocales(context).toLanguageTags().substringBefore(',')
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try { block(scenario) }
            finally {
                // System settings can choose regional variants such as en-US as well.
                scenario.onActivity { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(previous)) }
                awaitLocaleAppliedAndStored(scenario, previous)
            }
        }
    }

    private fun probeStrings(probeContext: Context): List<String> = listOf(
        probeContext.getString(R.string.ui_nav_search),
        probeContext.getString(R.string.ui_language_system),
        probeContext.getString(R.string.runtime_media_required),
    )

    private fun stringsForTag(tag: String): List<String> {
        val locales = if (tag.isEmpty()) LocaleManagerCompat.getSystemLocales(context)
            else LocaleListCompat.forLanguageTags(tag)
        val expected = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
            ConfigurationCompat.setLocales(this, locales)
        })
        return probeStrings(expected)
    }

    private fun awaitLocaleAppliedAndStored(scenario: ActivityScenario<MainActivity>, tag: String) {
        val expectedStrings = stringsForTag(tag)
        awaitActivity(scenario) { activity ->
            // Verify Android's stored application locale as well as both resource contexts.
            val stored = LocaleManagerCompat.getApplicationLocales(activity.applicationContext).toLanguageTags()
            AppLanguage.selectedTag(activity) == tag && stored == tag &&
                probeStrings(activity) == expectedStrings &&
                probeStrings(AppLanguage.localizedContext(activity.applicationContext)) == expectedStrings
        }
    }

    private fun awaitActivity(scenario: ActivityScenario<MainActivity>, predicate: (MainActivity) -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < deadline) {
            instrumentation.waitForIdleSync()
            var matched = false
            scenario.onActivity { matched = predicate(it) }
            if (matched) return
            Thread.sleep(100)
        }
        throw AssertionError("Activity did not apply the requested app language")
    }
}
