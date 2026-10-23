package dev.lunacam.feature.camera

import android.view.Surface
import dev.lunacam.camera.camera2.preview.PreviewCameraController
import dev.lunacam.camera.camera2.preview.PreviewSurfaceProvider
import dev.lunacam.camera.camera2.preview.PreviewViewGeometry
import dev.lunacam.domain.preview.CameraFailure
import dev.lunacam.domain.preview.CameraFailureKind
import dev.lunacam.domain.preview.CameraState
import dev.lunacam.domain.preview.ExposureCompensationSpec
import dev.lunacam.domain.preview.IntRect
import dev.lunacam.domain.preview.IntSize
import dev.lunacam.domain.preview.PointD
import dev.lunacam.domain.preview.PreviewRuntime
import dev.lunacam.domain.preview.PreviewTransformFactory
import dev.lunacam.domain.preview.StreamProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CameraPreviewViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var controller: FakePreviewCameraController

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        controller = FakePreviewCameraController()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `the camera does not open before the surface and the geometry both exist`() = runTest(
        dispatcher
    ) {
        val viewModel = createViewModel()

        viewModel.onForeground()
        advanceUntilIdle()
        assertEquals(0, controller.startCount)

        viewModel.onSurfaceProviderAvailable(FakeSurfaceProvider())
        advanceUntilIdle()
        assertEquals(0, controller.startCount)
    }

    @Test
    fun `the camera does not open while the screen is in the background`() = runTest(dispatcher) {
        val viewModel = createViewModel()

        viewModel.onSurfaceProviderAvailable(FakeSurfaceProvider())
        viewModel.onGeometryChanged(geometry())
        advanceUntilIdle()

        assertEquals(0, controller.startCount)
    }

    @Test
    fun `the camera opens exactly once when everything is ready`() = runTest(dispatcher) {
        val viewModel = startedViewModel()

        advanceUntilIdle()
        assertEquals(1, controller.startCount)

        // A repeated surface callback must not open a second generation.
        viewModel.onSurfaceProviderAvailable(FakeSurfaceProvider())
        advanceUntilIdle()
        assertEquals(1, controller.startCount)
    }

    @Test
    fun `a rotation updates the geometry instead of reopening the camera`() = runTest(dispatcher) {
        val viewModel = startedViewModel()
        advanceUntilIdle()

        viewModel.onGeometryChanged(geometry(displayRotationDegrees = 90))
        advanceUntilIdle()

        assertEquals(1, controller.startCount)
        assertEquals(1, controller.updateGeometryCount)
        assertEquals(0, controller.stopCount)
        assertEquals(90, controller.lastGeometry?.displayRotationDegrees)
    }

    @Test
    fun `a degenerate view size is ignored`() = runTest(dispatcher) {
        val viewModel = startedViewModel()
        advanceUntilIdle()

        viewModel.onGeometryChanged(PreviewViewGeometry(IntSize(0, 0), 0))
        advanceUntilIdle()

        assertEquals(0, controller.updateGeometryCount)
    }

    @Test
    fun `leaving the foreground releases the camera`() = runTest(dispatcher) {
        val viewModel = startedViewModel()
        advanceUntilIdle()

        viewModel.onBackground()
        advanceUntilIdle()

        assertEquals(1, controller.stopCount)
    }

    @Test
    fun `returning to the foreground opens the camera again`() = runTest(dispatcher) {
        val viewModel = startedViewModel()
        advanceUntilIdle()
        viewModel.onBackground()
        advanceUntilIdle()

        viewModel.onForeground()
        advanceUntilIdle()

        assertEquals(2, controller.startCount)
        assertEquals(1, controller.stopCount)
    }

    @Test
    fun `a tap before the preview started is ignored`() = runTest(dispatcher) {
        val viewModel = createViewModel()

        viewModel.onTap(PointD(10.0, 10.0))
        advanceUntilIdle()

        assertTrue(controller.focusTaps.isEmpty())
    }

    @Test
    fun `a tap while previewing reaches the controller`() = runTest(dispatcher) {
        val viewModel = startedViewModel()
        advanceUntilIdle()

        viewModel.onTap(PointD(540.0, 960.0))
        advanceUntilIdle()

        assertEquals(listOf(PointD(540.0, 960.0)), controller.focusTaps)
    }

    @Test
    fun `an EV request while previewing reaches the controller`() = runTest(dispatcher) {
        val viewModel = startedViewModel()
        advanceUntilIdle()

        viewModel.onExposureCompensationEvChanged(-1.0)
        advanceUntilIdle()

        assertEquals(listOf(-1.0), controller.requestedEvs)
    }

    @Test
    fun `a previewing camera state becomes a ready ui state`() = runTest(dispatcher) {
        val viewModel = startedViewModel()
        advanceUntilIdle()

        controller.emit(CameraState.Previewing(previewRuntime()))
        advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue("was $state", state is PreviewUiState.Ready)
        assertEquals(IntSize(1920, 1080), (state as PreviewUiState.Ready).previewSize)
    }

    @Test
    fun `a blocked camera state surfaces the underlying failure`() = runTest(dispatcher) {
        val viewModel = startedViewModel()
        advanceUntilIdle()

        val failure = CameraFailure(CameraFailureKind.DISABLED, "policy")
        controller.emit(CameraState.Blocked(failure))
        advanceUntilIdle()

        assertEquals(PreviewUiState.Blocked(failure), viewModel.state.value)
    }

    @Test
    fun `an opening camera state is reported as preparing`() = runTest(dispatcher) {
        val viewModel = startedViewModel()
        advanceUntilIdle()

        controller.emit(CameraState.Opening("0"))
        advanceUntilIdle()

        assertEquals(PreviewUiState.Preparing, viewModel.state.value)
    }

    private fun createViewModel() = CameraPreviewViewModel { controller }

    private fun startedViewModel(): CameraPreviewViewModel {
        val viewModel = createViewModel()
        viewModel.onSurfaceProviderAvailable(FakeSurfaceProvider())
        viewModel.onGeometryChanged(geometry())
        viewModel.onForeground()
        return viewModel
    }

    private fun geometry(displayRotationDegrees: Int = 0) =
        PreviewViewGeometry(IntSize(1080, 1920), displayRotationDegrees)

    private fun previewRuntime() = PreviewRuntime(
        profile = StreamProfile("0", IntSize(1920, 1080)),
        transform =
        requireNotNull(
            PreviewTransformFactory.create(
                activeArray = IntRect(0, 0, 4000, 3000),
                bufferSize = IntSize(1920, 1080),
                viewSize = IntSize(1080, 1920),
                sensorOrientationDegrees = 90,
                displayRotationDegrees = 0,
                mirrored = false
            )
        ),
        exposureCompensationSpec = ExposureCompensationSpec(-20, 20, 1, 3),
        exposureCompensationStep = 0,
        meteringRegion = null
    )
}

