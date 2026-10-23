package dev.lunacam.domain.capability

import kotlin.math.abs
import kotlin.math.roundToInt

object CameraRoleScorer {
    fun assignRoles(cameras: List<CameraCapabilityProfile>): List<CameraCapabilityProfile> {
        val backCandidates = cameras.filter { it.roleEvidence.lensFacing == LensFacing.BACK }
        val mainWideAlias = backCandidates.maxByOrNull(::wideCandidateScore)?.cameraAlias

        return cameras.map { camera ->
            camera.copy(roleAssignment = assignmentFor(camera, camera.cameraAlias == mainWideAlias))
        }
    }

    private fun assignmentFor(
        camera: CameraCapabilityProfile,
        isMainWide: Boolean
    ): CameraRoleAssignment {
        val evidence = camera.roleEvidence
        val equivalentFocalLength = equivalentFocalLengthMm(evidence)
        val score = wideCandidateScore(camera)

        if (evidence.lensFacing == LensFacing.FRONT) {
            return CameraRoleAssignment(
                role = CameraRole.FRONT,
                score = score,
                confidence = RoleConfidence.HIGH,
                reasons = listOf("framework reports a front-facing lens")
            )
        }
        if (evidence.lensFacing == LensFacing.EXTERNAL) {
            return CameraRoleAssignment(
                role = CameraRole.EXTERNAL,
                score = score,
                confidence = RoleConfidence.HIGH,
                reasons = listOf("framework reports an external camera")
            )
        }
        if (evidence.lensFacing != LensFacing.BACK) {
            return CameraRoleAssignment(
                role = CameraRole.UNKNOWN,
                score = score,
                confidence = RoleConfidence.LOW,
                reasons = listOf("lens facing metadata is missing or unknown")
            )
        }

        val reasons = buildList {
            if (evidence.hasRawCapability) add("RAW capability advertised")
            if (evidence.isLogicalMultiCamera) add("logical multi-camera capability advertised")
            equivalentFocalLength?.let {
                add("estimated ${"%.1f".format(it)}mm full-frame equivalent")
            }
            evidence.sensorWidthMm?.let { add("sensor width ${"%.2f".format(it)}mm") }
        }

        val role =
            when {
                isMainWide -> CameraRole.BACK_WIDE
                equivalentFocalLength != null && equivalentFocalLength <= 20.0 ->
                    CameraRole.BACK_ULTRAWIDE
                equivalentFocalLength != null && equivalentFocalLength >= 45.0 ->
                    CameraRole.BACK_TELEPHOTO
                else -> CameraRole.BACK_OTHER
            }
        val confidence =
            when {
                isMainWide && evidence.hasRawCapability && equivalentFocalLength != null ->
                    RoleConfidence.HIGH
                equivalentFocalLength != null -> RoleConfidence.MEDIUM
                else -> RoleConfidence.LOW
            }

        return CameraRoleAssignment(
            role = role,
            score = score,
            confidence = confidence,
            reasons = reasons.ifEmpty { listOf("insufficient optical metadata") }
        )
    }

    private fun wideCandidateScore(camera: CameraCapabilityProfile): Int {
        val evidence = camera.roleEvidence
        if (evidence.lensFacing != LensFacing.BACK) return -10_000

        var score = 0
        if (evidence.hasRawCapability) score += 100
        if (evidence.isLogicalMultiCamera) score += 30
        if (evidence.hardwareLevel != null && evidence.hardwareLevel != "LEGACY") score += 10

        equivalentFocalLengthMm(evidence)?.let { equivalent ->
            score += (40.0 - abs(equivalent - 28.0) * 1.5).coerceAtLeast(0.0).roundToInt()
        }
        val sensorArea =
            if (evidence.sensorWidthMm != null && evidence.sensorHeightMm != null) {
                evidence.sensorWidthMm * evidence.sensorHeightMm
            } else {
                null
            }
        sensorArea?.let { score += (it * 0.8).coerceAtMost(30.0).roundToInt() }
        return score
    }

    private fun equivalentFocalLengthMm(evidence: CameraRoleEvidence): Double? {
        val sensorWidth = evidence.sensorWidthMm?.takeIf { it > 0.0 } ?: return null
        return evidence.focalLengthsMm
            .asSequence()
            .filter { it > 0.0 }
            .map { focalLength -> focalLength / sensorWidth * FULL_FRAME_WIDTH_MM }
            .minByOrNull { equivalent -> abs(equivalent - 28.0) }
    }

    private const val FULL_FRAME_WIDTH_MM = 36.0
}
