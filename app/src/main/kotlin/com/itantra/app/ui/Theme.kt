package com.itantra.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** iTANTRA palette: indigo for normal use, red reserved for alerts, green for success. */
object ItantraColors {
    val Primary = Color(0xFF3F3DB5)
    val PrimaryDark = Color(0xFF2C2A8C)
    val PrimaryContainer = Color(0xFFE4E3FF)
    val Alert = Color(0xFFC62828)
    val AlertDark = Color(0xFF9E1B1B)
    val AlertContainer = Color(0xFFFFE6E3)
    val Success = Color(0xFF2E7D32)
    val SuccessContainer = Color(0xFFE2F3E3)
    val Pending = Color(0xFFB26A00)
    val Muted = Color(0xFF6B6F80)
    val Background = Color(0xFFF5F5FA)
    val Surface = Color(0xFFFFFFFF)
    val SurfaceVariant = Color(0xFFECECF4)
}

@Composable
fun ItantraTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = ItantraColors.Primary,
            onPrimary = Color.White,
            primaryContainer = ItantraColors.PrimaryContainer,
            onPrimaryContainer = Color(0xFF1B1A5C),
            secondary = ItantraColors.Muted,
            background = ItantraColors.Background,
            surface = ItantraColors.Surface,
            surfaceVariant = ItantraColors.SurfaceVariant,
            onSurfaceVariant = ItantraColors.Muted,
            error = ItantraColors.Alert,
            errorContainer = ItantraColors.AlertContainer,
        ),
        content = content,
    )
}
