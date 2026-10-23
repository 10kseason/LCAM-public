package dev.lunacam.camera.camera2

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.os.Build
import dev.lunacam.domain.capability.CAPABILITY_REPORT_SCHEMA_VERSION
import dev.lunacam.domain.capability.CameraRoleScorer
import dev.lunacam.domain.capability.CapabilityCacheKey
import dev.lunacam.domain.capability.CapabilityDeviceSummary
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
    private val cache = CapabilityDocumentCache(applicationContext.filesDir)

    override fun loadCached(): CapabilitySnapshot? =
        cache.read(currentCacheKey())?.let { documents ->
            CapabilitySnapshot(
                report = null,
                documents = documents,
                source = CapabilityReportSource.CACHE
            )
        }

    override fun refresh(): CapabilitySnapshot {
        check(
            applicationContext.checkSelfPermission(Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        ) {
            "Camera permission is required before capability discovery"
        }

        val cacheKey = currentCacheKey()
        val report =
            CapabilityReport(
                schemaVersion = CAPABILITY_REPORT_SCHEMA_VERSION,
                createdAtUtc = Instant.now().toString(),
                cacheKeyHash = cacheKey,
                device =
                CapabilityDeviceSummary(
                    manufacturer = Build.MANUFACTURER.orUnknown(),
                    model = Build.MODEL.orUnknown(),
                    sdkInt = Build.VERSION.SDK_INT,
                    buildFingerprintHash = CapabilityCacheKey.fingerprintHash(Build.FINGERPRINT)
                ),
                cameras = CameraRoleScorer.assignRoles(collector.collect(cameraManager))
            )
        val documents = renderer.render(report)
        cache.write(cacheKey, documents)
        return CapabilitySnapshot(
            report = report,
            documents = documents,
            source = CapabilityReportSource.LIVE
        )
    }

    private fun currentCacheKey(): String = CapabilityCacheKey.create(
        schemaVersion = CAPABILITY_REPORT_SCHEMA_VERSION,
        sdkInt = Build.VERSION.SDK_INT,
        buildFingerprint = Build.FINGERPRINT
    )

    private fun String?.orUnknown(): String =
        this?.trim().takeUnless { it.isNullOrEmpty() } ?: "unknown"
}
