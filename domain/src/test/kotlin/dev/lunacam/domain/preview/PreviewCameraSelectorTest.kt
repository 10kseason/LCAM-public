package dev.lunacam.domain.preview

import dev.lunacam.domain.capability.CameraRole
import dev.lunacam.domain.capability.CameraRoleAssignment
import dev.lunacam.domain.capability.CameraRoleEvidence
import dev.lunacam.domain.capability.LensFacing
import dev.lunacam.domain.capability.RoleConfidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PreviewCameraSelectorTest {
    @Test
    fun `the highest scoring back wide camera wins`() {
        val candidates =
            listOf(
                candidate("0", CameraRole.BACK_ULTRAWIDE, score = 99),
                candidate("1", CameraRole.BACK_WIDE, score = 80),
                candidate("2", CameraRole.BACK_WIDE, score = 95)
            )

        assertEquals("2", PreviewCameraSelector.selectMainBack(candidates)?.cameraKey)
    }

    @Test
    fun `a score tie is broken deterministically by camera key`() {
        val candidates =
            listOf(
                candidate("3", CameraRole.BACK_WIDE, score = 90),
                candidate("1", CameraRole.BACK_WIDE, score = 90)
            )

        assertEquals("1", PreviewCameraSelector.selectMainBack(candidates)?.cameraKey)
    }

    @Test
    fun `a device with no back wide role still previews the best back camera`() {
        val candidates =
            listOf(
                candidate("0", CameraRole.BACK_OTHER, score = 40),
                candidate("1", CameraRole.BACK_TELEPHOTO, score = 70),
                candidate("2", CameraRole.FRONT, score = 99, facing = LensFacing.FRONT)
            )

        assertEquals("1", PreviewCameraSelector.selectMainBack(candidates)?.cameraKey)
    }

    @Test
    fun `a front only device has no preview candidate`() {
        val candidates =
            listOf(candidate("0", CameraRole.FRONT, score = 99, facing = LensFacing.FRONT))

        assertNull(PreviewCameraSelector.selectMainBack(candidates))
    }

    @Test
    fun `an empty candidate list has no preview candidate`() {
        assertNull(PreviewCameraSelector.selectMainBack(emptyList()))
    }

    private fun candidate(
        cameraKey: String,
        role: CameraRole,
        score: Int,
        facing: LensFacing = LensFacing.BACK
    ): PreviewCameraCandidate = PreviewCameraCandidate(
        cameraKey = cameraKey,
        evidence =
        CameraRoleEvidence(
            lensFacing = facing,
            focalLengthsMm = listOf(6.3),
            sensorWidthMm = 8.1,
            sensorHeightMm = 6.1,
            hasRawCapability = true,
            isLogicalMultiCamera = true,
            hardwareLevel = "LEVEL_3"
        ),
        assignment =
        CameraRoleAssignment(
            role = role,
            score = score,
            confidence = RoleConfidence.HIGH,
            reasons = emptyList()
        )
    )
}
