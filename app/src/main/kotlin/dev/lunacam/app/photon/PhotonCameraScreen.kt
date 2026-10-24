package dev.lunacam.app.photon

import android.content.res.Configuration
import android.view.OrientationEventListener
import androidx.activity.compose.BackHandler
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.Locale

@Composable
fun PhotonCameraScreen(
    onBack: () -> Unit,
    controller: PhotonCameraController = viewModel(),
    onNight: (() -> Unit)? = null,
    onRaw: (() -> Unit)? = null,
    onRawAuto: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var showOptions by rememberSaveable { mutableStateOf(false) }
    var showExposure by rememberSaveable { mutableStateOf(false) }
    val viewer =
        remember(context.applicationContext) { PhotonPhotoViewer(context.applicationContext) }
    val view = remember(context) {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
            setOnTouchListener(
                PhotonPreviewTouchListener(
                    context,
                    controller::pinchZoom,
                    onHold = { x, y -> controller.focus(this, x, y, hold = true) }
                ) { x, y ->
                    controller.focus(this, x, y)
                    showExposure = controller.supportsExposure
                }
            )
        }
    }
    // Framing only resizes the PreviewView. Keep the session and orientation observer alive.
    DisposableEffect(owner, view, controller.retryKey) {
        controller.bind(owner, view)
        val orientation = object : OrientationEventListener(context) {
            override fun onOrientationChanged(value: Int) {
                captureRotation(value)?.let(controller::setRotation)
            }
        }
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> orientation.enable()
                Lifecycle.Event.ON_PAUSE -> orientation.disable()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(lifecycleObserver)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) orientation.enable()
        onDispose {
            orientation.disable()
            owner.lifecycle.removeObserver(lifecycleObserver)
            controller.release()
        }
    }
    BackHandler { if (!controller.saving) onBack() }
    MaterialTheme(colorScheme = PhotonCameraColors) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            val landscape =
                LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
            PhotonCameraLayout(
                landscape = landscape,
                preview = { modifier ->
                    CameraViewfinder(
                        controller,
                        view,
                        landscape,
                        showExposure,
                        { showExposure = false },
                        modifier
                    )
                },
                modes = {
                    PhotonCameraModeBar("AUTO", null, onRawAuto, onRaw, onNight, !controller.saving)
                },
                controls = {
                    CameraHeader(controller, onBack, { showExposure = !showExposure }) {
                        showOptions = true
                    }
                    CameraLenses(controller)
                    CameraRatios(controller)
                    CameraStatus(controller)
                },
                recent = {
                    PhotonRecentPhotoButton(controller.recentPhotoUri, viewer, Modifier.size(48.dp))
                },
                shutter = { CameraShutter(controller) { controller.takePhoto(owner) } }
            )
            if (showOptions) {
                CameraOptions(controller, viewer, onNight, onRaw, onRawAuto) {
                    showOptions = false
                }
            }
        }
    }
}

@Composable
private fun CameraHeader(
    controller: PhotonCameraController,
    onBack: () -> Unit,
    onExposure: () -> Unit,
    onOptions: () -> Unit
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp)
    ) {
        Text(
            "AUTO",
            Modifier.padding(horizontal = 8.dp).semantics {
                contentDescription = "현재 모드 AUTO"
            },
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1
        )
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            TextButton(onClick = onBack, enabled = !controller.saving) { Text("닫기") }
            if (!controller.ready) {
                TextButton(onClick = controller::retry, enabled = !controller.saving) {
                    Text("재연결")
                }
            }
            TextButton(
                onClick = onExposure,
                enabled = controller.supportsExposure && !controller.saving,
                modifier = Modifier.semantics { contentDescription = "밝기 조절" }
            ) { Text("EV") }
            TextButton(
                onClick = onOptions,
                modifier = Modifier.semantics { contentDescription = "보정 설정" }
            ) { Text("보정 설정") }
        }
    }
}

@Composable
private fun CameraRatios(controller: PhotonCameraController) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        PhotonAspectRatio.entries.forEach { ratio ->
            val label = ratio.label
            FilterChip(
                selected = controller.aspectRatio == ratio,
                onClick = { controller.selectAspectRatio(ratio) },
                enabled = !controller.saving,
                modifier = Modifier.weight(1f).semantics {
                    contentDescription = "사진 비율 $label"
                },
                label = {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(label, maxLines = 1)
                    }
                }
            )
        }
    }
}

