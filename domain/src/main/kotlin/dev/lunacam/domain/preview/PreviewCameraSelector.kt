package dev.lunacam.domain.preview

import dev.lunacam.domain.capability.CameraRole
import dev.lunacam.domain.capability.CameraRoleAssignment
import dev.lunacam.domain.capability.CameraRoleEvidence
import dev.lunacam.domain.capability.LensFacing

/**
 * A preview candidate.
 *
 * [cameraKey] is opaque to the domain. The Camera2 adapter passes the real camera id so the
 * preview can be opened, while the redacted `camera-N` alias stays the export-only identifier of
 * the P1 capability report.
 */
data class PreviewCameraCandidate(
    val cameraKey: String,
    val evidence: CameraRoleEvidence,
    val assignment: CameraRoleAssignment
)

/**
 * Picks the single camera P2 previews.
 *
 * FR-PRV-003 limits v0.1 to the back main 1x lens, so the highest scoring `BACK_WIDE` candidate
 * wins. A device whose role scoring found no `BACK_WIDE` still gets a preview from the best
 * back-facing candidate rather than a black screen.
 */
object PreviewCameraSelector {
    private val bestFirst =
        compareByDescending<PreviewCameraCandidate> { it.assignment.score }
            .thenBy { it.cameraKey }

    fun selectMainBack(candidates: List<PreviewCameraCandidate>): PreviewCameraCandidate? {
        val backWide = candidates.filter { it.assignment.role == CameraRole.BACK_WIDE }
        if (backWide.isNotEmpty()) return backWide.minWith(bestFirst)

        val backFacing = candidates.filter { it.evidence.lensFacing == LensFacing.BACK }
        if (backFacing.isNotEmpty()) return backFacing.minWith(bestFirst)

        return null
    }
}