private class FakePreviewCameraController : PreviewCameraController {
    private val mutableState = MutableStateFlow<CameraState>(CameraState.Closed)

    override val state: StateFlow<CameraState> = mutableState.asStateFlow()

    var startCount = 0
        private set
    var stopCount = 0
        private set
    var updateGeometryCount = 0
        private set
    var closeCount = 0
        private set
    var lastGeometry: PreviewViewGeometry? = null
        private set

    val focusTaps = mutableListOf<PointD>()
    val requestedEvs = mutableListOf<Double>()

    override suspend fun start(
        geometry: PreviewViewGeometry,
        surfaceProvider: PreviewSurfaceProvider
    ) {
        startCount += 1
        lastGeometry = geometry
    }

    override suspend fun updateGeometry(geometry: PreviewViewGeometry) {
        updateGeometryCount += 1
        lastGeometry = geometry
    }

    override suspend fun stop() {
        stopCount += 1
    }

    override suspend fun focusAt(tap: PointD) {
        focusTaps += tap
    }

    override suspend fun setExposureCompensationEv(requestedEv: Double) {
        requestedEvs += requestedEv
    }

    override fun close() {
        closeCount += 1
    }

    fun emit(state: CameraState) {
        mutableState.value = state
    }
}

private class FakeSurfaceProvider : PreviewSurfaceProvider {
    override suspend fun acquireSurface(bufferSize: IntSize): Surface? = null

    override fun releaseSurface() = Unit
}
