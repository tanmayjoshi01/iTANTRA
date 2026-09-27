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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
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
                    onClearTranscript = controller::clearTranscript,
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
private fun SpeechScreen(
    state: SpeechState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onClearTranscript: () -> Unit,
) {
    val transcriptListState = rememberLazyListState()
    // Keep the newest utterance in view as entries are appended.
    LaunchedEffect(state.transcript.size) {
        if (state.transcript.isNotEmpty()) transcriptListState.animateScrollToItem(state.transcript.lastIndex)
    }
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
            if (state.isLoadingModel) {
                Text(stringResource(R.string.loading_model))
            }
            state.error?.let { error ->
                Text(errorText(error), color = MaterialTheme.colorScheme.error)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.transcript_label, state.transcript.size),
                    style = MaterialTheme.typography.titleMedium,
                )
                // Only when idle, so an in-flight result cannot land in the new session.
                OutlinedButton(
                    onClick = onClearTranscript,
                    enabled = state.transcript.isNotEmpty() && !state.isListening && !state.isRecognizing,
                ) {
                    Text(stringResource(R.string.clear_transcript))
                }
            }
            if (state.transcript.isEmpty()) {
                Text(stringResource(R.string.transcript_empty))
            }
            LazyColumn(
                state = transcriptListState,
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(state.transcript) { index, entry ->
                    Column {
                        Text(
                            stringResource(
                                R.string.transcript_entry,
                                index + 1,
                                entry.result.text.ifBlank { stringResource(R.string.empty_result) },
                            ),
                        )
                        Text(
                            stringResource(
                                R.string.transcript_entry_timing,
                                entry.result.inferenceTimeMs,
                                entry.audioDurationMs,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
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
