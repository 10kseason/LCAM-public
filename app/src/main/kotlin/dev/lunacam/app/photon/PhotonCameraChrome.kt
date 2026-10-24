package dev.lunacam.app.photon

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

val PhotonCameraColors = darkColorScheme(
    primary = Color(0xFFE0E7EB),
    secondaryContainer = Color(0xFF303A40),
    onSecondaryContainer = Color.White,
    background = Color(0xFF080A0C),
    surface = Color(0xFF111518),
    onSurface = Color(0xFFF1F3F5)
)

/** Keep the capture target identical across pipelines, including its accessibility contract. */
@Composable
fun PhotonCameraShutter(enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val color = if (enabled) Color.White else Color(0xFF667078)
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.size(80.dp).testTag("camera-shutter").semantics {
            contentDescription = "사진 촬영"
            role = Role.Button
        },
        shape = CircleShape,
        contentPadding = PaddingValues(6.dp),
        border = BorderStroke(2.dp, color),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent
        )
    ) {
        Box(Modifier.fillMaxSize().background(color, CircleShape))
    }
}

@Composable
fun PhotonCameraModeBar(
    currentMode: String,
    onAuto: (() -> Unit)?,
    onRawAuto: (() -> Unit)?,
    onRaw: (() -> Unit)?,
    onNight: (() -> Unit)?,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        listOf("AUTO" to onAuto, "RAW AUTO" to onRawAuto, "RAW" to onRaw, "나이트" to onNight)
            .forEach { (mode, action) ->
                if (mode == currentMode || action != null) {
                    FilterChip(
                        selected = mode == currentMode,
                        onClick = { if (mode != currentMode) action?.invoke() },
                        enabled = enabled,
                        modifier = Modifier.semantics { contentDescription = "$mode 모드" },
                        label = { Text(mode, maxLines = 1) }
                    )
                }
            }
    }
}
