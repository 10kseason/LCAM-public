package dev.lunacam.app.photon

import android.app.Application
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class PhotonDiagnosticsModel(application: Application) : AndroidViewModel(application) {
    private val context = application
    var json by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf("공개 카메라 기능을 조회합니다. 사진을 촬영하지 않습니다.")
        private set
    private var pendingExport: String? = null

    fun scan() {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try {
                val report = PhotonCapabilityScanner(context).scan()
                json = report.toString(2)
                message =
                    "카메라 ${report.getJSONArray(
                        "cameras"
                    ).length()}개 · 조회 주의 ${report.getJSONArray("probeErrors").length()}건"
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                json = null
                message = "진단 실패 (${error.javaClass.simpleName}). 권한을 확인하고 다시 조회해 주세요."
            } finally {
                busy = false
            }
        }
    }

    fun prepareExport() {
        pendingExport = json
    }

    fun save(uri: Uri?) {
        val content = pendingExport
        pendingExport = null
        if (uri == null || content == null) return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    checkNotNull(context.contentResolver.openOutputStream(uri, "wt"))
                        .bufferedWriter(Charsets.UTF_8).use { it.write(content) }
                }
                message = "capabilities-v2.json 저장 완료"
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                message = "진단 저장 실패. 다른 위치를 선택해 주세요."
            }
        }
    }

    fun share(onReady: (Intent) -> Unit) {
        val content = json ?: return
        viewModelScope.launch {
            try {
                val uri = withContext(Dispatchers.IO) {
                    val directory = File(context.cacheDir, "photon-reports").apply { mkdirs() }
                    // Expire only reports created by this feature, never user-selected files.
                    directory.listFiles()?.filter {
                        it.isFile &&
                            it.lastModified() < System.currentTimeMillis() - 86_400_000
                    }
                        ?.forEach { it.delete() }
                    val file = File(directory, "capabilities-v2-${UUID.randomUUID()}.json")
                    file.writeText(content, Charsets.UTF_8)
                    FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.photon-reports",
                        file
                    )
                }
                val intent = Intent(Intent.ACTION_SEND).setType("application/json")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .apply { clipData = ClipData.newRawUri("capabilities-v2", uri) }
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                onReady(Intent.createChooser(intent, "진단 JSON 공유"))
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                message = "공유창을 열지 못했습니다. JSON 저장을 이용해 주세요."
            }
        }
    }
}

@Composable
fun PhotonDiagnosticsScreen(onBack: () -> Unit, model: PhotonDiagnosticsModel = viewModel()) {
    val context = LocalContext.current
    val launcher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/json"),
            model::save
        )
    LaunchedEffect(Unit) { if (model.json == null) model.scan() }
    BackHandler(onBack = onBack)
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().safeDrawingPadding().padding(
                    16.dp
                ).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("PHOTON · 기능 진단", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "공개 API 조회 결과입니다. supported는 기능 노출을 의미하며 촬영 조합의 성공을 보장하지 않습니다. 확장 촬영은 아직 제공하지 않습니다."
                )
                Text(if (model.busy) "기능 조회 중…" else model.message)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onBack) { Text("뒤로") }
                    Button(onClick = model::scan, enabled = !model.busy) { Text("새로 조회") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            model.prepareExport()
                            launcher.launch("capabilities-v2.json")
                        },
                        enabled =
                        model.json != null && !model.busy
                    ) { Text("JSON 저장") }
                    OutlinedButton(
                        onClick = { model.share(context::startActivity) },
                        enabled =
                        model.json != null && !model.busy
                    ) { Text("공유") }
                }
                model.json?.let { text ->
                    val report = JSONObject(text)
                    val cameras = report.getJSONArray("cameras")
                    for (index in 0 until cameras.length()) {
                        val camera = cameras.getJSONObject(index)
                        Text(
                            "${camera.getString(
                                "facing"
                            )} · ID ${camera.getString(
                                "id"
                            )} · ${camera.getString("hardwareLevel")}",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            "RAW: ${camera.getJSONObject(
                                "formats"
                            ).opt(
                                "RAW_SENSOR"
                            )}\n수동: ${camera.getJSONObject(
                                "manual"
                            )}\n확장: ${camera.getJSONObject("extensions")}"
                        )
                    }
                    Text(text, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
