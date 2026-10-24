package dev.lunacam.app.photon

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
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

/** Reserve the viewport before measuring controls, so text/settings cannot shrink it. */
@Composable
fun PhotonCameraLayout(
    landscape: Boolean,
    preview: @Composable (Modifier) -> Unit,
    modes: @Composable () -> Unit,
    controls: @Composable () -> Unit,
    recent: @Composable () -> Unit,
    shutter: @Composable () -> Unit
) {
    val viewport = Modifier.fillMaxSize().testTag("camera-preview-area").semantics {
        contentDescription = "미리보기 영역"
    }
    val screen = Modifier.fillMaxSize().safeDrawingPadding().semantics {
        contentDescription = "카메라 화면 영역"
    }
    if (landscape) {
        Row(screen) {
            Box(Modifier.weight(0.72f).fillMaxSize()) { preview(viewport) }
            Column(Modifier.weight(0.28f).fillMaxSize()) {
                modes()
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                    controls()
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    recent()
                    shutter()
                }
            }
        }
    } else {
        Column(screen) {
            Box(Modifier.weight(0.72f).fillMaxWidth()) { preview(viewport) }
            Column(Modifier.weight(0.28f).fillMaxWidth()) {
                modes()
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    Column(
                        Modifier.weight(1f).fillMaxSize().verticalScroll(rememberScrollState())
                    ) {
                        controls()
                        recent()
                    }
                    Column(
                        Modifier.width(96.dp).fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        shutter()
                    }
                }
            }
        }
    }
}

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
            .semantics { contentDescription = "촬영 모드 선택" }
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
