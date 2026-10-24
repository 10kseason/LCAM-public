package dev.lunacam.camera.camera2

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import dev.lunacam.domain.capability.CAPABILITY_REPORT_SCHEMA_VERSION
import dev.lunacam.domain.capability.CameraRoleScorer
import dev.lunacam.domain.capability.CapabilityReport
import dev.lunacam.domain.capability.CapabilityReportRenderer
import dev.lunacam.domain.capability.CapabilityReportSource
import dev.lunacam.domain.capability.CapabilityRepository
import dev.lunacam.domain.capability.CapabilitySnapshot
import java.time.Instant

class AndroidCapabilityRepository(context: Context) : CapabilityRepository {
    private val applicationContext = context.applicationContext
    private val cameraManager = applicationContext.getSystemService(CameraManager::class.java)
    private val collector = CameraCharacteristicsCollector()
    private val renderer = CapabilityReportRenderer()

    // Keep fallback data only within this repository lifetime; never identify firmware to cache it.
    private var lastSnapshot: CapabilitySnapshot? = null

    override fun loadCached(): CapabilitySnapshot? =
        lastSnapshot?.copy(source = CapabilityReportSource.CACHE)

    override fun refresh(): CapabilitySnapshot {
        check(
            applicationContext.checkSelfPermission(Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        ) {
            "Camera permission is required before capability discovery"
        }

        val report =
            CapabilityReport(
                schemaVersion = CAPABILITY_REPORT_SCHEMA_VERSION,
                createdAtUtc = Instant.now().toString(),
                cameras = CameraRoleScorer.assignRoles(collector.collect(cameraManager))
            )
        val documents = renderer.render(report)
        return CapabilitySnapshot(
            report = report,
            documents = documents,
            source = CapabilityReportSource.LIVE
        ).also { lastSnapshot = it }
    }
}
