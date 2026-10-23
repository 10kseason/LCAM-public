package dev.lunacam.domain.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraStateMachineTest {
    @Test
    fun `the documented happy path is allowed end to end`() {
        val machine = CameraStateMachine()
        val generation = machine.beginGeneration()

        assertApplied(machine.transition(CameraState.Opening(CAMERA_KEY), generation))
        assertApplied(machine.transition(CameraState.Configuring(PROFILE), generation))
        assertApplied(machine.transition(previewing(), generation))
        assertApplied(machine.transition(CameraState.Closing, generation))
        assertApplied(machine.transition(CameraState.Closed, generation))

        assertEquals(CameraState.Closed, machine.state)
    }

    @Test
    fun `a rotation replaces the preview runtime without reopening the camera`() {
        val machine = previewingMachine()
        val generationBeforeRotation = machine.generation

        val rotated = previewing(displayRotationDegrees = 90)
        assertApplied(machine.transition(rotated, generationBeforeRotation))

        assertEquals(rotated, machine.state)
        assertEquals(generationBeforeRotation, machine.generation)
    }

    @Test
    fun `a callback from a superseded generation cannot change the state`() {
        val machine = CameraStateMachine()
        val staleGeneration = machine.beginGeneration()
        machine.transition(CameraState.Opening(CAMERA_KEY), staleGeneration)
        val currentGeneration = machine.beginGeneration()

        val result = machine.transition(CameraState.Configuring(PROFILE), staleGeneration)

        assertEquals(
            TransitionResult.IgnoredStaleGeneration(staleGeneration, currentGeneration),
            result
        )
        assertEquals(CameraState.Opening(CAMERA_KEY), machine.state)
    }

    @Test
    fun `generation ids increase monotonically`() {
        val machine = CameraStateMachine()

        val first = machine.beginGeneration()
        val second = machine.beginGeneration()
        val third = machine.beginGeneration()

        assertTrue(first < second)
        assertTrue(second < third)
        assertEquals(third, machine.generation)
    }

    @Test
    fun `closed cannot jump straight to previewing`() {
        val machine = CameraStateMachine()
        val generation = machine.beginGeneration()

        val result = machine.transition(previewing(), generation)

        assertEquals(TransitionResult.Rejected(CameraState.Closed, previewing()), result)
        assertEquals(CameraState.Closed, machine.state)
    }

    @Test
    fun `closing only leads to closed`() {
        val machine = previewingMachine()
        val generation = machine.generation
        machine.transition(CameraState.Closing, generation)

        assertRejected(machine.transition(CameraState.Opening(CAMERA_KEY), generation))
        assertRejected(machine.transition(previewing(), generation))
        assertApplied(machine.transition(CameraState.Closed, generation))
    }

    @Test
    fun `permission required leads back to opening once granted`() {
        val machine = CameraStateMachine()
        val generation = machine.beginGeneration()

        assertApplied(machine.transition(CameraState.PermissionRequired, generation))
        assertApplied(machine.transition(CameraState.Opening(CAMERA_KEY), generation))
    }

    @Test
    fun `a blocked camera can be reopened by a user action`() {
        val machine = CameraStateMachine()
        val generation = machine.beginGeneration()
        machine.transition(CameraState.Opening(CAMERA_KEY), generation)
        machine.transition(CameraState.Blocked(FAILURE), generation)

        assertApplied(machine.transition(CameraState.Opening(CAMERA_KEY), generation))
    }

    @Test
    fun `recovery can retry with an increasing attempt count`() {
        val machine = CameraStateMachine()
        val generation = machine.beginGeneration()
        machine.transition(CameraState.Opening(CAMERA_KEY), generation)

        assertApplied(machine.transition(CameraState.Recovering(1, FAILURE), generation))
        assertApplied(machine.transition(CameraState.Recovering(2, FAILURE), generation))
        assertApplied(machine.transition(CameraState.Opening(CAMERA_KEY), generation))
    }

    @Test
    fun `P2 never enters capturing from a state other than previewing`() {
        val machine = CameraStateMachine()
        val generation = machine.beginGeneration()
        machine.transition(CameraState.Opening(CAMERA_KEY), generation)

        assertRejected(machine.transition(CameraState.Capturing("job", 0, 8), generation))
    }

    private fun previewingMachine(): CameraStateMachine {
        val machine = CameraStateMachine()
        val generation = machine.beginGeneration()
        machine.transition(CameraState.Opening(CAMERA_KEY), generation)
        machine.transition(CameraState.Configuring(PROFILE), generation)
        machine.transition(previewing(), generation)
        return machine
    }

    private fun previewing(displayRotationDegrees: Int = 0): CameraState.Previewing =
        CameraState.Previewing(
            PreviewRuntime(
                profile = PROFILE,
                transform =
                requireNotNull(
                    PreviewTransformFactory.create(
                        activeArray = IntRect(0, 0, 4000, 3000),
                        bufferSize = IntSize(1920, 1080),
                        viewSize = IntSize(1080, 1920),
                        sensorOrientationDegrees = 90,
                        displayRotationDegrees = displayRotationDegrees,
                        mirrored = false
                    )
                ),
                exposureCompensationSpec = ExposureCompensationSpec(-20, 20, 1, 3),
                exposureCompensationStep = 0,
                meteringRegion = null
            )
        )

    private fun assertApplied(result: TransitionResult) {
        assertTrue("expected Applied but was $result", result is TransitionResult.Applied)
    }

    private fun assertRejected(result: TransitionResult) {
        assertTrue("expected Rejected but was $result", result is TransitionResult.Rejected)
    }

    private companion object {
        const val CAMERA_KEY = "0"
        val PROFILE = StreamProfile(CAMERA_KEY, IntSize(1920, 1080))
        val FAILURE = CameraFailure(CameraFailureKind.DEVICE)
    }
}
