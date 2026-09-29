package com.itantra.app

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import java.io.File

/**
 * Runs with RECORD_AUDIO pre-granted. The denied-permission path is checked
 * manually on the device (the system permission dialog is outside this
 * app's UI).
 */
@RunWith(AndroidJUnit4::class)
class MainActivityInstrumentedTest {

    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: TestRule = RuleChain
        .outerRule(GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO))
        .around(composeRule)

    private fun text(id: Int, vararg args: Any) = composeRule.activity.getString(id, *args)

    @Test
    fun launch_rendersDemoScreen() {
        composeRule.onNodeWithText(text(R.string.app_name)).assertExists()
        composeRule.onNodeWithText(text(R.string.app_subtitle)).assertExists()
        composeRule.onNodeWithText(text(R.string.tab_speak_send_ui)).assertExists()
        composeRule.onNodeWithText(text(R.string.tab_receive_ui)).assertExists()
        composeRule.onNodeWithText(text(R.string.hold_to_talk)).assertExists()
        composeRule.onNodeWithText(text(R.string.stop)).assertIsNotEnabled()
    }

    /**
     * A test install has no model file: speech input is disabled with a
     * friendly explanation instead of failing at Start. Skipped if a model
     * has been pushed for this app.
     */
    @Test
    fun withoutModelFile_speechInputDisabledWithFriendlyMessage() {
        val modelFile = File(composeRule.activity.getExternalFilesDir(null), "indicconformer_hi.onnx")
        assumeFalse("model present; this test covers the missing-model path", modelFile.exists())

        composeRule.onNodeWithText(text(R.string.ptt_unavailable)).assertExists()
        composeRule.onNodeWithText(text(R.string.start)).assertIsNotEnabled()
    }

    @Test
    fun receiveTab_showsReceiverAndEmptyTimeline() {
        composeRule.onNodeWithText(text(R.string.tab_receive_ui)).performClick()
        composeRule.onNodeWithText(text(R.string.no_messages_yet)).assertExists()
        composeRule.onNodeWithText(text(R.string.hearing_original_caption)).assertExists()
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
