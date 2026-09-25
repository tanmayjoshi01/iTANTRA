package com.itantra.app

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityInstrumentedTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun launch_rendersFoundationScreen() {
        val activity = composeRule.activity
        composeRule.onNodeWithText(activity.getString(R.string.app_name)).assertExists()
        composeRule.onNodeWithText(activity.getString(R.string.speech_engine_status)).assertExists()
    }

    /**
     * RECORD_AUDIO is declared only in speech-engine's manifest; its presence
     * in the installed app shows the speech-engine dependency is wired in and
     * its manifest merged. It does not check that the permission is granted.
     */
    @Test
    fun installedApp_declaresSpeechEngineMicrophonePermission() {
        val context = composeRule.activity
        @Suppress("DEPRECATION")
        val requested = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            .orEmpty()
        assertTrue(Manifest.permission.RECORD_AUDIO in requested)
    }
}
