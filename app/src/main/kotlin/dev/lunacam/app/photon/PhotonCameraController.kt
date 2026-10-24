package dev.lunacam.app.photon

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.net.Uri
import android.os.SystemClock
import android.util.Size
import android.view.Surface
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import androidx.lifecycle.viewModelScope
import java.io.File
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

data class PhotonLens(val id: String, val label: String, val info: CameraInfo)

/** Main-thread controller. Keeps capture feedback across rotation; never retains an Activity view. */
class PhotonCameraController(application: Application) : AndroidViewModel(application) {
    private val context = application
    private val preferences = application.getSharedPreferences(
        "photon-photo",
        Application.MODE_PRIVATE
    )
    var postProcessingEnabled by mutableStateOf(preferences.getBoolean("texture-guard", false))
        private set
    var hdrEnabled by mutableStateOf(preferences.getBoolean("hdr", false))
        private set
    var detailEnabled by mutableStateOf(preferences.getBoolean("detail-guard", false))
        private set
    var aspectRatio by mutableStateOf(
        PhotonAspectRatio.fromPreference(preferences.getString("aspect-ratio", null))
    )
        private set
    var gridEnabled by mutableStateOf(preferences.getBoolean("grid", false))
        private set
    var levelEnabled by mutableStateOf(preferences.getBoolean("level", false))
        private set
    var histogramEnabled by mutableStateOf(preferences.getBoolean("histogram", false))
        private set
    var histogram by mutableStateOf<PhotonHistogram?>(null)
        private set
    var histogramUnavailable by mutableStateOf(false)
        private set
    private val histogramAspect = AtomicReference(aspectRatio)
    private var analysis: ImageAnalysis? = null
    private var analysisExecutor: ExecutorService? = null
    var recentPhotoUri by mutableStateOf<Uri?>(null)
        private set
    private var recentPhotoVersion = 0
    var focusFeedback by mutableStateOf<PhotonFocusFeedback?>(null)
        private set
    var focusLocked by mutableStateOf(false)
        private set
    var supportsExposure by mutableStateOf(false)
        private set
    var minExposureEv by mutableFloatStateOf(0f)
        private set
    var maxExposureEv by mutableFloatStateOf(0f)
        private set
    var exposureEv by mutableFloatStateOf(0f)
        private set
    private var exposureStep = 0f
    private var exposureMinimum = 0
    private var exposureMaximum = 0
    private var exposureRequestedIndex = 0
    private var exposureAppliedIndex: Int? = null
    private var focusRequest = 0
    private var exposureRequest = 0
    var pendingCount by mutableIntStateOf(0)
        private set
    var recoverableCount by mutableIntStateOf(0)
        private set
    var lastShutterReadyMs by mutableStateOf<Long?>(null)
        private set
    private var resumed by mutableStateOf(false)
    val canCapture: Boolean get() = ready && resumed && !saving && pendingCount < 2
    private val pendingRoot = File(application.filesDir, "photon-pending")
    private val activeJobs = mutableSetOf<String>()
    private val capturer = PhotonHdrCapture(application)
    private var metering = AtomicReference<PhotonMeteredFrame?>(null)
    private var stillEvents: PhotonStillCaptureEvents? = null
    private data class CaptureTiming(
        val started: Long,
        var sensorReady: Long? = null,
        var shutterReady: Long? = null,
        var report: JSONObject? = null
    )
    private val timings = linkedMapOf<String, CaptureTiming>()

