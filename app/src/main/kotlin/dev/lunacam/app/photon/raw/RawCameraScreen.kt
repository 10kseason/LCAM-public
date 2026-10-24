package dev.lunacam.app.photon.raw

import android.content.Context
import android.content.res.Configuration
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.OrientationEventListener
import android.view.Surface as AndroidSurface
import android.view.TextureView
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.lunacam.app.photon.PhotonAspectRatio
import dev.lunacam.app.photon.PhotonCameraColors
import dev.lunacam.app.photon.PhotonCameraLayout
import dev.lunacam.app.photon.PhotonCameraModeBar
import dev.lunacam.app.photon.PhotonCameraShutter
import dev.lunacam.app.photon.PhotonPhotoViewer
import dev.lunacam.app.photon.PhotonPhotoViewerSettings
import dev.lunacam.app.photon.PhotonPreviewTouchListener
import dev.lunacam.app.photon.PhotonRecentPhotoButton
import dev.lunacam.app.photon.PhotonViewfinderAssist
import dev.lunacam.app.photon.TexturePreviewHistogram
import dev.lunacam.app.photon.captureRotation
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

private class RawPreviewView(context: Context) : TextureView(context) {
    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}

/** TextureView's zoom matrix can draw past its layout bounds unless the native host also clips. */
internal class RawPreviewContainer(context: Context, val preview: TextureView) :
    FrameLayout(context) {
    init {
        clipChildren = true
        clipToPadding = true
        addView(
            preview,
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        clipBounds = Rect(0, 0, width, height)
        preview.clipBounds = Rect(0, 0, width, height)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RawCameraScreen(
    onBack: () -> Unit,
    model: RawCameraViewModel = viewModel(),
    onRaw: (() -> Unit)? = null,
    onRawAuto: (() -> Unit)? = null,
    onNight: (() -> Unit)? = null,
    onExit: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var showOptions by rememberSaveable { mutableStateOf(false) }
    var showPro by rememberSaveable { mutableStateOf(false) }
    var showExposure by rememberSaveable { mutableStateOf(false) }
    var rotation by remember { mutableStateOf(AndroidSurface.ROTATION_0) }
    val viewer =
        remember(context.applicationContext) { PhotonPhotoViewer(context.applicationContext) }
    val view = remember(context, model) {
        RawPreviewView(context).apply {
            contentDescription = "RAW 미리보기 · 두 손가락 확대 · 터치 초점"
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> model.updateTransform(this) }
            setOnTouchListener(
                PhotonPreviewTouchListener(
                    context,
                    onScale = { model.pinch(it, this) },
                    onHold = { x, y -> model.focus(this, x, y) }
                ) { x, y ->
                    model.focus(this, x, y)
                    showExposure = model.capabilities.ev && !model.controls.manualExposure
                }
            )
        }
    }
    val previewContainer = remember(view) { RawPreviewContainer(context, view) }
    DisposableEffect(owner, view) {
        val displays = context.getSystemService(DisplayManager::class.java)
        val displayListener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
            override fun onDisplayChanged(displayId: Int) {
                // A 180-degree display rotation need not change this view's layout bounds.
                if (view.display?.displayId == displayId) model.updateTransform(view)
            }
        }
        val orientation = object : OrientationEventListener(context) {
            override fun onOrientationChanged(value: Int) {
                captureRotation(value)?.let { rotation = it }
            }
        }
        displays.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    rotation = view.display?.rotation ?: AndroidSurface.ROTATION_0
                    orientation.enable()
                    model.open(view)
                }
                Lifecycle.Event.ON_PAUSE -> {
                    orientation.disable()
                    model.closeCamera()
                }
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            rotation = view.display?.rotation ?: AndroidSurface.ROTATION_0
            orientation.enable()
            model.open(view)
        }
        onDispose {
            orientation.disable()
            displays.unregisterDisplayListener(displayListener)
            owner.lifecycle.removeObserver(observer)
            model.closeCamera()
        }
    }
    BackHandler { if (!model.busy) onBack() }
    MaterialTheme(colorScheme = PhotonCameraColors) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            PhotonCameraLayout(
                landscape = landscape,
                preview = { modifier ->
                    RawViewfinder(
                        model,
                        previewContainer,
                        landscape,
                        showExposure,
                        { showExposure = false },
                        modifier
                    )
                },
                modes = {
                    PhotonCameraModeBar(
                        currentMode = if (model.naturalAuto) "RAW AUTO" else "RAW",
                        onAuto = onBack,
                        onRawAuto = onRawAuto,
                        onRaw = onRaw,
                        onNight = onNight,
                        enabled = !model.busy
                    )
                },
                controls = {
                    RawHeader(model, onExit ?: onBack, { showExposure = !showExposure }) {
                        showOptions = true
                    }
                    RawLenses(model, view)
                    RawRatios(model)
                    RawStatus(model)
                },
                recent = {
                    PhotonRecentPhotoButton(
                        model.recentPhotoUri,
                        viewer,
                        Modifier.size(48.dp),
                        mimeType = model.recentPhotoMimeType
                    )
                },
                shutter = { RawShutter(model) { model.capture(rotation) } }
            )
            if (showOptions) {
                ModalBottomSheet(
                    onDismissRequest = { showOptions = false },
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                ) {
                    RawOptions(model, view, viewer, onPro = {
                        showOptions = false
                        showPro = true
                    }, onDone = { showOptions = false })
                }
            }
            if (showPro) {
                ModalBottomSheet(
                    onDismissRequest = { showPro = false },
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                ) { RawProControls(model) { showPro = false } }
            }
        }
    }
}

