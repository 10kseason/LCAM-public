package dev.lunacam.app.photon.raw

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.roundToInt

@Composable
internal fun RawProControls(model: RawCameraViewModel, onDone: () -> Unit) {
    val support = model.capabilities
    val controls = model.controls
    val enabled = model.ready && !model.busy && !model.switching
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("RAW 초점·Pro", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onDone) { Text("완료") }
        }
        Text("지원되는 항목만 표시합니다. 렌즈 변경 시 자동 설정으로 시작합니다.")
        if (support.tapAf) {
            Text(
                "미리보기를 터치하면 초점·측광 위치를 지정합니다. " +
                    "터치하면 수동 초점은 해제됩니다."
            )
        } else if (support.tapAe) {
            Text("자동 노출에서 미리보기를 터치하면 측광 위치를 지정합니다.")
        }
        if (support.ev) {
            Text(String.format(Locale.ROOT, "노출 보정 %+.2f EV", controls.evIndex * support.evStep))
            Slider(
                value = controls.evIndex.toFloat(),
                onValueChange = { model.updateControls(controls.copy(evIndex = it.roundToInt())) },
                valueRange = support.minEvIndex.toFloat()..support.maxEvIndex.toFloat(),
                enabled = enabled && !controls.manualExposure,
                modifier = Modifier.semantics { contentDescription = "RAW 노출 보정" }
            )
            if (controls.manualExposure) Text("수동 노출에서는 ISO와 셔터로 밝기를 조절합니다.")
        }
        if (support.manualExposure) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("수동 ISO·셔터", Modifier.weight(1f))
                Switch(
                    controls.manualExposure,
                    { manual ->
                        val actual = model.observedControls
                        model.updateControls(
                            controls.copy(
                                manualExposure = manual,
                                exposureNs = actual?.exposureNs ?: controls.exposureNs,
                                iso = actual?.iso ?: controls.iso
                            )
                        )
                    },
                    enabled = enabled,
                    modifier = Modifier.semantics {
                        contentDescription =
                            "RAW 수동 노출"
                    }
                )
            }
            if (controls.manualExposure) {
                Text(
                    String.format(
                        Locale.ROOT,
                        "셔터 %.3f ms · 최대 %.3f초",
                        controls.exposureNs / 1_000_000.0,
                        support.maxExposureNs / 1_000_000_000.0
                    )
                )
                Slider(
                    value = rawLogFraction(
                        controls.exposureNs,
                        support.minExposureNs,
                        support.maxExposureNs
                    ),
                    onValueChange = {
                        model.updateControls(
                            controls.copy(
                                exposureNs = rawLogValue(
                                    it,
                                    support.minExposureNs,
                                    support.maxExposureNs
                                )
                            )
                        )
                    },
                    enabled = enabled,
                    modifier = Modifier.semantics {
                        contentDescription =
                            "RAW 셔터 속도"
                    }
                )
                Text("ISO ${controls.iso}")
                Slider(
                    value = rawLogFraction(
                        controls.iso.toLong(),
                        support.minIso.toLong(),
                        support.maxIso.toLong()
                    ),
                    onValueChange = {
                        model.updateControls(
                            controls.copy(
                                iso = rawLogValue(
                                    it,
                                    support.minIso.toLong(),
                                    support.maxIso.toLong()
                                ).toInt()
                            )
                        )
                    },
                    enabled = enabled,
                    modifier = Modifier.semantics {
                        contentDescription =
                            "RAW ISO"
                    }
                )
                Text("긴 노출에서는 미리보기와 다음 촬영 대기가 느려집니다.")
            }
        } else {
            Text("이 렌즈 경로는 수동 ISO·셔터 제어를 제공하지 않습니다.")
        }
        if (support.manualFocus) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("수동 초점", Modifier.weight(1f))
                Switch(
                    controls.focusDiopters != null,
                    { manual ->
                        model.updateControls(
                            controls.copy(
                                focusDiopters = if (manual) {
                                    model.observedControls?.focusDiopters
                                        ?: 0f
                                } else {
                                    null
                                }
                            )
                        )
                    },
                    enabled = enabled,
                    modifier = Modifier.semantics {
                        contentDescription =
                            "RAW 수동 초점"
                    }
                )
            }
            controls.focusDiopters?.let { distance ->
                Text(String.format(Locale.ROOT, "초점 %.2f D · 0은 무한대", distance))
                Slider(
                    value = distance,
                    onValueChange = {
                        model.updateControls(controls.copy(focusDiopters = it))
                    },
                    valueRange = 0f..support.maxFocusDiopters,
                    enabled = enabled,
                    modifier = Modifier.semantics { contentDescription = "RAW 초점 거리" }
                )
            }
        }
        if (support.whiteBalances.size > 1) {
            Text("화이트밸런스")
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                support.whiteBalances.forEach { wb ->
                    FilterChip(
                        selected = controls.whiteBalance == wb,
                        onClick = { model.updateControls(controls.copy(whiteBalance = wb)) },
                        label = { Text(wb.label) },
                        enabled = enabled
                    )
                }
            }
            Text("WB는 프리뷰와 DNG 색 메타데이터에 반영됩니다. RAW 현상 앱이 이를 다르게 해석할 수 있습니다.")
        }
        if (model.controlsUpdating) Text("설정 반영 프레임 확인 중…")
        model.observedControls?.let { actual ->
            Text(
                "최근 확인: ISO ${actual.iso ?: "?"} · ${actual.exposureNs?.div(1_000_000.0) ?: "?"} ms"
            )
        }
        Text(model.message, style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = {
            model.updateControls(RawControls())
        }, enabled = enabled) { Text("RAW 설정 초기화") }
    }
}