@Composable
private fun CameraViewfinder(
    controller: PhotonCameraController,
    view: PreviewView,
    landscape: Boolean,
    showExposure: Boolean,
    onHideExposure: () -> Unit,
    modifier: Modifier
) {
    BoxWithConstraints(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        val ratio = controller.aspectRatio.previewRatio(landscape)
        val width = minOf(maxWidth, maxHeight * ratio)
        val height = width / ratio
        // Fit the requested frame inside the space; CameraX then fills/crops this exact frame.
        Box(Modifier.size(width, height).clipToBounds().testTag("camera-preview")) {
            AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
            PhotonViewfinderAssist(
                controller.gridEnabled,
                controller.levelEnabled,
                Modifier.fillMaxSize()
            )
            CameraFocusRing(controller.focusFeedback)
            if (controller.histogramEnabled) {
                PhotonHistogramOverlay(
                    controller.histogram,
                    controller.histogramUnavailable,
                    Modifier.align(Alignment.TopStart).padding(8.dp)
                )
            }
            if (controller.focusLocked) {
                Surface(
                    modifier = Modifier.align(Alignment.TopCenter).padding(8.dp),
                    color = Color.Black.copy(alpha = 0.72f),
                    shape = MaterialTheme.shapes.small
                ) {
                    TextButton(onClick = controller::clearFocusLock) { Text("초점 유지 해제") }
                }
            }
            if (showExposure && controller.supportsExposure) {
                Surface(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp).fillMaxWidth(),
                    color = Color.Black.copy(alpha = 0.78f),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Column(Modifier.padding(horizontal = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                exposureLabel(controller),
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.labelLarge
                            )
                            TextButton(onClick = {
                                controller.adjustExposureEv(0f)
                            }) { Text("초기화") }
                            TextButton(onClick = onHideExposure) { Text("닫기") }
                        }
                        ExposureSlider(controller)
                    }
                }
            }
        }
    }
}

@Composable
private fun CameraFocusRing(feedback: PhotonFocusFeedback?) {
    if (feedback == null) return
    val color = when (feedback.status) {
        PhotonFocusStatus.FOCUSING -> Color.White
        PhotonFocusStatus.FOCUSED -> Color(0xFF9FE3B1)
        PhotonFocusStatus.FAILED -> Color(0xFFFF9B91)
        PhotonFocusStatus.LOCKED -> Color(0xFFFFDA85)
    }
    Canvas(Modifier.fillMaxSize()) {
        val side = 56.dp.toPx().coerceAtMost(size.minDimension)
        val left = (size.width * feedback.xFraction - side / 2).coerceIn(0f, size.width - side)
        val top = (size.height * feedback.yFraction - side / 2).coerceIn(0f, size.height - side)
        drawRoundRect(
            color,
            Offset(left, top),
            Size(side, side),
            CornerRadius(5.dp.toPx()),
            style = Stroke(1.5.dp.toPx())
        )
    }
}

private fun exposureLabel(controller: PhotonCameraController): String =
    String.format(Locale.ROOT, "밝기 %+.1f EV", controller.exposureEv)

@Composable
private fun ExposureSlider(controller: PhotonCameraController) {
    Slider(
        value = controller.exposureEv.coerceIn(controller.minExposureEv, controller.maxExposureEv),
        onValueChange = controller::adjustExposureEv,
        valueRange = controller.minExposureEv..controller.maxExposureEv,
        enabled = controller.ready && !controller.saving,
        modifier = Modifier.semantics { contentDescription = "노출 보정 조절" }
    )
}

@Composable
private fun CameraLenses(controller: PhotonCameraController) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            controller.lenses.forEach { lens ->
                FilterChip(
                    selected = controller.selectedId == lens.id,
                    onClick = { controller.select(lens.id) },
                    enabled = !controller.saving,
                    label = { Text(lens.label, maxLines = 1) }
                )
            }
        }
        if (controller.canZoom) {
            Text(
                String.format(Locale.ROOT, "%.1f×", controller.zoomRatio),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { contentDescription = "현재 줌 배율" }
            )
        }
    }
}

@Composable
private fun CameraShutter(controller: PhotonCameraController, onClick: () -> Unit) {
    Column(
        Modifier.padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        PhotonCameraShutter(controller.canCapture, onClick)
        Text(
            when {
                controller.saving -> "촬영 중"
                controller.pendingCount >= 2 -> "처리 대기"
                else -> "사진"
            },
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1
        )
    }
}