@Composable
private fun RawHeader(
    model: RawCameraViewModel,
    onExit: () -> Unit,
    onExposure: () -> Unit,
    onOptions: () -> Unit
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp)
    ) {
        Text(
            if (model.naturalAuto) "RAW AUTO" else "RAW",
            Modifier.padding(horizontal = 8.dp).semantics {
                contentDescription = "현재 모드 ${if (model.naturalAuto) "RAW AUTO" else "RAW"}"
            },
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1
        )
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            TextButton(onClick = onExit, enabled = !model.busy) { Text("닫기") }
            TextButton(
                onClick = onExposure,
                enabled =
                model.ready && model.capabilities.ev && !model.controls.manualExposure &&
                    !model.busy,
                modifier = Modifier.semantics { contentDescription = "밝기 조절" }
            ) { Text("EV") }
            TextButton(
                onClick = onOptions,
                modifier = Modifier.semantics {
                    contentDescription = "보정 설정"
                }
            ) {
                Text("보정 설정")
            }
        }
    }
}

@Composable
private fun RawRatios(model: RawCameraViewModel) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        PhotonAspectRatio.entries.forEach { ratio ->
            FilterChip(
                selected = model.aspectRatio == ratio,
                onClick = { model.setAspectRatio(ratio) },
                enabled = !model.busy,
                modifier = Modifier.weight(1f).semantics {
                    contentDescription =
                        "사진 비율 ${ratio.label}"
                },
                label = {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(ratio.label, maxLines = 1)
                    }
                }
            )
        }
    }
}

@Composable
private fun RawViewfinder(
    model: RawCameraViewModel,
    container: RawPreviewContainer,
    landscape: Boolean,
    showExposure: Boolean,
    onHideExposure: () -> Unit,
    modifier: Modifier
) {
    BoxWithConstraints(
        modifier.background(Color.Black).clipToBounds(),
        contentAlignment = Alignment.Center
    ) {
        val ratio = model.aspectRatio.previewRatio(landscape)
        val width = minOf(maxWidth, maxHeight * ratio)
        val height = width / ratio
        // The frame and native host both clip: digital zoom must not cover mode or shutter controls.
        Box(Modifier.size(width, height).clipToBounds().testTag("camera-preview")) {
            AndroidView(factory = { container }, modifier = Modifier.fillMaxSize().clipToBounds())
            PhotonViewfinderAssist(model.gridEnabled, model.levelEnabled, Modifier.fillMaxSize())
            TexturePreviewHistogram(
                container.preview,
                model.histogramEnabled && model.ready && !model.busy && !model.switching,
                Modifier.align(Alignment.TopStart).padding(8.dp)
            )
            model.focusPoint?.let { point ->
                Canvas(Modifier.fillMaxSize()) {
                    val side = 48.dp.toPx().coerceAtMost(size.minDimension)
                    drawRect(
                        Color.Yellow,
                        Offset(
                            (point.first * size.width - side / 2).coerceIn(0f, size.width - side),
                            (point.second * size.height - side / 2).coerceIn(0f, size.height - side)
                        ),
                        Size(side, side),
                        style = Stroke(2.dp.toPx())
                    )
                }
                Surface(
                    modifier = Modifier.align(Alignment.TopCenter).padding(8.dp),
                    color = Color.Black.copy(alpha = 0.72f),
                    shape = MaterialTheme.shapes.small
                ) {
                    if (model.controlsUpdating) {
                        Text(
                            model.focusStatus,
                            Modifier.padding(8.dp),
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    } else {
                        TextButton(
                            onClick = {
                                model.updateControls(model.controls.copy(focusDiopters = null))
                            },
                            enabled = !model.busy && !model.switching
                        ) { Text("초점 해제") }
                    }
                }
            }
            if (showExposure && model.capabilities.ev && !model.controls.manualExposure) {
                Surface(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp).fillMaxWidth(),
                    color = Color.Black.copy(alpha = 0.78f),
                    shape = MaterialTheme.shapes.medium
                ) { RawExposure(model, onHideExposure) }
            }
        }
    }
}

