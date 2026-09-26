package com.itantra.app

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith

/**
 * Runs with RECORD_AUDIO pre-granted, so Start opens the real microphone
 * through speech-engine's AudioRecorder without the system dialog. The
 * denied-permission path is checked manually on the device (the system
 * permission dialog is outside this app's UI). No model file is involved:
 * the recognizer is only created once an utterance finishes.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityInstrumentedTest {

    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: TestRule = RuleChain
        .outerRule(GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO))
        .around(composeRule)

    private fun text(id: Int, vararg args: Any) = composeRule.activity.getString(id, *args)

    private fun status(id: Int) = text(R.string.status_label, text(id))

    @Test
    fun launch_rendersSpeechScreenInIdleState() {
        composeRule.onNodeWithText(text(R.string.app_name)).assertExists()
        composeRule.onNodeWithText(status(R.string.status_idle)).assertExists()
        composeRule.onNodeWithText(text(R.string.start)).assertIsEnabled()
        composeRule.onNodeWithText(text(R.string.stop)).assertIsNotEnabled()
        composeRule.onNodeWithText(text(R.string.last_text_label, text(R.string.none))).assertExists()
    }

    @Test
    fun startThenStop_togglesListeningState() {
        composeRule.onNodeWithText(text(R.string.start)).performClick()
        composeRule.onNodeWithText(status(R.string.status_listening)).assertExists()
        composeRule.onNodeWithText(text(R.string.start)).assertIsNotEnabled()
        composeRule.onNodeWithText(text(R.string.stop)).assertIsEnabled()

        composeRule.onNodeWithText(text(R.string.stop)).performClick()
        composeRule.onNodeWithText(status(R.string.status_idle)).assertExists()
        composeRule.onNodeWithText(text(R.string.start)).assertIsEnabled()
    }

    /**
     * RECORD_AUDIO is declared only in speech-engine's manifest; its presence
     * in the installed app shows the speech-engine dependency is wired in and
     * its manifest merged.
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
