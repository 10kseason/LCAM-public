package dev.lunacam.app

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.lunacam.app.photon.PhotonCameraController
import dev.lunacam.app.photon.PhotonCameraScreen
import dev.lunacam.app.photon.night.SerNightCameraScreen
import dev.lunacam.app.photon.night.SerNightViewModel
import dev.lunacam.app.photon.raw.RawCameraScreen
import dev.lunacam.app.photon.raw.RawCameraViewModel
import dev.lunacam.feature.diagnostics.CameraPermissionUiState

enum class LunaCamScreen {
    AUTO,
    RAW,
    NIGHT,
    RAW_AUTO,

    // Retain serialized names so an OS-restored pre-0.12 task can safely open AUTO.
    DIAGNOSTICS,
    PREVIEW,
    PHOTON_DIAGNOSTICS
}

/** Camera-first entry. Diagnostic tools are retained in source, outside product navigation. */
@Composable
fun LunaCamApp(
    permissionState: CameraPermissionUiState,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
    onExit: () -> Unit
) {
    var screen by rememberSaveable { mutableStateOf(LunaCamScreen.RAW_AUTO) }
    val raw: RawCameraViewModel = viewModel()
    val application = LocalContext.current.applicationContext as Application
    val rawAuto: RawCameraViewModel = viewModel(
        key = "rawAuto",
        factory = viewModelFactory {
            initializer { RawCameraViewModel(application, true) }
        }
    )
    val auto: PhotonCameraController = viewModel()
    val night: SerNightViewModel = viewModel()
    fun hasUnfinishedWork() =
        raw.busy || raw.processingCount > 0 || auto.saving || auto.pendingCount > 0 ||
            night.busy || night.processingCount > 0 || rawAuto.busy || rawAuto.processingCount > 0
    val unfinished = hasUnfinishedWork()
    var exitRequested by rememberSaveable { mutableStateOf(false) }
    // Read live state in the event too; a capture may have started since the last composition.
    val requestExit = { if (hasUnfinishedWork()) exitRequested = true else onExit() }
    if (exitRequested) {
        CameraExitDialog(
            unfinished,
            { exitRequested = false },
            { if (!hasUnfinishedWork()) onExit() },
            "RAW: ${raw.message}\nAUTO: ${auto.message}\nRAW AUTO: ${rawAuto.message}\n나이트: ${night.message}"
        )
    }
    val granted = permissionState == CameraPermissionUiState.GRANTED
    BackHandler(enabled = !granted) { requestExit() }
    LaunchedEffect(granted, screen, rawAuto.unsupported) {
        if (granted && screen == LunaCamScreen.RAW_AUTO && rawAuto.unsupported) {
            screen = LunaCamScreen.AUTO
        }
    }
    // Do not compose a camera owner until permission is granted. Revocation disposes it too.
    if (!granted) {
        MaterialTheme {
            Surface(Modifier.fillMaxSize()) {
                Column(
                    Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("PHOTON · RAW AUTO", style = MaterialTheme.typography.headlineMedium)
                    Text("촬영을 시작하려면 카메라 권한이 필요합니다.", Modifier.padding(vertical = 16.dp))
                    if (permissionState == CameraPermissionUiState.SETTINGS_REQUIRED) {
                        Button(onClick = onOpenSettings) { Text("설정에서 카메라 권한 허용") }
                    } else {
                        Button(onClick = onRequestPermission) { Text("카메라 권한 허용") }
                    }
                }
            }
        }
        return
    }
    when (screen) {
        LunaCamScreen.AUTO, LunaCamScreen.DIAGNOSTICS,
        LunaCamScreen.PREVIEW, LunaCamScreen.PHOTON_DIAGNOSTICS -> PhotonCameraScreen(
            onBack = requestExit,
            controller = auto,
            onNight = { screen = LunaCamScreen.NIGHT },
            onRaw = { screen = LunaCamScreen.RAW },
            onRawAuto = { screen = LunaCamScreen.RAW_AUTO }
        )
        LunaCamScreen.RAW -> RawCameraScreen(
            onBack = { screen = LunaCamScreen.AUTO },
            model = raw,
            onRawAuto = { screen = LunaCamScreen.RAW_AUTO },
            onNight = { screen = LunaCamScreen.NIGHT },
            onExit = requestExit
        )
        LunaCamScreen.RAW_AUTO -> RawCameraScreen(
            onBack = {
                screen = LunaCamScreen.AUTO
            },
            model = rawAuto,
            onRaw = { screen = LunaCamScreen.RAW },
            onNight = { screen = LunaCamScreen.NIGHT },
            onExit = requestExit
        )
        LunaCamScreen.NIGHT -> SerNightCameraScreen(
            onBack = {
                screen = LunaCamScreen.AUTO
            },
            model = night,
            onRaw = { screen = LunaCamScreen.RAW },
            onRawAuto = { screen = LunaCamScreen.RAW_AUTO },
            onExit = requestExit
        )
    }
}

/** No discard action: an intentional app exit cannot cancel accepted in-memory RAW work. */
@Composable
internal fun CameraExitDialog(
    unfinished: Boolean,
    onCancel: () -> Unit,
    onExit: () -> Unit,
    status: String = ""
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(if (unfinished) "촬영·저장 작업이 남아 있습니다" else "촬영·저장 작업이 끝났습니다") },
        text = { Text("작업이 끝나면 저장 결과를 확인한 뒤 닫아 주세요.\n$status") },
        confirmButton = {
            TextButton(onClick = {
                if (!unfinished) onExit()
            }, enabled = !unfinished) {
                Text(if (unfinished) "작업 처리 중…" else "결과 확인 · 닫기")
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("계속 촬영") } }
    )
}
