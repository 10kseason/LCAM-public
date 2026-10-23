package dev.lunacam.app.photon.night

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.TextureView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
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
import dev.lunacam.app.photon.PhotonCameraColors
import dev.lunacam.app.photon.PhotonCameraModeBar
import dev.lunacam.app.photon.PhotonCameraShutter
import dev.lunacam.app.photon.PhotonPhotoViewer
import dev.lunacam.app.photon.PhotonPhotoViewerSettings
import dev.lunacam.app.photon.PhotonRecentPhotoButton
import dev.lunacam.app.photon.PhotonViewfinderAssist
import dev.lunacam.app.photon.captureRotation

private class NightPreview(context: Context) : TextureView(context) {
    var onRefocus: () -> Unit = {}
    private val taps = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent): Boolean = true
            override fun onSingleTapUp(event: MotionEvent): Boolean = performClick()
        }
    )

    init {
        isClickable = true
        contentDescription = "나이트 초점과 색 다시 맞추기"
    }

    // GestureDetector's single tap calls performClick; accessibility calls it directly.
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean =
        taps.onTouchEvent(event) || super.onTouchEvent(event)

    override fun performClick(): Boolean {
        super.performClick()
        onRefocus()
        return true
    }
}

/** RAW session opens on entry, not inside the shutter's acquisition interval. */
@Composable
fun SerNightCameraScreen(
    onBack: () -> Unit,
    model: SerNightViewModel = viewModel(),
    onRaw: (() -> Unit)? = null,
    onRawAuto: (() -> Unit)? = null,
    onExit: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var showOptions by rememberSaveable { mutableStateOf(false) }
    val viewer =
        remember(context.applicationContext) { PhotonPhotoViewer(context.applicationContext) }
    var rotation by remember { mutableIntStateOf(android.view.Surface.ROTATION_0) }
    val view = remember(context) {
        NightPreview(context).apply {
            onRefocus = model::refocus
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> model.updateTransform(this) }
        }
    }
    DisposableEffect(owner, view) {
        val displays = context.getSystemService(DisplayManager::class.java)
        val displayListener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) {
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
                    rotation = view.display?.rotation ?: android.view.Surface.ROTATION_0
                    model.open(view)
                    orientation.enable()
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
            rotation = view.display?.rotation ?: android.view.Surface.ROTATION_0
            model.open(view)
            orientation.enable()
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
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onExit ?: onBack, enabled = !model.busy) { Text("닫기") }
                    Text(
                        "나이트",
                        Modifier.weight(1f).padding(horizontal = 8.dp).semantics {
                            contentDescription = "현재 모드 나이트"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1
                    )
                    if (!model.ready) {
                        TextButton(onClick = { model.open(view) }, enabled = !model.busy) {
                            Text("재연결")
                        }
                    }
                    TextButton(
                        onClick = { showOptions = true },
                        modifier = Modifier.semantics { contentDescription = "보정 설정" }
                    ) { Text("보정 설정") }
                }
                PhotonCameraModeBar("나이트", onBack, onRawAuto, onRaw, null, !model.busy)
                val landscape = LocalConfiguration.current.orientation ==
                    Configuration.ORIENTATION_LANDSCAPE
                if (landscape) {
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        Column(Modifier.weight(1f).fillMaxSize()) {
                            NightFraming()
                            NightViewfinder(view, model, true, Modifier.weight(1f).fillMaxWidth())
                            NightLens()
                        }
                        Column(
                            Modifier.width(112.dp).fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            PhotonRecentPhotoButton(
                                model.recentPhotoUri,
                                viewer,
                                Modifier.size(56.dp)
                            )
                            NightShutter(model) { model.capture(rotation) }
                        }
                    }
                } else {
                    NightFraming()
                    NightViewfinder(view, model, false, Modifier.weight(1f).fillMaxWidth())
                    NightLens()
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        PhotonRecentPhotoButton(
                            model.recentPhotoUri,
                            viewer,
                            Modifier.align(Alignment.CenterStart).padding(start = 24.dp).size(64.dp)
                        )
                        NightShutter(model) { model.capture(rotation) }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        model.message,
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (model.recoverableCount > 0) {
                        TextButton(
                            onClick = model::recoverPending,
                            enabled = !model.busy && model.processingCount == 0
                        ) { Text("저장 재시도 (${model.recoverableCount})") }
                    }
                }
            }
            if (showOptions) NightOptions(model, viewer) { showOptions = false }
        }
    }
}

@Composable
private fun NightFraming() {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        FilterChip(selected = true, onClick = {}, label = { Text("4:3") })
        Text("나이트 고정 비율", style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun NightLens() {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        FilterChip(selected = true, onClick = {}, label = { Text("1×") })
        Text("후면 · 12.48MP", style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun NightViewfinder(
    view: TextureView,
    model: SerNightViewModel,
    landscape: Boolean,
    modifier: Modifier
) {
    BoxWithConstraints(
        modifier.background(Color.Black).clipToBounds(),
        contentAlignment = Alignment.Center
    ) {
        val ratio = if (landscape) 4f / 3 else 3f / 4
        val width = minOf(maxWidth, maxHeight * ratio)
        Box(Modifier.size(width, width / ratio).clipToBounds().testTag("night-preview")) {
            AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
            PhotonViewfinderAssist(model.gridEnabled, model.levelEnabled, Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun NightShutter(model: SerNightViewModel, onClick: () -> Unit) {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NightOptions(
    model: SerNightViewModel,
    viewer: PhotonPhotoViewer,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("촬영 설정", style = MaterialTheme.typography.titleLarge)
            NightOption("격자", "프리뷰 격자", model.gridEnabled, true, model::setGrid)
            NightOption("수평계", "프리뷰 수평계", model.levelEnabled, true, model::setLevel)
            NightOption("SER 암부 합성", "SER 암부 합성", model.serEnabled, !model.busy, model::setSer)
            Text("정지 장면의 암부를 합성합니다. 후면 1× · 4:3 촬영을 사용합니다.")
            Text(
                if (model.focusColorHeld) {
                    "초점·색 잠시 유지 · 프리뷰 탭으로 다시 맞춤"
                } else {
                    "프리뷰 탭: 초점·색 다시 맞춤"
                },
                style = MaterialTheme.typography.bodySmall
            )
            PhotonPhotoViewerSettings(viewer)
            model.lastShutterReadyMs?.let {
                Text("최근 다음 촬영 대기 ${it}ms", style = MaterialTheme.typography.bodySmall)
            }
            Text(model.message, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("완료") }
        }
    }
}

@Composable
private fun NightOption(
    title: String,
    accessibilityLabel: String,
    checked: Boolean,
    enabled: Boolean,
    onChanged: (Boolean) -> Unit
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        Switch(
            checked = checked,
            onCheckedChange = onChanged,
            enabled = enabled,
            modifier = Modifier.semantics { contentDescription = accessibilityLabel }
        )
    }
}
