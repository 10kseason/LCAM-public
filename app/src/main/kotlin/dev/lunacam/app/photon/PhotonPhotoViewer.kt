package dev.lunacam.app.photon

import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.util.Size
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val MAX_RECEIPT_BYTES = 256 * 1024
private const val JPEG_MIME = "image/jpeg"
private const val DNG_MIME = "image/x-adobe-dng"

data class RecentRawPhoto(val uri: Uri, val mimeType: String)

/** Per-mode receipt first, with a filtered legacy receipt for upgrades from older builds. */
suspend fun loadRecentRawPhoto(context: Context, naturalAuto: Boolean): RecentRawPhoto? =
    withContext(Dispatchers.IO) {
        val name = if (naturalAuto) {
            "photon-raw-auto-last-save.json"
        } else {
            "photon-raw-manual-last-save.json"
        }
        listOf(name, "photon-raw-last-save.json").firstNotNullOfOrNull { candidate ->
            readBoundedPhotoReceipt(File(context.filesDir, candidate))?.let {
                parseRecentRawPhoto(it, naturalAuto)
            }
        }
    }

internal fun parseRecentRawPhoto(json: String, naturalAuto: Boolean): RecentRawPhoto? =
    runCatching {
        if (json.length > MAX_RECEIPT_BYTES) return null
        val receipt = JSONObject(json)
        val pipeline = receipt.optString("pipeline")
        if (naturalAuto) {
            if (pipeline !in setOf("raw_auto_single_v1", "raw_auto_hdr_v2")) return null
            val mimeType = receipt.optString("mimeType")
            if (mimeType != JPEG_MIME && mimeType != DNG_MIME) return null
            val uri = Uri.parse(receipt.optString("output"))
            RecentRawPhoto(uri, mimeType).takeIf { isPhotonMediaImageUri(uri) }
        } else {
            if (pipeline.isNotEmpty()) return null
            val outputs = receipt.optJSONArray("outputs") ?: return null
            if (outputs.length() != 1) return null
            val uri = Uri.parse(outputs.optString(0))
            RecentRawPhoto(uri, DNG_MIME).takeIf { isPhotonMediaImageUri(uri) }
        }
    }.getOrNull()

private fun readBoundedPhotoReceipt(receipt: File): String? = runCatching {
    if (!receipt.isFile || receipt.length() !in 1..MAX_RECEIPT_BYTES.toLong()) return null
    val bytes = receipt.inputStream().use { it.readNBytes(MAX_RECEIPT_BYTES + 1) }
    if (bytes.size > MAX_RECEIPT_BYTES) return null
    bytes.toString(Charsets.UTF_8)
}.getOrNull()

/** Read only our last-save receipt. No MediaStore enumeration or image decoding occurs here. */
suspend fun loadRecentPhotonPhoto(context: Context): Uri? = withContext(Dispatchers.IO) {
    readRecentPhotonPhoto(File(context.filesDir, "photon-auto-last-save.json"))
}

internal fun readRecentPhotonPhoto(receipt: File): Uri? =
    readBoundedPhotoReceipt(receipt)?.let(::parseRecentPhotonPhoto)

internal fun parseRecentPhotonPhoto(json: String): Uri? = runCatching {
    if (json.length > MAX_RECEIPT_BYTES) return null
    val value = JSONObject(json).optString("output")
    Uri.parse(value).takeIf(::isPhotonMediaImageUri)
}.getOrNull()

/** Only individual MediaStore image rows can receive an external read grant. */
internal fun isPhotonMediaImageUri(uri: Uri): Boolean {
    val parts = uri.pathSegments
    return uri.scheme == "content" && uri.authority == "media" &&
        uri.query == null && uri.fragment == null && parts.size == 4 &&
        parts[0].matches(Regex("[A-Za-z0-9_-]+")) && parts[1] == "images" &&
        parts[2] == "media" && parts[3].toLongOrNull()?.let { it > 0 } == true
}

internal fun photonImageViewIntent(
    uri: Uri,
    component: ComponentName? = null,
    mimeType: String = JPEG_MIME
): Intent {
    require(isPhotonMediaImageUri(uri))
    require(mimeType == JPEG_MIME || mimeType == DNG_MIME)
    return Intent(Intent.ACTION_VIEW).setDataAndType(uri, mimeType).apply {
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        clipData = ClipData.newRawUri("PHOTON photo", uri)
        this.component = component
    }
}

internal data class PhotonViewerApp(val component: ComponentName, val label: String)

/** User-selected external viewer; no installed-app query occurs until the user opens its picker. */
class PhotonPhotoViewer(appContext: Context) {
    private val context = appContext.applicationContext
    private val preferences = context.getSharedPreferences(
        "photon-photo-viewer",
        Context.MODE_PRIVATE
    )
    private var selectedComponent = preferences.getString("component", null)
        ?.let(ComponentName::unflattenFromString)
    var selectedName by mutableStateOf(
        if (selectedComponent == null) "시스템 선택" else preferences.getString("label", null) ?: "선택한 앱"
    )
        private set
    internal var pickerVisible by mutableStateOf(false)
    internal var pickerMimeType by mutableStateOf(JPEG_MIME)

    internal fun showPicker(mimeType: String) {
        if (mimeType != JPEG_MIME && mimeType != DNG_MIME) return
        pickerMimeType = mimeType
        pickerVisible = true
    }

