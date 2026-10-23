package dev.lunacam.feature.camera

import android.view.Surface
import android.view.SurfaceView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lunacam.camera.camera2.preview.PreviewViewGeometry
import dev.lunacam.domain.preview.CameraFailure
import dev.lunacam.domain.preview.CameraFailureKind
import dev.lunacam.domain.preview.IntSize as PreviewIntSize
import dev.lunacam.domain.preview.PointD

/**
 * The P2 preview screen: viewfinder, tap focus indicator, and EV compensation.
 *
 * There is deliberately no shutter here. Capture starts in P3.
 */
@Composable
fun PreviewScreen(
    viewModel: CameraPreviewViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val surfaceProvider = remember { SurfaceHolderPreviewSurfaceProvider() }

    DisposableEffect(surfaceProvider, viewModel) {
        surfaceProvider.onSurfaceReady = { viewModel.onSurfaceProviderAvailable(surfaceProvider) }
        surfaceProvider.onSurfaceLost = { viewModel.onSurfaceLost() }
        onDispose {
            surfaceProvider.onSurfaceReady = null
            surfaceProvider.onSurfaceLost = null
        }
    }

    // The camera is released whenever the screen leaves the foreground so other apps can use it
    // (DESIGN.md section 5.6). A rotation does not pass through here as a stop and restart.
    LifecycleResumeEffect(viewModel) {
        viewModel.onForeground()
        onPauseOrDispose { viewModel.onBackground() }
    }

    Box(
        modifier =
        modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { size ->
                viewModel.onGeometryChanged(
                    PreviewViewGeometry(
                        viewSize = PreviewIntSize(size.width, size.height),
                        displayRotationDegrees = context.displayRotationDegrees()
                    )
                )
            }
            .pointerInput(viewModel) {
                detectTapGestures { offset ->
                    viewModel.onTap(PointD(offset.x.toDouble(), offset.y.toDouble()))
                }
            }
    ) {
        // The surface must exist before the camera opens, so it is laid out full size until the
        // preview reports its ratio. From then on the view carries exactly the ratio that
        // PreviewTransformFactory fitted, which is what keeps the picture undistorted and the tap
        // mapping honest.
        val readyAspectRatio = (state as? PreviewUiState.Ready)?.displayAspectRatio
        AndroidView(
            modifier =
            if (readyAspectRatio != null && readyAspectRatio > 0f) {
                Modifier.aspectRatio(readyAspectRatio).align(Alignment.Center)
            } else {
                Modifier.fillMaxSize()
            },
            factory = { viewContext ->
                SurfaceView(viewContext).apply { holder.addCallback(surfaceProvider) }
            }
        )

        FocusIndicator(state)
        StatusOverlay(state, onBack, Modifier.align(Alignment.TopStart))
        ExposureCompensationBar(
            state = state,
            onEvChange = viewModel::onExposureCompensationEvChanged,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

@Composable
private fun FocusIndicator(state: PreviewUiState) {
    val indicator = (state as? PreviewUiState.Ready)?.focusIndicator ?: return
    Canvas(modifier = Modifier.fillMaxSize()) {
        val half = FOCUS_INDICATOR_HALF_PX
        drawRect(
            color = Color.White,
            topLeft =
            Offset(
                (indicator.x - half).toFloat(),
                (indicator.y - half).toFloat()
            ),
            size = Size(half * 2f, half * 2f),
            style = Stroke(width = 3f)
        )
    }
}

@Composable
private fun StatusOverlay(state: PreviewUiState, onBack: () -> Unit, modifier: Modifier) {
    Column(modifier = modifier.padding(16.dp), verticalArrangement = Arrangement.Top) {
        Button(onClick = onBack) { Text(text = "진단으로 돌아가기") }
        val message = state.statusMessage()
        if (message != null) {
            Text(
                text = message,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 12.dp)
            )
        }
    }
}

@Composable
private fun ExposureCompensationBar(
    state: PreviewUiState,
    onEvChange: (Double) -> Unit,
    modifier: Modifier
) {
    val ready = state as? PreviewUiState.Ready ?: return
    val spec = ready.exposureCompensation
    if (!spec.isSupported) return

    val currentEv = spec.evForStep(ready.exposureCompensationStep)
    Column(
        modifier = modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "EV %+.1f".format(currentEv),
            color = Color.White,
            style = MaterialTheme.typography.titleMedium
        )
        Slider(
            value = currentEv.toFloat(),
            onValueChange = { requested -> onEvChange(requested.toDouble()) },
            valueRange = spec.minEv.toFloat()..spec.maxEv.toFloat(),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

private fun PreviewUiState.statusMessage(): String? = when (this) {
    is PreviewUiState.Idle -> null
    is PreviewUiState.Preparing -> "카메라를 준비하는 중입니다."
    is PreviewUiState.Ready -> null
    is PreviewUiState.Recovering -> "카메라를 복구하는 중입니다. (${attempt}회차)"
    is PreviewUiState.Blocked -> cause.blockedMessage()
}

private fun CameraFailure.blockedMessage(): String = when (kind) {
    CameraFailureKind.DISABLED ->
        "카메라가 비활성화되어 있습니다. 기기 정책이나 설정을 확인해 주세요."

    CameraFailureKind.IN_USE, CameraFailureKind.MAX_IN_USE, CameraFailureKind.DISCONNECTED ->
        "다른 앱이 카메라를 사용 중입니다. 해당 앱을 종료한 뒤 다시 시도해 주세요."

    CameraFailureKind.SERVICE ->
        "카메라 서비스에 문제가 발생했습니다. 앱을 다시 실행해 주세요."

    else -> "카메라를 열 수 없습니다. 앱을 다시 실행해 주세요."
}

private fun android.content.Context.displayRotationDegrees(): Int = when (display?.rotation) {
    Surface.ROTATION_90 -> 90
    Surface.ROTATION_180 -> 180
    Surface.ROTATION_270 -> 270
    else -> 0
}

private const val FOCUS_INDICATOR_HALF_PX = 60f