@Composable
private fun CameraStatus(controller: PhotonCameraController) {
    Row(
        Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val status = if (controller.pendingCount > 0) {
            "처리 중 ${controller.pendingCount}/2 · ${controller.message}"
        } else {
            controller.message
        }
        Text(
            status,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (controller.recoverableCount > 0) {
            TextButton(
                onClick = controller::retryPendingSave,
                enabled = !controller.saving && controller.pendingCount < 2
            ) { Text("저장 재시도 (${controller.recoverableCount})") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CameraOptions(
    controller: PhotonCameraController,
    viewer: PhotonPhotoViewer,
    onNight: (() -> Unit)?,
    onRaw: (() -> Unit)?,
    onRawAuto: (() -> Unit)?,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("촬영 설정", style = MaterialTheme.typography.titleLarge)
            if (onRawAuto != null) {
                TextButton(onClick = {
                    onDismiss()
                    onRawAuto()
                }, enabled = !controller.saving) { Text("RAW AUTO 열기 · 하늘·거리 (실험)") }
            }
            if (onRaw != null) {
                TextButton(onClick = {
                    onDismiss()
                    onRaw()
                }, enabled = !controller.saving) { Text("RAW 촬영 열기") }
            }
            if (onNight != null) {
                TextButton(onClick = {
                    onDismiss()
                    onNight()
                }, enabled = !controller.saving) {
                    Text("SER 나이트샷 열기 · 12.48MP")
                }
            }
            if (controller.supportsExposure) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(exposureLabel(controller), Modifier.weight(1f))
                    TextButton(onClick = { controller.adjustExposureEv(0f) }) { Text("초기화") }
                }
                ExposureSlider(controller)
            }
            Text("화면을 누르면 초점, 길게 누르면 초점을 유지합니다.", style = MaterialTheme.typography.bodySmall)
            CameraOption(
                "격자",
                "삼분할 구도를 프리뷰에 표시합니다.",
                "프리뷰 격자",
                controller.gridEnabled,
                true,
                controller::setGrid
            )
            CameraOption(
                "밝기 히스토그램",
                "프리뷰 밝기 분포입니다. 최종 보정 사진이나 RAW 노출과는 다를 수 있습니다.",
                "프리뷰 히스토그램",
                controller.histogramEnabled,
                !controller.saving,
                controller::setHistogram
            )
            if (controller.histogramUnavailable) {
                Text("이 렌즈에서는 히스토그램을 사용할 수 없어 일반 촬영으로 복구했습니다.")
            }
            CameraOption(
                "수평계",
                "휴대폰의 기울기를 프리뷰에 표시합니다.",
                "프리뷰 수평계",
                controller.levelEnabled,
                true,
                controller::setLevel
            )
            PhotonPhotoViewerSettings(viewer)
            Text("선택한 보정은 저장 사진에 적용됩니다.")
            CameraOption(
                "자연스러운 질감",
                "과한 보정을 줄이고 질감을 보호합니다.",
                "PHOTON 질감 보호 후처리",
                controller.postProcessingEnabled,
                !controller.saving,
                controller::setPostProcessing
            )
            CameraOption(
                "디테일 강화",
                "질감 보호를 켠 상태에서 잡음과 윤곽을 정리합니다.",
                "PHOTON 디테일 강화",
                controller.detailEnabled,
                controller.postProcessingEnabled && !controller.saving,
                controller::setDetail
            )
            CameraOption(
                "HDR",
                "밝은 부분과 어두운 부분을 함께 담습니다. 결과는 한 장 저장됩니다.",
                "PHOTON HDR",
                controller.hdrEnabled,
                !controller.saving,
                controller::setHdr
            )
            if (controller.canZoom) {
                Text(String.format(Locale.ROOT, "줌 %.1f×", controller.zoomRatio))
                Slider(
                    value = controller.linearZoom,
                    onValueChange = controller::zoom,
                    enabled = controller.ready && !controller.saving,
                    modifier = Modifier.semantics { contentDescription = "줌 배율 조절" }
                )
                Text("미리보기에서 두 손가락으로도 조절할 수 있습니다.")
            }
            controller.lastShutterReadyMs?.let {
                Text("최근 다음 촬영 대기 ${it}ms", style = MaterialTheme.typography.bodySmall)
            }
            Text(controller.message, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text("완료")
            }
        }
    }
}

@Composable
private fun CameraOption(
    title: String,
    description: String,
    accessibilityLabel: String,
    checked: Boolean,
    enabled: Boolean,
    onChanged: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onChanged,
            enabled = enabled,
            modifier = Modifier.semantics { contentDescription = accessibilityLabel }
        )
    }
}
