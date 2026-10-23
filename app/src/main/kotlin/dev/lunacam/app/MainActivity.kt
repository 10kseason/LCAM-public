package dev.lunacam.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import dev.lunacam.feature.diagnostics.CameraPermissionUiState
import dev.lunacam.feature.diagnostics.CameraPermissionUiStateResolver

class MainActivity : ComponentActivity() {
    private var permissionState by mutableStateOf(CameraPermissionUiState.NOT_REQUESTED)
    private val permissionPreferences by lazy {
        getSharedPreferences("camera-permission", MODE_PRIVATE)
    }
    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
            updatePermissionState()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        updatePermissionState()
        setContent {
            LunaCamApp(
                permissionState = permissionState,
                onRequestPermission = ::requestCameraPermission,
                onOpenSettings = ::openApplicationSettings,
                onExit = ::finish
            )
        }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionState()
    }

    private fun requestCameraPermission() {
        permissionPreferences.edit { putBoolean("requested", true) }
        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun updatePermissionState() {
        permissionState = CameraPermissionUiStateResolver.resolve(
            isGranted =
            checkSelfPermission(
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED,
            hasRequested = permissionPreferences.getBoolean("requested", false),
            shouldShowRationale = shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
        )
    }

    private fun openApplicationSettings() {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null)
            )
        )
    }
}
