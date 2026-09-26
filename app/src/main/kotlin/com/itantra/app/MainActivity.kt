package com.itantra.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.itantra.app.speech.SpeechError
import com.itantra.app.speech.SpeechState

/**
 * Single entry point of the application. Owns the RECORD_AUDIO runtime
 * permission request (the app layer's job; speech-engine only declares the
 * permission) and renders [SpeechState]. Holds no speech logic itself.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: SpeechViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val controller = viewModel.controller
        setContent {
            val state by controller.state.collectAsState()
            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { granted ->
                if (granted) controller.start() else controller.onPermissionDenied()
            }
            MaterialTheme {
                SpeechScreen(
                    state = state,
                    onStart = {
                        if (hasMicrophonePermission()) {
                            controller.start()
                        } else {
                            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    onStop = controller::stop,
                )
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Android does not deliver microphone audio to backgrounded apps
        // without a foreground service, so stop listening when not visible.
        // A rotation keeps listening (the ViewModel survives it).
        if (!isChangingConfigurations) viewModel.controller.stop()
    }

    private fun hasMicrophonePermission(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
}

@Composable
private fun SpeechScreen(state: SpeechState, onStart: () -> Unit, onStop: () -> Unit) {
    // Scaffold's content padding keeps text clear of system bars, which
    // Android 15+ draws over app content when targetSdk >= 35.
    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.status_label, stringResource(statusText(state))))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onStart, enabled = !state.isListening) {
                    Text(stringResource(R.string.start))
                }
                Button(onClick = onStop, enabled = state.isListening) {
                    Text(stringResource(R.string.stop))
                }
            }
            Text(stringResource(R.string.last_text_label, state.lastText ?: stringResource(R.string.none)))
            state.error?.let { error ->
                Text(errorText(error), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

private fun statusText(state: SpeechState): Int = when {
    state.isListening && state.isRecognizing -> R.string.status_listening_and_recognizing
    state.isListening -> R.string.status_listening
    state.isRecognizing -> R.string.status_recognizing
    else -> R.string.status_idle
}

@Composable
private fun errorText(error: SpeechError): String = when (error) {
    SpeechError.MicrophonePermissionDenied -> stringResource(R.string.error_permission_denied)
    is SpeechError.AudioCaptureFailed -> stringResource(R.string.error_audio_capture, error.message)
    is SpeechError.RecognitionFailed -> stringResource(R.string.error_recognition, error.message)
}
