package com.mcgeelee.photos.localization

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/** Lets Android own the language picker and its saved application locale. */
object AppLanguageSettings {
    internal fun intents(packageName: String, sdkInt: Int = Build.VERSION.SDK_INT): List<Intent> {
        val packageUri = Uri.fromParts("package", packageName, null)
        return if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
            listOf(
                Intent(Settings.ACTION_APP_LOCALE_SETTINGS, packageUri),
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri),
                Intent(Settings.ACTION_SETTINGS),
            )
        } else {
            // Android 12 has no system picker for an individual app's language.
            listOf(Intent(Settings.ACTION_LOCALE_SETTINGS), Intent(Settings.ACTION_SETTINGS))
        }
    }

    fun open(activity: Activity): Boolean {
        for (intent in intents(activity.packageName)) {
            try {
                activity.startActivity(intent)
                return true
            } catch (_: ActivityNotFoundException) {
                // Some device vendors do not expose the dedicated settings activity.
            } catch (_: SecurityException) {
                // A vendor settings activity may exist without accepting external callers.
            }
        }
        return false
    }
}
