package dev.lunacam.feature.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.lunacam.domain.capability.CameraCapabilityProfile
import dev.lunacam.domain.capability.CapabilitySnapshot

@Composable
fun CapabilityExplorerScreen(
    nativeVersionLabel: String,
    permissionState: CameraPermissionUiState,
    explorerState: CapabilityExplorerState,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
    onRefresh: () -> Unit,
    onExportJson: (String) -> Unit,
    onExportMarkdown: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    MaterialTheme {
        Surface(modifier = modifier.fillMaxSize()) {
            when (permissionState) {
                CameraPermissionUiState.GRANTED ->
                    GrantedContent(
                        nativeVersionLabel = nativeVersionLabel,
                        explorerState = explorerState,
                        onRefresh = onRefresh,
                        onExportJson = onExportJson,
                        onExportMarkdown = onExportMarkdown
                    )
                CameraPermissionUiState.NOT_REQUESTED ->
                    PermissionContent(
                        title = "LUNA PHOTON 카메라 권한",
                        message = "카메라 기능 진단, 미리보기와 촬영에 권한을 사용합니다. " +
                            "사진은 AUTO 화면에서 촬영 버튼을 누를 때만 기기에 저장됩니다.",
                        actionLabel = "카메라 권한 허용",
                        onAction = onRequestPermission
                    )
                CameraPermissionUiState.RATIONALE ->
                    PermissionContent(
                        title = "카메라 권한이 필요합니다",
                        message =
                        "빈 화면 대신 권한이 필요한 이유를 먼저 보여드립니다. " +
                            "진단은 공개 카메라 특성만 읽으며, 사진은 촬영 버튼을 눌러야 저장됩니다.",
                        actionLabel = "다시 요청",
                        onAction = onRequestPermission
                    )
                CameraPermissionUiState.SETTINGS_REQUIRED ->
                    PermissionContent(
                        title = "설정에서 권한을 허용해주세요",
                        message = "권한 요청을 반복하지 않습니다. 앱 설정에서 카메라 권한을 허용한 뒤 돌아오면 자동으로 다시 확인합니다.",
                        actionLabel = "앱 설정 열기",
                        onAction = onOpenSettings
                    )
            }
        }
    }
}

@Composable
private fun PermissionContent(
    title: String,
    message: String,
    actionLabel: String,
    onAction: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = title, style = MaterialTheme.typography.headlineMedium)
        Text(
            text = message,
            modifier = Modifier.padding(top = 16.dp, bottom = 24.dp),
            style = MaterialTheme.typography.bodyLarge
        )
        Button(onClick = onAction) {
            Text(actionLabel)
        }
    }
}

@Composable
private fun GrantedContent(
    nativeVersionLabel: String,
    explorerState: CapabilityExplorerState,
    onRefresh: () -> Unit,
    onExportJson: (String) -> Unit,
    onExportMarkdown: (String) -> Unit
) {
    when (explorerState) {
        CapabilityExplorerState.Idle,
        CapabilityExplorerState.Loading ->
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator()
                Text(
                    text = "공개 CameraCharacteristics를 읽는 중…",
                    modifier = Modifier.padding(top = 16.dp)
                )
            }
        is CapabilityExplorerState.Ready ->
            ReportContent(
                nativeVersionLabel = nativeVersionLabel,
                snapshot = explorerState.snapshot,
                isRefreshing = explorerState.isRefreshing,
                warning = null,
                onRefresh = onRefresh,
                onExportJson = onExportJson,
                onExportMarkdown = onExportMarkdown
            )
        is CapabilityExplorerState.Failure -> {
            val cached = explorerState.cachedSnapshot
            if (cached == null) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("카메라 진단을 완료하지 못했습니다.", style = MaterialTheme.typography.titleLarge)
                    Text(explorerState.message, modifier = Modifier.padding(vertical = 16.dp))
                    Button(onClick = onRefresh) { Text("다시 진단") }
                }
            } else {
                ReportContent(
                    nativeVersionLabel = nativeVersionLabel,
                    snapshot = cached,
                    isRefreshing = false,
                    warning = explorerState.message,
                    onRefresh = onRefresh,
                    onExportJson = onExportJson,
                    onExportMarkdown = onExportMarkdown
                )
            }
        }
    }
}

@Composable
private fun ReportContent(
    nativeVersionLabel: String,
    snapshot: CapabilitySnapshot,
    isRefreshing: Boolean,
    warning: String?,
    onRefresh: () -> Unit,
    onExportJson: (String) -> Unit,
    onExportMarkdown: (String) -> Unit
) {
    val report = snapshot.report
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column(modifier = Modifier.padding(top = 24.dp)) {
                Text("LunaCam Capability Explorer", style = MaterialTheme.typography.headlineSmall)
                Text(nativeVersionLabel, style = MaterialTheme.typography.bodySmall)
                Text(
                    text =
                    when {
                        warning != null -> warning
                        isRefreshing -> "캐시된 리포트를 표시하며 현재 기기 값을 갱신 중입니다."
                        report == null -> "캐시된 진단 리포트"
                        else -> "${report.cameras.size}개 카메라 후보 · ${report.createdAtUtc}"
                    },
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
        item {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onRefresh,
                    enabled = !isRefreshing,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("새로 진단")
                }
                OutlinedButton(
                    onClick = { onExportJson(snapshot.documents.json) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("JSON 저장")
                }
                OutlinedButton(
                    onClick = { onExportMarkdown(snapshot.documents.markdown) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Markdown 저장")
                }
            }
        }
        if (report == null) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text =
                        snapshot.documents.markdown
                            .lineSequence()
                            .take(60)
                            .joinToString("\n"),
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        } else {
            items(report.cameras, key = CameraCapabilityProfile::cameraAlias) { camera ->
                CameraCard(camera)
            }
        }
        item {
            Text(
                text = "P1은 진단 전용입니다. 프리뷰와 촬영은 P2 이후에 구현됩니다.",
                modifier = Modifier.padding(bottom = 24.dp),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun CameraCard(camera: CameraCapabilityProfile) {
    val rawStreams = camera.streams.filter { it.formatName.startsWith("RAW") }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "${camera.cameraAlias} · ${camera.roleAssignment.role.name}",
                style = MaterialTheme.typography.titleMedium
            )
            Text("신뢰도 ${camera.roleAssignment.confidence.name} · 점수 ${camera.roleAssignment.score}")
            Text(
                "Lens ${camera.roleEvidence.lensFacing.name} · " +
                    (camera.roleEvidence.hardwareLevel ?: "metadata missing")
            )
            Text("Capabilities: ${camera.capabilities.ifEmpty { listOf("none") }.joinToString()}")
            Text(
                text =
                if (rawStreams.isEmpty()) {
                    "RAW sizes: none"
                } else {
                    "RAW sizes: " +
                        rawStreams.joinToString { stream ->
                            val sizes = stream.sizes.joinToString { "${it.width}x${it.height}" }
                            "${stream.formatName} $sizes"
                        }
                }
            )
            Text("Missing keys ${camera.missingKeys.size} · Read errors ${camera.readErrors.size}")
        }
    }
}
