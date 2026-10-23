package dev.lunacam.feature.camera

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.lunacam.camera.camera2.preview.PreviewCameraController
import dev.lunacam.camera.camera2.preview.PreviewSurfaceProvider
import dev.lunacam.camera.camera2.preview.PreviewViewGeometry
import dev.lunacam.domain.preview.CameraState
import dev.lunacam.domain.preview.PointD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Owns the camera across activity recreation, as DESIGN.md section 5.6 requires.
 *
 * The camera itself is released whenever the screen actually leaves the foreground, so a rotation
 * keeps the device open while a background transition hands it back to other apps.
 */
class CameraPreviewViewModel(controllerProvider: (CoroutineScope) -> PreviewCameraController) :
    ViewModel() {
    private val controller = controllerProvider(viewModelScope)

    private val mutableState = MutableStateFlow<PreviewUiState>(PreviewUiState.Idle)
    val state: StateFlow<PreviewUiState> = mutableState.asStateFlow()

    private var geometry: PreviewViewGeometry? = null
    private var surfaceProvider: PreviewSurfaceProvider? = null
    private var isForeground = false
    private var isStarted = false
    private var focusIndicator: PointD? = null

    init {
        viewModelScope.launch {
            controller.state.collect { cameraState -> mutableState.value = toUiState(cameraState) }
        }
    }

    fun onForeground() {
        isForeground = true
        startIfReady()
    }

    fun onBackground() {
        isForeground = false
        isStarted = false
        focusIndicator = null
        viewModelScope.launch { controller.stop() }
    }

    fun onSurfaceProviderAvailable(provider: PreviewSurfaceProvider) {
        surfaceProvider = provider
        startIfReady()
    }

    fun onSurfaceLost() {
        isStarted = false
    }

    fun onGeometryChanged(newGeometry: PreviewViewGeometry) {
        if (!newGeometry.viewSize.isPositive) return
        val previous = geometry
        geometry = newGeometry
        if (isStarted && previous != null) {
            viewModelScope.launch { controller.updateGeometry(newGeometry) }
        } else {
            startIfReady()
        }
    }

    fun onTap(point: PointD) {
        if (!isStarted) return
        focusIndicator = point
        refreshFocusIndicator()
        viewModelScope.launch { controller.focusAt(point) }
    }

    fun onExposureCompensationEvChanged(requestedEv: Double) {
        if (!isStarted) return
        viewModelScope.launch { controller.setExposureCompensationEv(requestedEv) }
    }

    override fun onCleared() {
        controller.close()
        super.onCleared()
    }

    private fun startIfReady() {
        val geometry = this.geometry ?: return
        val provider = this.surfaceProvider ?: return
        if (!isForeground || isStarted) return

        isStarted = true
        viewModelScope.launch { controller.start(geometry, provider) }
    }

    private fun refreshFocusIndicator() {
        val current = mutableState.value
        if (current is PreviewUiState.Ready) {
            mutableState.value = current.copy(focusIndicator = focusIndicator)
        }
    }

    private fun toUiState(cameraState: CameraState): PreviewUiState = when (cameraState) {
        is CameraState.Closed, is CameraState.Closing -> PreviewUiState.Idle
        is CameraState.PermissionRequired -> PreviewUiState.Idle
        is CameraState.Opening, is CameraState.Configuring -> PreviewUiState.Preparing
        is CameraState.Previewing ->
            PreviewUiState.Ready(
                previewSize = cameraState.runtime.profile.previewSize,
                displayAspectRatio = cameraState.runtime.transform.displayAspectRatio.toFloat(),
                exposureCompensation = cameraState.runtime.exposureCompensationSpec,
                exposureCompensationStep = cameraState.runtime.exposureCompensationStep,
                focusIndicator = focusIndicator
            )

        // P2 has no capture path, so this arm exists only to keep the mapping exhaustive.
        is CameraState.Capturing -> PreviewUiState.Preparing
        is CameraState.Recovering ->
            PreviewUiState.Recovering(cameraState.attempt, cameraState.cause)

        is CameraState.Blocked -> PreviewUiState.Blocked(cameraState.cause)
    }

    class Factory(private val controllerProvider: (CoroutineScope) -> PreviewCameraController) :
        ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            CameraPreviewViewModel(controllerProvider) as T
    }
}
