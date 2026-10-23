package dev.lunacam.feature.camera

import dev.lunacam.domain.preview.CameraFailure
import dev.lunacam.domain.preview.ExposureCompensationSpec
import dev.lunacam.domain.preview.IntSize
import dev.lunacam.domain.preview.PointD

/** What the preview screen draws. P2 has no shutter, so there is no capture state here. */
sealed interface PreviewUiState {
    data object Idle : PreviewUiState

    data object Preparing : PreviewUiState

    /**
     * [displayAspectRatio] is width over height of the area the preview occupies. The screen must
     * lay the preview surface out at exactly this ratio: the sensor frame is letterboxed rather
     * than cropped, and if the view ignored the ratio the picture and the tap mapping would
     * disagree.
     */
    data class Ready(
        val previewSize: IntSize,
        val displayAspectRatio: Float,
        val exposureCompensation: ExposureCompensationSpec,
        val exposureCompensationStep: Int,
        val focusIndicator: PointD?
    ) : PreviewUiState

    data class Recovering(val attempt: Int, val cause: CameraFailure) : PreviewUiState

    /**
     * The message shown here is derived from [CameraFailure.kind] in the screen. The retry
     * budget that produced the block stays in [dev.lunacam.domain.preview.RecoveryPolicy] and is
     * never re-derived by the UI.
     */
    data class Blocked(val cause: CameraFailure) : PreviewUiState
}
