package dev.lunacam.app

import android.content.Context
import android.hardware.camera2.CameraManager
import dev.lunacam.camera.camera2.AndroidCapabilityRepository
import dev.lunacam.camera.camera2.preview.Camera2PreviewController
import dev.lunacam.camera.camera2.preview.PreviewCameraControllerFactory
import dev.lunacam.feature.diagnostics.CapabilityExplorerController
import java.util.concurrent.Executors

class AppGraph(context: Context) : AutoCloseable {
    val capabilityExplorerController =
        CapabilityExplorerController(
            repository = AndroidCapabilityRepository(context)
        )
    val diagnosticExportWriter = DiagnosticExportWriter(context)

    private val cameraManager = context.getSystemService(CameraManager::class.java)

    // DESIGN.md section 6.5: Camera2 callbacks are ordered by a single thread.
    private val cameraExecutor =
        Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "LunaCam-Camera") }

    val previewCameraControllerFactory =
        PreviewCameraControllerFactory { scope ->
            Camera2PreviewController(
                cameraManager = cameraManager,
                scope = scope,
                cameraExecutor = cameraExecutor
            )
        }

    override fun close() {
        capabilityExplorerController.close()
        diagnosticExportWriter.close()
        cameraExecutor.shutdownNow()
    }
}