@Composable
private fun RawExposure(model: RawCameraViewModel, onDone: () -> Unit) {
    val support = model.capabilities
    Column(Modifier.padding(horizontal = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                String.format(Locale.ROOT, "%+.1f EV", model.controls.evIndex * support.evStep),
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge
            )
            TextButton(
                onClick = { model.updateControls(model.controls.copy(evIndex = 0)) },
                enabled = !model.busy && !model.switching
            ) { Text("초기화") }
            TextButton(onClick = onDone) { Text("닫기") }
        }
        Slider(
            value = model.controls.evIndex.toFloat(),
            onValueChange = {
                model.updateControls(model.controls.copy(evIndex = it.roundToInt()))
            },
            valueRange = support.minEvIndex.toFloat()..support.maxEvIndex.toFloat(),
            enabled = model.ready && !model.busy && !model.switching,
            modifier = Modifier.semantics { contentDescription = "RAW 노출 보정" }
        )
    }
}

@Composable
private fun RawLenses(model: RawCameraViewModel, view: TextureView) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (model.autoLens) {
            RawZoomSteps(model, view)
            Text("${zoomText(model.zoomRatio)}×", style = MaterialTheme.typography.labelLarge)
        } else {
            model.lenses.forEach { lens ->
                FilterChip(
                    selected = model.selectedId == lens.id,
                    onClick = { model.select(lens.id, view) },
                    enabled = !model.busy && !model.switching,
                    label = { Text(lens.label) }
                )
            }
        }
    }
}

@Composable
private fun RawZoomSteps(model: RawCameraViewModel, view: TextureView) {
    val enabled = model.ready && !model.busy && !model.controlsUpdating
    listOf(model.minimumZoomRatio, 1f, 2f, 3f, 5f, 8f)
        .filter {
            it >= model.minimumZoomRatio && it <= model.maximumZoomRatio
        }.distinct().forEach { ratio ->
            FilterChip(
                selected = abs(model.zoomRatio - ratio) < 0.04f,
                onClick = { model.setZoom(ratio, view) },
                enabled = enabled,
                label = { Text("${zoomText(ratio)}×") }
            )
        }
}

@Composable
private fun RawShutter(model: RawCameraViewModel, onClick: () -> Unit) {
    Column(
        Modifier.padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        PhotonCameraShutter(model.canCapture, onClick)
        Text(
            when {
                model.busy -> "촬영 중"
                model.processingCount >= 2 -> "처리 대기"
                else -> "사진"
            },
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1
        )
    }
}

