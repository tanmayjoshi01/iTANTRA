package com.itantra.app

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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
 * Runs with RECORD_AUDIO pre-granted, so Start opens the real microphone
 * through speech-engine's AudioRecorder without the system dialog. The
 * denied-permission path is checked manually on the device (the system
 * permission dialog is outside this app's UI).
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
        composeRule.onNodeWithText(text(R.string.transcript_label, 0)).assertExists()
        composeRule.onNodeWithText(text(R.string.transcript_empty)).assertExists()
        composeRule.onNodeWithText(text(R.string.clear_transcript)).assertIsNotEnabled()
    }

    /**
     * Start opens the real microphone and loads the real
     * IndicConformerRecognizer. A test install has no model file, so the
     * recognizer's ModelNotFound must reach the UI and capture must stop.
     * Skipped if a model has been pushed for this app.
     */
    @Test
    fun start_withoutModelFile_reportsModelNotFound_andReturnsToIdle() {
        val modelFile = File(composeRule.activity.getExternalFilesDir(null), "indicconformer_hi.onnx")
        assumeFalse("model present; this test covers the missing-model path", modelFile.exists())

        composeRule.onNodeWithText(text(R.string.start)).performClick()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("STT model file not found", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(status(R.string.status_idle)).assertExists()
        composeRule.onNodeWithText(text(R.string.start)).assertIsEnabled()
        composeRule.onNodeWithText(text(R.string.stop)).assertIsNotEnabled()
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
