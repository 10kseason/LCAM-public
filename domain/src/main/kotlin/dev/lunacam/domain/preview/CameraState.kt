package dev.lunacam.domain.preview

/**
 * The stream configuration a camera generation is being configured for.
 *
 * P2 configures a preview-only session (DESIGN.md section 9.2, output 1). P3 extends this with
 * the RAW `ImageReader` output rather than replacing it.
 */
data class StreamProfile(val cameraKey: String, val previewSize: IntSize)

/** Everything the UI needs while a preview is running. */
data class PreviewRuntime(
    val profile: StreamProfile,
    val transform: PreviewTransform,
    val exposureCompensationSpec: ExposureCompensationSpec,
    val exposureCompensationStep: Int,
    val meteringRegion: MeteringRegion?
)

/**
 * Camera state per DESIGN.md section 7.1.
 *
 * [Capturing] is part of the documented hierarchy but P2 creates no path into it; its producer
 * arrives with the burst pipeline in P3.
 */
sealed interface CameraState {
    data object Closed : CameraState

    data object PermissionRequired : CameraState

    data class Opening(val cameraKey: String) : CameraState

    data class Configuring(val profile: StreamProfile) : CameraState

    data class Previewing(val runtime: PreviewRuntime) : CameraState

    data class Capturing(val jobId: String, val received: Int, val expected: Int) : CameraState

    data class Recovering(val attempt: Int, val cause: CameraFailure) : CameraState

    data class Blocked(val cause: CameraFailure) : CameraState

    data object Closing : CameraState
}