    init {
        refreshRecoverable()
        val version = recentPhotoVersion
        viewModelScope.launch {
            val recent = try {
                loadRecentPhotonPhoto(context)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
            // A slow startup query must not replace a photo saved during that query.
            if (recentPhotoVersion == version && recentPhotoUri == null) recentPhotoUri = recent
        }
    }

    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var preview: Preview? = null
    private var capture: ImageCapture? = null
    private var generation = 0
    private var zoomRequest = 0
    private var removeObservers: (() -> Unit)? = null
    var lenses by mutableStateOf<List<PhotonLens>>(emptyList())
        private set
    var selectedId by mutableStateOf<String?>(null)
        private set
    var ready by mutableStateOf(false)
        private set
    var saving by mutableStateOf(false)
        private set
    var message by mutableStateOf("카메라 준비 중…")
        private set
    var zoomRatio by mutableFloatStateOf(1f)
        private set
    var linearZoom by mutableFloatStateOf(0f)
        private set
    var canZoom by mutableStateOf(false)
        private set
    var retryKey by mutableIntStateOf(0)
        private set

    @androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
    fun bind(owner: LifecycleOwner, view: PreviewView) {
        release()
        val token = generation
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (token != generation) return@addListener
            try {
                if (context.checkSelfPermission(Manifest.permission.CAMERA) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    message = "카메라 권한을 허용한 뒤 다시 시도해 주세요."
                    return@addListener
                }
                val p = future.get()
                provider = p
                lenses = p.availableCameraInfos.map { info ->
                    val id = Camera2CameraInfo.from(info).cameraId
                    val facing = when (info.lensFacing) {
                        CameraSelector.LENS_FACING_BACK -> "후면"
                        CameraSelector.LENS_FACING_FRONT -> "전면"
                        else -> "외부"
                    }
                    PhotonLens(id, "$facing · $id", info)
                }
                val lens = lenses.firstOrNull { it.id == selectedId }
                    ?: lenses.firstOrNull { it.info.lensFacing == CameraSelector.LENS_FACING_BACK }
                    ?: lenses.firstOrNull() ?: error("NoCamera")
                selectedId = lens.id
                val rotation = view.display?.rotation ?: Surface.ROTATION_0
                val selector = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(
                        AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
                    ).build()
                // Each binding gets its own holder so late results from an old lens cannot win.
                val observed = AtomicReference<PhotonMeteredFrame?>(null)
                metering = observed
                val previewBuilder = Preview.Builder().setResolutionSelector(selector)
                    .setTargetRotation(rotation)
                Camera2Interop.Extender(previewBuilder).setSessionCaptureCallback(
                    object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureCompleted(
                            session: CameraCaptureSession,
                            request: CaptureRequest,
                            result: TotalCaptureResult
                        ) {
                            observed.set(PhotonMeteredFrame(result, SystemClock.elapsedRealtime()))
                        }
                    }
                )
                val pv = previewBuilder.build()
                val captureBuilder = ImageCapture.Builder().setResolutionSelector(selector)
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setTargetRotation(rotation).setJpegQuality(95)
                val events = PhotonStillCaptureEvents(context.mainExecutor)
                Camera2Interop.Extender(captureBuilder).setSessionCaptureCallback(events.callback)
                val photo = captureBuilder.build()
                stillEvents = events
                preview = pv
                capture = photo
                pv.setSurfaceProvider(view.surfaceProvider)
                // Both use cases receive the same viewport so preview crop and JPEG crop agree.
                val sensorRotation = lens.info.getSensorRotationDegrees(rotation)
                val aspect = if (sensorRotation % 180 ==
                    0
                ) {
                    android.util.Rational(4, 3)
                } else {
                    android.util.Rational(3, 4)
                }
                val viewport = ViewPort.Builder(aspect, rotation).setScaleType(ViewPort.FIT).build()
                fun group(extra: ImageAnalysis? = null): UseCaseGroup =
                    UseCaseGroup.Builder().addUseCase(pv).addUseCase(photo)
                        .setViewPort(viewport).apply { extra?.let { addUseCase(it) } }.build()
                var bound = p.bindToLifecycle(owner, lens.info.cameraSelector, group())
                if (histogramEnabled && !histogramUnavailable) {
                    // Establish the ordinary still size first; analysis must not downsize photos.
                    val stillSize = photo.resolutionInfo?.resolution
                    val extra = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setResolutionSelector(
                            ResolutionSelector.Builder().setResolutionStrategy(
                                ResolutionStrategy(
                                    Size(320, 240),
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                                )
                            ).build()
                        ).setTargetRotation(rotation).build()
                    try {
                        check(stillSize != null)
                        p.unbind(pv, photo)
                        bound = p.bindToLifecycle(owner, lens.info.cameraSelector, group(extra))
                        check(photo.resolutionInfo?.resolution == stillSize)
                        analysis = extra
                        val executor = Executors.newSingleThreadExecutor()
                        analysisExecutor = executor
                        extra.setAnalyzer(
                            executor,
                            PhotonHistogramAnalyzer(histogramAspect::get, {
                                    ratio,
                                    value
                                ->
                                context.mainExecutor.execute {
                                    if (generation == token && analysis === extra && resumed &&
                                        histogramAspect.get() == ratio
                                    ) {
                                        histogram = value
                                    }
                                }
                            })
                        )
                    } catch (_: Exception) {
                        extra.clearAnalyzer()
                        analysis = null
                        analysisExecutor?.shutdown()
                        analysisExecutor = null
                        p.unbind(pv, photo, extra)
                        histogramUnavailable = true
                        bound = p.bindToLifecycle(owner, lens.info.cameraSelector, group())
                    }
                }
                camera = bound
                configureExposure(bound)
                resumed = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                val lifecycleObserver = LifecycleEventObserver { source, _ ->
                    if (generation == token) {
                        resumed = source.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                        if (!resumed) histogram = null
                        updateAvailability()
                    }
                }
                owner.lifecycle.addObserver(lifecycleObserver)
                val stateObserver = Observer<CameraState> { state ->
                    if (generation == token) {
                        ready = state.type == CameraState.Type.OPEN && state.error == null
                        if (state.error != null && analysis != null && !histogramUnavailable) {
                            // Some HALs reject the extra stream asynchronously after binding.
                            histogramUnavailable = true
                            histogram = null
                            retryKey++
                        }
                        message = when {
                            state.error != null ->
                                "카메라 오류 (${state.error?.code}). " +
                                    "다른 카메라 앱을 닫고 다시 시도해 주세요."
                            ready -> "AUTO · 두 손가락 줌 · 터치 초점"
                            else -> "카메라 연결 중…"
                        }
                        updateAvailability()
                    }
                }
                val zoomObserver = Observer<androidx.camera.core.ZoomState> { state ->
                    if (generation == token) {
                        zoomRatio = state.zoomRatio
                        linearZoom = state.linearZoom
                        canZoom = state.maxZoomRatio > state.minZoomRatio
                    }
                }
                bound.cameraInfo.cameraState.observe(owner, stateObserver)
                bound.cameraInfo.zoomState.observe(owner, zoomObserver)
                removeObservers = {
                    owner.lifecycle.removeObserver(lifecycleObserver)
                    bound.cameraInfo.cameraState.removeObserver(stateObserver)
                    bound.cameraInfo.zoomState.removeObserver(zoomObserver)
                }
            } catch (error: Exception) {
                release()
                message = "카메라를 열지 못했습니다 (${error.javaClass.simpleName}). 다시 시도해 주세요."
            }
        }, context.mainExecutor)
    }

    fun select(id: String) {
        if (!saving && id != selectedId) {
            histogramUnavailable = false
            clearFocusLock()
            selectedId = id
            retryKey++
        }
    }

    fun retry() {
        if (!saving) {
            histogramUnavailable = false
            retryKey++
        }
    }

    fun setHistogram(enabled: Boolean) {
        if (saving || histogramEnabled == enabled) return
        histogramEnabled = enabled
        histogramUnavailable = false
        histogram = null
        preferences.edit { putBoolean("histogram", enabled) }
        retryKey++
    }

    fun setPostProcessing(enabled: Boolean) {
        if (saving) return
        postProcessingEnabled = enabled
        preferences.edit { putBoolean("texture-guard", enabled) }
    }

    fun setHdr(enabled: Boolean) {
        if (saving) return
        hdrEnabled = enabled
        preferences.edit { putBoolean("hdr", enabled) }
    }

    fun setDetail(enabled: Boolean) {
        if (saving) return
        detailEnabled = enabled
        preferences.edit { putBoolean("detail-guard", enabled) }
    }

    fun selectAspectRatio(value: PhotonAspectRatio) {
        if (saving || aspectRatio == value) return
        clearFocusLock()
        aspectRatio = value
        histogramAspect.set(value)
        histogram = null
        preferences.edit { putString("aspect-ratio", value.id) }
        // Keep the camera session and acquisition timing unchanged. The viewfinder
        // and background save use this same centered crop; each job snapshots it.
    }

    fun setRotation(rotation: Int) {
        if (saving) return
        if (capture?.targetRotation != rotation) clearFocusLock()
        capture?.targetRotation = rotation
    }

    fun zoom(value: Float) {
        if (!ready || saving || !value.isFinite()) return
        val active = camera ?: return
        val target = value.coerceIn(0f, 1f)
        if (kotlin.math.abs(target - linearZoom) > .0001f &&
            (focusFeedback != null || focusLocked)
        ) {
            resetFocus(cancelCamera = true)
        }
        observeZoom(active.cameraControl.setLinearZoom(target))
    }

    fun pinchZoom(factor: Float) {
        val active = camera ?: return
        val state = active.cameraInfo.zoomState.value ?: return
        if (!ready || saving || !canZoom) return
        val target = pinchZoomRatio(state.zoomRatio, factor, state.minZoomRatio, state.maxZoomRatio)
        if (kotlin.math.abs(target - state.zoomRatio) > .0001f &&
            (focusFeedback != null || focusLocked)
        ) {
            resetFocus(cancelCamera = true)
        }
        observeZoom(active.cameraControl.setZoomRatio(target))
    }

    private fun observeZoom(future: com.google.common.util.concurrent.ListenableFuture<Void>) {
        val token = generation
        val request = ++zoomRequest
        future.addListener({
            try {
                future.get()
            } catch (error: Exception) {
                // CameraX cancels superseded requests during a continuous gesture; that is normal.
                if (generation == token && request == zoomRequest &&
                    error.cause !is CameraControl.OperationCanceledException
                ) {
                    message = "줌을 적용하지 못했습니다. 다시 조절해 주세요."
                }
            }
        }, context.mainExecutor)
    }

    fun setGrid(enabled: Boolean) {
        gridEnabled = enabled
        preferences.edit { putBoolean("grid", enabled) }
    }

    fun setLevel(enabled: Boolean) {
        levelEnabled = enabled
        preferences.edit { putBoolean("level", enabled) }
    }

    private fun configureExposure(active: Camera) {
        val state = active.cameraInfo.exposureState
        exposureStep =
            state.exposureCompensationStep.toFloat().takeIf { it.isFinite() && it > 0f } ?: 0f
        exposureMinimum = state.exposureCompensationRange.lower
        exposureMaximum = state.exposureCompensationRange.upper
        supportsExposure = state.isExposureCompensationSupported && exposureStep.isFinite() &&
            exposureStep > 0f && exposureMinimum < exposureMaximum &&
            exposureMinimum <= 0 && exposureMaximum >= 0
        minExposureEv = if (supportsExposure) exposureMinimum * exposureStep else 0f
        maxExposureEv = if (supportsExposure) exposureMaximum * exposureStep else 0f
        exposureEv = 0f
        exposureRequestedIndex = 0
        // Exposure never carries across lenses. This is asynchronous, with no capture wait.
        if (supportsExposure) applyExposure(active, 0)
    }

    fun adjustExposureEv(value: Float) {
        val active = camera ?: return
        if (!ready || saving || !supportsExposure) return
        val index =
            photonExposureIndex(value, exposureStep, exposureMinimum, exposureMaximum) ?: return
        if (index == exposureRequestedIndex) return
        applyExposure(active, index)
    }

    private fun applyExposure(active: Camera, index: Int) {
        val token = generation
        val request = ++exposureRequest
        exposureRequestedIndex = index
        exposureAppliedIndex = null
        exposureEv = index * exposureStep
        try {
            val future = active.cameraControl.setExposureCompensationIndex(index)
            future.addListener({
                if (generation != token || exposureRequest != request) return@addListener
                try {
                    val applied = future.get()
                    exposureAppliedIndex = applied
                    exposureRequestedIndex = applied
                    exposureEv = applied * exposureStep
                } catch (_: Exception) {
                    exposureRequestedIndex =
                        active.cameraInfo.exposureState.exposureCompensationIndex
                    exposureEv = exposureRequestedIndex * exposureStep
                    message = "노출 보정을 적용하지 못했습니다. 다시 조절해 주세요."
                }
            }, context.mainExecutor)
        } catch (_: Exception) {
            if (generation == token && exposureRequest == request) {
                exposureRequestedIndex = active.cameraInfo.exposureState.exposureCompensationIndex
                exposureEv = exposureRequestedIndex * exposureStep
                message = "노출 보정을 적용하지 못했습니다. 다시 조절해 주세요."
            }
        }
    }

    fun clearFocusLock() {
        if (saving) return
        resetFocus(cancelCamera = true)
    }

    private fun resetFocus(cancelCamera: Boolean) {
        focusRequest++
        focusFeedback = null
        focusLocked = false
        if (cancelCamera) runCatching { camera?.cameraControl?.cancelFocusAndMetering() }
    }

    fun focus(view: PreviewView, x: Float, y: Float, hold: Boolean = false) {
        val active = camera ?: return
        if (!ready || saving || !x.isFinite() || !y.isFinite()) return
        val info = preview?.resolutionInfo ?: return
        if (!isPreviewTap(
                view.width,
                view.height,
                info.cropRect.width(),
                info.cropRect.height(),
                info.rotationDegrees,
                x,
                y,
                fillCenter = view.scaleType == PreviewView.ScaleType.FILL_CENTER
            )
        ) {
            return
        }
        clearFocusLock()
        val point = view.meteringPointFactory.createPoint(x, y)
        // This maintains AF only. AE is neither locked nor advertised as locked.
        val builder = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
        if (hold) {
            builder.disableAutoCancel()
        } else {
            builder.setAutoCancelDuration(
                3,
                TimeUnit.SECONDS
            )
        }
        val action = builder.build()
        if (!active.cameraInfo.isFocusMeteringSupported(action)) {
            message = "이 렌즈는 터치 초점을 지원하지 않습니다."
            return
        }
        val token = generation
        val request = ++focusRequest
        val feedback =
            PhotonFocusFeedback(x / view.width, y / view.height, PhotonFocusStatus.FOCUSING)
        focusFeedback = feedback
        focusLocked = hold
        try {
            val future = active.cameraControl.startFocusAndMetering(action)
            future.addListener({
                if (generation != token || focusRequest != request) return@addListener
                val success = runCatching { future.get().isFocusSuccessful }.getOrDefault(false)
                focusLocked = hold && success
                focusFeedback = feedback.copy(
                    status = when {
                        !success -> PhotonFocusStatus.FAILED
                        hold -> PhotonFocusStatus.LOCKED
                        else -> PhotonFocusStatus.FOCUSED
                    }
                )
                if (!success && hold) runCatching { active.cameraControl.cancelFocusAndMetering() }
                message = when {
                    !success -> "초점을 확인하지 못했습니다. 다시 터치해 주세요."
                    hold -> "초점을 유지합니다. 다시 터치하면 해제됩니다."
                    else -> "초점을 맞췄습니다."
                }
            }, context.mainExecutor)
        } catch (_: Exception) {
            if (generation == token && focusRequest == request) {
                focusLocked = false
                focusFeedback = feedback.copy(status = PhotonFocusStatus.FAILED)
                message = "초점을 적용하지 못했습니다. 다시 터치해 주세요."
            }
        }
    }

    /** Requested and reported values are distinct: capture never waits for an EV slider future. */
    internal fun snapshotControls(): JSONObject = JSONObject()
        .put("exposureRequestedEv", exposureEv.toDouble())
        .put("exposureRequestedIndex", exposureRequestedIndex)
        .put("exposureAppliedIndex", exposureAppliedIndex ?: JSONObject.NULL)
        .put(
            "exposureCameraIndex",
            camera?.cameraInfo?.exposureState?.exposureCompensationIndex ?: JSONObject.NULL
        )
        .put(
            "exposurePreviewIndex",
            metering.get()?.result?.get(CaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION)
                ?: JSONObject.NULL
        )
        .put("exposureStepEv", exposureStep.toDouble())
        .put("focusLocked", focusLocked)
        .put("focusStatus", focusFeedback?.status?.name ?: JSONObject.NULL)
        .put("aeLockRequested", false)

    fun takePhoto(owner: LifecycleOwner) {
        val photo = capture ?: return
        val active = camera ?: return
        if (!canCapture || !owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        if (!shutterSound.play()) {
            message = "셔터음을 준비하지 못했습니다. 촬영을 다시 시도해 주세요."
            return
        }
        val started = SystemClock.elapsedRealtime()
        val name = "${System.currentTimeMillis()}_${UUID.randomUUID()}"
        val directory = File(pendingRoot, name)
        val texture = postProcessingEnabled
        val detail = texture && detailEnabled
        val ratio = aspectRatio
        val hdr = hdrEnabled
        val controls = snapshotControls()
        val rotation = photo.targetRotation
        val events = stillEvents
        try {
            check(directory.mkdirs())
            File(directory, "job.json").writeText(
                JSONObject().put("texture", texture).put("hdr", hdr)
                    .put("detail", detail).put("aspectRatio", ratio.id)
                    .put("controls", controls).toString()
            )
        } catch (_: Exception) {
            message = "촬영 임시 공간을 만들지 못했습니다. 저장 공간을 확인해 주세요."
            return
        }
        pendingCount++
        activeJobs += name
        timings[name] = CaptureTiming(started)
        saving = true
        message = if (hdr) "HDR 촬영 중…" else "촬영 중…"
        viewModelScope.launch {
            var input: CapturedJpegs? = null
            try {
                input =
                    capturer.capture(
                        photo,
                        active,
                        directory,
                        hdr,
                        rotation,
                        metering.get(),
                        events
                    )
            } catch (error: CancellationException) {
                throw error
            } catch (error: PhotonHdrCaptureException) {
                input = error.retainedBase?.let {
                    CapturedJpegs(
                        it,
                        null,
                        SystemClock.elapsedRealtime() - started,
                        hdr,
                        "capture_fallback"
                    )
                }
                if (error.requiresRebind) {
                    release()
                    retryKey++
                }
                message = "촬영을 완료하지 못했습니다. 남은 기본 사진이 있으면 저장합니다."
            } catch (_: Exception) {
                message = "촬영을 시작하지 못했습니다. 재연결 후 다시 시도해 주세요."
            } finally {
                saving = false
                if (input == null) {
                    pendingCount--
                    activeJobs -= name
                    timings.remove(name)
                    refreshRecoverable()
                    updateAvailability()
                }
            }
            input?.let { captured ->
                val readyAt = SystemClock.elapsedRealtime()
                timings[name]?.sensorReady = readyAt
                updateAvailability()
                // Sensor requests and exposure restoration are complete. Processing holds no shutter lock.
                processCaptured(captured, texture, detail, ratio, controls, started)
            }
        }
    }

    private suspend fun processCaptured(
        input: CapturedJpegs,
        texture: Boolean,
        detail: Boolean,
        ratio: PhotonAspectRatio,
        controls: JSONObject,
        started: Long
    ) {
        val name = requireNotNull(input.base.parentFile).name
        var newPhoto: Uri? = null
        try {
            val saved = PhotonProcessingGate.mutex.withLock {
                PhotonAutoPhotoStore(context).save(input, texture, detail, ratio)
            }
            newPhoto = saved.uri
            saved.receipt.put("controls", JSONObject(controls.toString()))
            val completedAt = SystemClock.elapsedRealtime()
            saved.receipt.put("jobId", name).put("totalMs", completedAt - started)
            val timing = timings[name]
            if (timing != null) {
                timing.report = saved.receipt
                // A background save can finish before the camera becomes ready again. Persist
                // its output now; the source has already been cleaned up after public commit.
                record(
                    autoReadinessReceipt(
                        saved.receipt,
                        timing.started,
                        timing.sensorReady,
                        timing.shutterReady
                    ),
                    name
                )
            } else {
                saved.receipt.put("recoveredSave", true)
                record(saved.receipt, name)
            }
            if (!saving) message = saved.message
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            message = "저장하지 못했습니다. 내부 임시 사진은 보존됐으며 저장 재시도가 가능합니다."
        } catch (_: OutOfMemoryError) {
            message = "메모리가 부족합니다. 내부 임시 사진에서 저장을 재시도해 주세요."
        } finally {
            pendingCount--
            activeJobs -= name
            // A failed/cancelled save has no completion receipt to flush on later camera resumes.
            if (timings[name]?.report == null) timings.remove(name)
            refreshRecoverable()
            updateAvailability()
            // Publish the thumbnail state only after the existing shutter-availability release.
            newPhoto?.let {
                recentPhotoVersion++
                recentPhotoUri = it
            }
        }
    }

    private fun updateAvailability() {
        if (canCapture) {
            val now = SystemClock.elapsedRealtime()
            timings.values.filter { it.sensorReady != null && it.shutterReady == null }.forEach {
                it.shutterReady = now
                lastShutterReadyMs = now - it.started
            }
        }
        val completed = timings.filterValues { it.report != null && it.shutterReady != null }
        completed.forEach { (name, timing) ->
            record(
                autoReadinessReceipt(
                    requireNotNull(timing.report),
                    timing.started,
                    timing.sensorReady,
                    timing.shutterReady
                ),
                name
            )
            timings.remove(name)
        }
    }

    fun retryPendingSave() {
        if (pendingCount >= 2 || saving) return
        val directory = pendingRoot.listFiles()?.firstOrNull {
            it.isDirectory && it.name !in activeJobs && isCompleteCapturedJpeg(File(it, "base.jpg"))
        } ?: return
        val job = runCatching { JSONObject(File(directory, "job.json").readText()) }
            .getOrDefault(JSONObject())
        val base = File(directory, "base.jpg")
        val short = File(directory, "short.jpg").takeIf(::isCompleteCapturedJpeg)
        activeJobs += directory.name
        pendingCount++
        refreshRecoverable()
        viewModelScope.launch {
            processCaptured(
                CapturedJpegs(base, short, 0, job.optBoolean("hdr"), "recovered_private_capture"),
                job.optBoolean("texture"),
                job.optBoolean("detail"),
                PhotonAspectRatio.fromPreference(job.optString("aspectRatio")),
                job.optJSONObject("controls") ?: JSONObject().put("snapshotUnavailable", true),
                SystemClock.elapsedRealtime()
            )
        }
    }

    private fun refreshRecoverable() {
        recoverableCount = pendingRoot.listFiles()?.count {
            it.isDirectory && it.name !in activeJobs && isCompleteCapturedJpeg(File(it, "base.jpg"))
        } ?: 0
    }

    private fun record(report: JSONObject, name: String) {
        runCatching {
            File(context.filesDir, "photon-auto-last-save.json").writeText(report.toString(2))
            val folder = File(context.filesDir, "photon-auto-receipts").apply { mkdirs() }
            File(folder, "$name.json").writeText(report.toString(2))
            folder.listFiles()?.sortedByDescending {
                it.lastModified()
            }?.drop(20)?.forEach { it.delete() }
        }
    }

    fun release() {
        generation++
        resetFocus(cancelCamera = true)
        exposureRequest++
        supportsExposure = false
        exposureEv = 0f
        minExposureEv = 0f
        maxExposureEv = 0f
        exposureStep = 0f
        exposureMinimum = 0
        exposureMaximum = 0
        exposureRequestedIndex = 0
        exposureAppliedIndex = null
        metering = AtomicReference(null)
        ready = false
        resumed = false
        canZoom = false
        removeObservers?.invoke()
        removeObservers = null
        // Unbind only our use cases; the legacy Camera2 screen has its own lifecycle.
        histogram = null
        analysis?.clearAnalyzer()
        val useCases = listOfNotNull(preview, capture, analysis).toTypedArray()
        if (useCases.isNotEmpty()) provider?.unbind(*useCases)
        analysis = null
        analysisExecutor?.shutdown()
        analysisExecutor = null
        preview = null
        capture = null
        camera = null
        stillEvents = null
    }

    private val shutterSound = PhotonShutterSound()

    override fun onCleared() {
        shutterSound.close()
        release()
    }
}