@Composable
private fun RawStatus(model: RawCameraViewModel) {
    Column(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 12.dp, vertical = 4.dp)) {
        Text(
            model.message,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            if (model.busy) {
                "RAW 수신 중 · 잠시 고정해 주세요"
            } else if (model.switching) {
                "렌즈 전환 중…"
            } else if (model.processingCount > 0) {
                "저장 ${model.processingCount}/2건 · RAW 원본 보존 중"
            } else if (model.naturalAuto) {
                "RAW AUTO · ${if (model.hdrEnabled) "하늘·거리 HDR" else "단일 RAW"}"
            } else {
                "RAW · ${if (model.mergeEnabled) "3장 합성 DNG" else "원본 DNG"}"
            },
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun RawOptions(
    model: RawCameraViewModel,
    view: TextureView,
    viewer: PhotonPhotoViewer,
    onPro: () -> Unit,
    onDone: () -> Unit
) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("촬영 설정", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onDone) { Text("완료") }
        }
        RawOption("격자", model.gridEnabled, model::setGrid)
        RawOption("수평계", model.levelEnabled, model::setLevel)
        RawOption("히스토그램", model.histogramEnabled, model::setHistogram)
        PhotonPhotoViewerSettings(viewer, mimeType = model.recentPhotoMimeType)
        if (model.naturalAuto) {
            RawOption("RAW AUTO 다중 노출 HDR", model.hdrEnabled, model::setHdr, !model.busy)
            Text("하늘·거리 HDR · 밝기가 다른 RAW 3장을 자연스럽게 합성합니다.")
            Text("암부 밝기")
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(0f to "기본", 0.35f to "자연스럽게", 0.6f to "더 밝게").forEach { (value, label) ->
                    FilterChip(
                        selected = model.shadowStrength == value,
                        onClick = { model.updateShadowStrength(value) },
                        enabled = !model.busy,
                        label = { Text(label) }
                    )
                }
            }
            Text(
                "미리보기와 히스토그램은 최종 현상의 색·밝기와 다릅니다. 결과는 사진에서 확인하세요.",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "촬영 중 잠시 고정해 주세요. 움직임·합성 불일치 구간은 기준 프레임을 사용합니다. " +
                    "현상 실패 시 DNG 원본 1장을 저장합니다.",
                style = MaterialTheme.typography.bodySmall
            )
        } else {
            TextButton(onClick = onPro, enabled = !model.busy) { Text("초점·Pro") }
            RawOption("RAW 다중 프레임 합성", model.mergeEnabled, model::setMerge, !model.busy)
            Text(
                if (model.mergeEnabled) {
                    "합성 DNG 1장 저장 · 합성 실패 시 기준 원본 1장"
                } else {
                    "후처리 OFF · 원본 DNG 1장"
                },
                style = MaterialTheme.typography.bodyMedium
            )
            Text("DNG 확인에는 RAW 지원 갤러리나 편집기가 필요합니다.", style = MaterialTheme.typography.bodySmall)
        }
        Text(
            "DNG는 센서 원본과 크롭 정보를 함께 보존합니다. 편집기에 따라 전체 영역이 보일 수 있습니다.",
            style = MaterialTheme.typography.bodySmall
        )
        if (model.opticalLenses.isNotEmpty()) {
            FilterChip(
                selected = model.autoLens,
                onClick = { model.selectAuto(view) },
                enabled = !model.busy && !model.switching,
                label = { Text("후면 AUTO 줌") }
            )
        }
        if (!model.naturalAuto) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                model.lenses.forEach { lens ->
                    FilterChip(
                        selected = !model.autoLens && model.selectedId == lens.id,
                        onClick = { model.select(lens.id, view) },
                        enabled =
                        !model.busy && !model.switching,
                        label = { Text(lens.label) }
                    )
                }
            }
        }
        if (model.autoLens) RawZoomControls(model, view)
        TextButton(onClick = {
            model.open(view)
        }, enabled = !model.busy && !model.switching) { Text("재연결") }
        if (model.recoverableCount > 0) {
            TextButton(
                onClick = model::recoverNaturalPhoto,
                enabled = !model.busy && model.processingCount == 0
            ) {
                Text("보존된 사진 저장 재시도 (${model.recoverableCount})")
            }
        }
        Text(model.message, style = MaterialTheme.typography.bodySmall)
        Text(
            "처리 대기·진행 ${model.processingCount}/2건 · 수신 후 다음 촬영 가능",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun RawOption(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(
            checked,
            onChange,
            enabled = enabled,
            modifier = Modifier.semantics {
                contentDescription =
                    label
            }
        )
    }
}

@Composable
private fun RawZoomControls(model: RawCameraViewModel, view: TextureView) {
    val enabled = model.ready && !model.busy && !model.controlsUpdating
    Text(
        "${zoomText(
            model.zoomRatio
        )}× · ${model.opticalLabel.orEmpty()} · 디지털 ${zoomText(model.digitalZoomRatio)}×"
    )
    // A logarithmic slider gives equal travel to equal zoom factors, like a pinch gesture.
    if (model.minimumZoomRatio < model.maximumZoomRatio) {
        Slider(
            value = ln(model.zoomRatio.coerceIn(model.minimumZoomRatio, model.maximumZoomRatio)),
            onValueChange = { model.setZoom(exp(it), view) },
            valueRange = ln(model.minimumZoomRatio)..ln(model.maximumZoomRatio),
            enabled = enabled,
            modifier = Modifier.semantics {
                contentDescription = "RAW 연속 줌"
                stateDescription = "${zoomText(model.zoomRatio)}배"
            }
        )
    }
    Text(
        "${zoomText(model.minimumZoomRatio)}–${zoomText(model.maximumZoomRatio)}× · " +
            "두 손가락이나 슬라이더로 확대 · 렌즈 사이와 최대 배율은 디지털 크롭",
        style = MaterialTheme.typography.bodySmall
    )
}

private fun zoomText(value: Float): String = String.format(Locale.ROOT, "%.1f", value)