    internal fun select(app: PhotonViewerApp?) {
        selectedComponent = app?.component
        selectedName = app?.label ?: "시스템 선택"
        preferences.edit().apply {
            if (app == null) {
                remove("component").remove("label")
            } else {
                putString(
                    "component",
                    app.component.flattenToString()
                ).putString("label", app.label)
            }
        }.apply()
        pickerVisible = false
    }

    internal suspend fun apps(mimeType: String = JPEG_MIME): List<PhotonViewerApp> =
        withContext(Dispatchers.IO) {
            require(mimeType == JPEG_MIME || mimeType == DNG_MIME)
            // A metadata-only sample URI lets the user select a viewer before the first photo.
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(Uri.parse("content://media/external/images/media/1"), mimeType)
            context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
                .mapNotNull { resolved ->
                    val activity = resolved.activityInfo ?: return@mapNotNull null
                    if (!activity.exported || !activity.enabled ||
                        !activity.applicationInfo.enabled
                    ) {
                        return@mapNotNull null
                    }
                    PhotonViewerApp(
                        ComponentName(activity.packageName, activity.name),
                        resolved.loadLabel(context.packageManager).toString()
                    )
                }.distinctBy { it.component }.sortedBy { it.label.lowercase() }
        }

    internal suspend fun thumbnail(uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        if (!isPhotonMediaImageUri(uri)) return@withContext null
        runCatching { context.contentResolver.loadThumbnail(uri, Size(128, 128), null) }.getOrNull()
    }

    fun open(uri: Uri, mimeType: String = JPEG_MIME) {
        if (!isPhotonMediaImageUri(uri) || (mimeType != JPEG_MIME && mimeType != DNG_MIME)) return
        val selected = selectedComponent
        if (selected != null) {
            val direct = photonImageViewIntent(uri, selected, mimeType)
            val ready = runCatching {
                val activity = context.packageManager.queryIntentActivities(
                    photonImageViewIntent(uri, mimeType = mimeType),
                    PackageManager.MATCH_DEFAULT_ONLY
                ).firstOrNull {
                    val info = it.activityInfo
                    info != null && ComponentName(info.packageName, info.name) == selected
                }?.activityInfo
                activity != null && activity.exported && activity.enabled &&
                    activity.applicationInfo.enabled
            }.getOrDefault(false)
            if (ready && runCatching { context.startActivity(direct) }.isSuccess) return
            // Uninstalled, disabled or failing handlers must not remain a sticky selection.
            select(null)
        }
        val view = photonImageViewIntent(uri, mimeType = mimeType)
        val chooser = Intent.createChooser(view, "사진을 열 앱").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = view.clipData
        }
        if (runCatching { context.startActivity(chooser) }.isFailure) {
            Toast.makeText(context, "사진을 열 수 있는 앱이 없거나 사진이 삭제되었습니다.", Toast.LENGTH_SHORT).show()
        }
    }
}

@Composable
fun PhotonRecentPhotoButton(
    uri: Uri?,
    viewer: PhotonPhotoViewer,
    modifier: Modifier = Modifier,
    mimeType: String = JPEG_MIME
) {
    val inspection = LocalInspectionMode.current
    val bitmap by produceState<Bitmap?>(null, uri, viewer, inspection) {
        value = null
        if (!inspection && uri != null) value = viewer.thumbnail(uri)
    }
    Surface(
        modifier = modifier.testTag("recentPhotoButton").combinedClickable(
            role = Role.Button,
            onClickLabel = "최근 PHOTON 사진 열기",
            onLongClickLabel = "사진을 열 앱 선택",
            onClick = { if (!inspection && uri != null) viewer.open(uri, mimeType) },
            onLongClick = { if (!inspection) viewer.showPicker(mimeType) }
        ),
        shape = MaterialTheme.shapes.small
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val image = bitmap
            if (image != null) {
                Image(
                    image.asImageBitmap(),
                    "최근 PHOTON 사진",
                    Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Text(
                    if (uri ==
                        null
                    ) {
                        "사진 없음"
                    } else {
                        "최근 사진"
                    },
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
    PhotonViewerPicker(viewer)
}

@Composable
fun PhotonPhotoViewerSettings(viewer: PhotonPhotoViewer, mimeType: String = JPEG_MIME) {
    val inspection = LocalInspectionMode.current
    TextButton(onClick = { if (!inspection) viewer.showPicker(mimeType) }) {
        Text("사진을 열 앱: ${viewer.selectedName}")
    }
}

@Composable
private fun PhotonViewerPicker(viewer: PhotonPhotoViewer) {
    if (!viewer.pickerVisible) return
    val inspection = LocalInspectionMode.current
    val mimeType = viewer.pickerMimeType
    val apps by produceState<List<PhotonViewerApp>?>(null, viewer, inspection, mimeType) {
        if (!inspection) {
            value = try {
                viewer.apps(mimeType)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: RuntimeException) {
                emptyList()
            }
        }
    }
    AlertDialog(
        onDismissRequest = { viewer.pickerVisible = false },
        title = { Text("사진을 열 앱") },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                item { TextButton(onClick = { viewer.select(null) }) { Text("매번 시스템에서 선택") } }
                val available = apps
                if (available == null) {
                    item { Text(if (inspection) "미리보기" else "앱 확인 중…") }
                } else if (available.isEmpty()) {
                    item { Text("사용 가능한 사진 앱이 없습니다.") }
                } else {
                    items(available, key = { it.component.flattenToString() }) { app ->
                        TextButton(onClick = { viewer.select(app) }) { Text(app.label) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { viewer.pickerVisible = false }) { Text("닫기") } }
    )
}
