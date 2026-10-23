# LunaCam 기기 검증표

이 문서는 가정이 아닌 기기 측정 근거를 기록합니다. P1 항목은 명시된 기기·펌웨어에서 내보낸 기능 탐색기의 JSON/Markdown 보고서로 확인하기 전까지 `[DEVICE-VERIFY]`로 남깁니다.
P1~P3는 초기 개발 단계의 역사적 기록입니다. 당시 진단 화면 진입 절차와 대기 중인 재검증을 현재 PHOTON 앱의 완료 상태로 해석하지 않습니다.

## Galaxy S25 Ultra — P1 기능 탐색기

원본 내보내기: `LunaCam-capability-report (1).json`, `createdAtUtc 2026-09-21T00:45:10Z`,
`cacheKeyHash 4c7666677464a946924af9f879a0e5bac386d359c36dc05b0588662d4d799626`.
`[DEVICE-VERIFY]`는 해당 보고서에서 아직 확인하지 못한 항목입니다.

| 항목 | 확인한 값 | 근거 |
| --- | --- | --- |
| 모델 / 지역 | `SM-S938N`(`N`은 한국 내수 모델을 뜻하며 시험자의 지역은 별도 기록하지 않음) | P1 보고서 `device.model`, 2026-09-21 내보내기 |
| Android SDK | `37`. 고정한 `compileSdk 36` / `targetSdk 36` 기준보다 한 단계 높음 | `device.sdkInt` |
| 펌웨어 지문 해시 | `3c87c63fa35a5e31` | `device.buildFingerprintHash` |
| 카메라 후보 수 | `4`: `camera-0` BACK_WIDE(LEVEL_3), `camera-1` FRONT(FULL), `camera-2` BACK_ULTRAWIDE(LIMITED), `camera-3` FRONT(FULL). 망원은 별도 열거되지 않고 3x/5x 렌즈는 `camera-0`의 물리 하위 카메라로 제공됨 | `cameras.length` |
| 후면 주 카메라 별칭 | `camera-0`, 역할 `BACK_WIDE`, 점수 `203`, 신뢰도 `HIGH`. RAW 지원, 논리 다중 카메라, 35mm 환산 23.2mm, 센서 너비 9.79mm가 근거 | 근거를 포함한 최고 `BACK_WIDE` 점수 |
| 주 카메라의 물리 카메라 별칭 | `camera-2`, `physical-0`, `physical-1`, `physical-2` | 선택 후보 `physicalCameraAliases` |
| 하드웨어 등급 | `LEVEL_3` | `android.info.supportedHardwareLevel` |
| RAW 지원 | 지원(`hasRawCapability: true`) | 후보 기능에 `RAW` 포함 |
| RAW_SENSOR 크기 | `4080x3060`만 제공(약 12.5MP, 4:3) | `streams[formatName=RAW_SENSOR].sizes` |
| RAW10 / RAW12 / RAW_PRIVATE 크기 | RAW10 `4080x3060`, RAW_PRIVATE `4080x3060`. **RAW12는 미제공.** 문서화되지 않은 `FORMAT_54`도 모든 PRIVATE 크기에 존재 | 해당 진단 스트림 항목 |
| RAW 최소 프레임 시간 | RAW_SENSOR, RAW10, RAW_PRIVATE 모두 `16666666`ns(60fps) | 크기별 `minFrameDurationNs` |
| RAW 정지 지연 | 모든 RAW 형식 `0`. JPEG/JPEG_R만 정지 지연이 있으며 전체 크기에서 81ms | 크기별 `stallDurationNs` |
| 센서 물리 크기 | `9.792mm x 7.344mm`, 초점 거리 `6.3mm`(35mm 환산 23.2mm) | `android.sensor.info.physicalSize`, `android.lens.info.availableFocalLengths` |
| 활성 배열 / 픽셀 배열 | 활성 `[0,0,4080,3060]`, 보정 전 `[0,0,4080,3060]`, 픽셀 배열 `4080x3060`. 모두 같아 보정 전 여백이 없음 | 해당 특성 |
| CFA 배열 | 후면 주 카메라는 `2`=**GBRG**. 나머지 세 카메라는 `1`=GRBG. P5 디모자이크는 카메라별 값을 읽어야 하며 RGGB를 가정하면 안 됨 | `android.sensor.info.colorFilterArrangement` |
| 노출 시간 / 감도 범위 | 노출 `83490`~`176037266`ns(83.5µs~176ms). ISO `12`~`3200`, 최대 아날로그 감도 `1500`이므로 그 위는 디지털 이득 | 해당 특성 |
| AE 보정 범위 / 단계 | `[-20, 20]`, 단계 `1/10`: **±2.0EV, 0.1EV 간격**. 설계 11.19절의 하이라이트 보호 기본 `-1.0EV`는 단계 `-10` | 해당 특성 |
| 검정 / 흰색 수준 | `blackLevelPattern [64,64],[64,64]`, `whiteLevel 1023`: 검정 수준 64인 10비트 RAW | 해당 특성 |
| 렌즈 음영 적용 여부 | **`true`**. 당시에는 HAL이 적용한 음영을 중복 보정하지 않는 것으로 해석함. 이후 Night 리비전 2는 프레임별 **잔여** 음영 지도를 사용하므로 이 초기 해석으로 현재 경로를 생략하면 안 됨 | `android.sensor.info.lensShadingApplied`; 후속 Night 규칙은 `AI_MAINTENANCE.md` |
| 광학 손떨림 보정 모드 | `[0]`(OFF만). Camera2에 OIS가 노출되지 않아 설계 9.5절의 `maxHandheldExposureNs` 결정 시 OIS 상태를 읽을 수 없음 | `android.lens.info.availableOpticalStabilization` |
| 타임스탬프 출처 / 동기 지연 | `timestampSource 1`(REALTIME), `sync.maxLatency 0`(PER_FRAME_CONTROL). P3 영상·결과 연결과 P6 수동 연사에 유리한 메타데이터 조건 | 해당 특성 |
| 필수 스트림 조합 | DEFAULT, CONCURRENT, TEN_BIT, STREAM_USE_CASE, PREVIEW_STABILIZATION 제공. **표준 DNG 촬영 조합인 PRIVATE ≤ 1920x1080 + RAW_SENSOR 4080x3060을 선언**하며 P3 목표와 설계 8.5절 FULL_HD 단계에 해당. 실제 세션 성공은 별도 검증 필요 | `mandatoryStreamCombinations` |
| 권장 RAW / 프리뷰 맵 | `recommendedStreams`가 **비어 있음**. 권장 맵이 없어 설계 8.3절은 일반 스트림 구성 맵을 사용해야 함 | `recommendedStreams` |
| 예상 키 누락 | 주 카메라에서 `1`개: `android.scaler.mandatoryMaximumResolutionStreamCombinations` | 후보 `missingKeys` |
| 메타데이터 읽기 오류 | 주 카메라 `0`개. `camera-1`, `camera-2`, `camera-3` 각각 `availablePhysicalRequestKeys: NullPointerException`. P1의 null 안전 리더가 충돌 없이 잡은 프레임워크 오류이며 FR-CAP-003 실제 오류 대응 근거 | 후보 `readErrors` |

## P1 기기 절차

1. 대상 S25 Ultra에 디버그 APK를 설치합니다.
2. 시스템 권한 요청 전에 설명이 표시되는지 확인합니다.
3. 한 번 거부한 뒤 빈 프리뷰나 반복 권한 요청 없이 이유 설명이 나오는지 확인합니다.
4. 카메라 권한을 허용하고 모든 후보 카드가 로드될 때까지 기다립니다.
5. 시스템 문서 선택기로 JSON과 Markdown을 모두 저장합니다.
6. Android ID, 계정, 위치, 로컬 경로, 이미지 픽셀, 원시 빌드 지문이 내보낸 파일에 없는지 확인합니다.
7. 확인한 값을 위 표에 옮기고 보고서 해시를 시험 기록에 첨부합니다.

P2 프리뷰·세션 성공과 P3 RAW 영상·결과 연결은 이 P1 표의 범위 밖입니다.

## Galaxy S25 Ultra — P2 프리뷰 수명주기

| 항목 | 확인한 값 | 근거 |
| --- | --- | --- |
| 선택한 프리뷰 카메라 ID | `camera-0`(실제 ID `0`), `BACK_WIDE`, 점수 `203` | 최고 점수 `BACK_WIDE`에 대한 `PreviewCameraSelector` 결과 |
| 센서 방향 | `90` | 선택 카메라 `android.sensor.orientation` |
| 활성 배열 사각형 | `[0,0,4080,3060]` | `android.sensor.info.activeArraySize` |
| 프리뷰 크기 / 단계 | `0.3.0-p2`는 **`1920x824`** 선택(결함 D2). `0.3.1-p2`는 **`1440x1080`**, FULL_HD, 긴 화면에 여백을 두는 방식. 기기 재확인 필요 | `PreviewUiState.Ready.previewSize`, 도달한 `PreviewSizeTier` |
| AE 보정 범위 / 단계 | `[-20, 20]`, `1/10`: ±2.0EV, 0.1EV 간격. 초기 슬라이더 표시는 `EV +0.0` | 기기 키에서 만든 `ExposureCompensationSpec` |
| 연속 AF 모드 | `CONTROL_AF_MODE_CONTINUOUS_PICTURE`(모드 4 지원) | 프리뷰 요청의 실제 `CONTROL_AF_MODE` |
| 최대 AF / AE 측광 영역 | AF `1`, AE `1`, AWB `0`. AF·AE 각 한 영역이며 AWB 영역은 지정하면 안 됨 | `android.control.maxRegionsAf` / `maxRegionsAe` |
| 줌 비율 범위 | 논리 카메라 `[0.6, 10.0]`. 기본 `1.0`은 주 렌즈이나 P3에서는 기본값에 의존하지 않고 `CONTROL_ZOOM_RATIO = 1.0` 명시(FR-PRV-003) | `android.control.zoomRatioRange` |
| 열기·닫기·회전 20회 | `[DEVICE-VERIFY]` | 통과 여부와 logcat 오류 수 |
| 프리뷰 늘어짐 | `[DEVICE-VERIFY]` | 세로·가로 격자 피사체 사진 |
| 탭 좌표 정확도 | `[DEVICE-VERIFY]` | 네 모서리·중앙에서 표시 위치와 실제 초점 피사체 비교 |
| 다른 카메라 앱 사용 후 복구 | `[DEVICE-VERIFY]` | 관찰한 `CameraFailureKind`와 자동 복귀 여부 |
| 열기 지연 | `[DEVICE-VERIFY]` | `Opening`→`Previewing` 시간과 3초 제한 비교 |

## P2 기기 절차

1. 디버그 APK를 설치하고 당시 진단 화면에서 카메라 권한을 허용합니다.
2. `프리뷰 열기`를 눌러 4:3 프리뷰가 중앙에 표시되고, 세로에서는 위·아래, 가로에서는 좌·우에 같은 검정 여백이 있으며 늘어짐이 없는지 확인합니다.
3. 화면을 나갔다 들어오는 동작과 번갈아 기기를 회전·복귀하는 과정을 20회 반복합니다. 매번 프리뷰가 돌아와야 하고 회전만으로 카메라를 다시 열면 안 되며 logcat에 카메라 오류가 없어야 합니다.
4. 격자·줄무늬 피사체를 두 방향으로 촬영하여 비율 왜곡을 확인합니다.
5. 네 모서리와 중앙을 탭합니다. 초점 표시가 손가락 위치에 있고 실제 해당 피사체에 초점이 맞아야 합니다.
6. EV 슬라이더를 양끝으로 움직여 프리뷰 밝기와 보고된 EV가 기기의 범위·단계에 맞는지 확인합니다.
7. 앱을 나가 기본 카메라 앱을 연 뒤 복귀하여 프리뷰가 자동 복구되는지 확인합니다.
8. 백그라운드에서 프리뷰가 해제되어 기본 카메라 앱이 오류 없이 카메라를 여는지 확인합니다.
9. 모든 값을 기록하고 logcat 발췌를 첨부합니다.

## P2 기기 결함(2026-09-21) — 당일 코드 수정, 기기 재검증 대기

2026-09-21 기능 보고서와 P2 코드를 비교해 두 결함을 발견했습니다. 둘 다 프리뷰 늘어짐과 탭 좌표 종료 기준에 영향을 주었습니다. `0.3.1-p2`에서 수정했으나 당시 기기 재시험은 남아 있었습니다.

### D1 — 계산한 `PreviewTransform`을 `SurfaceView`에 적용하지 않음

`PreviewScreen`은 `Modifier.fillMaxSize()`로 `SurfaceView`를 배치하고 `SurfaceHolderPreviewSurfaceProvider`는 `SurfaceHolder.setFixedSize(...)`만 호출했습니다. `PreviewTransformFactory`가 계산한 중앙 크롭 배율에 맞춰 뷰 크기·위치를 조정하지 않아 플랫폼은 버퍼를 전체 뷰로 늘렸습니다. 반면 `MeteringRegionMapper`는 중앙 크롭을 가정해 탭을 변환하므로 표시 구도와 탭 좌표가 불일치했습니다.

기기 화면에서 프리뷰가 왼쪽 약 3/4를 차지하고 오른쪽에 검정 띠가 보였으며 이 결함과 일치했습니다.

0.3.1-p2에서는 `PreviewTransform`에 `displayedViewRect`, `PreviewUiState.Ready`에 그 비율을 담고, `PreviewScreen`이 `Modifier.aspectRatio(...)`로 검정 영역 중앙에 뷰를 배치합니다. 여백 탭은 가장자리로 제한하지 않고 무시합니다.

### D2 — 프리뷰 크기 선택이 센서를 과도하게 잘라냄

`PreviewSizeSelector`는 후보의 긴 변/짧은 변 비율이 뷰에 가까울수록 높은 점수를 주었습니다. 1440x3120 화면(비율 2.167)에서 1920x1080 이하 후보 중 `1920x1080`(1.778) 대신 `1920x824`(2.330)를 선택했습니다.

4:3 센서의 2.33:1 읽기는 세로 시야 약 43%를 버립니다. P3 RAW는 4:3인 `4080x3060`이므로 프리뷰 구도와 촬영 파일이 크게 달랐습니다.

0.3.1-p2에서는 `PreviewSizeSelector.select`에 목표 비율을 명시하고 컨트롤러가 센서 활성 배열 비율을 전달합니다. 실제 후보 목록에서 `1440x1080`을 선택하며 회귀 시험으로 `1920x824` 재선택을 막습니다. 4:3 프리뷰에 여백을 두는 결정은 `DESIGN.md` 5.2절에 기록했습니다.

## P3 준비 — 기기 메타데이터에 따른 예산 입력

아래 시간·처리량은 선언된 최소치에 따른 계산이며 실제 연사·메타데이터 대응 성공을 보장하지 않습니다.

| 입력 | 확인한 값 | P3 / P6 영향 |
| --- | --- | --- |
| RAW 프레임 크기 | `4080 x 3060`, 픽셀당 16비트 = **프레임당 23.8MiB** | 8프레임은 작업 버퍼 전에도 약 **190MiB**. 설계 14.1절을 이 값으로 재계산 |
| RAW 최소 프레임 시간 | `16.67ms`(60fps) | 8프레임의 이상적 하한은 약 **133ms**이며 손촬영 정렬 예산의 입력 |
| RAW 정지 지연 | `0` | 선언상 프레임 간 정지 지연이 없어 프레임 시간·메모리가 주요 한계 |
| 최대 RAW 출력 스트림 | `1` | 설계 9.2절처럼 RAW `ImageReader` 하나 사용 |
| 동기 지연 | `0`(PER_FRAME_CONTROL) | 수동 노출·ISO의 다음 프레임 적용을 선언. 실제 결과가 요청과 대응하는지는 별도 확인 |
| 최대 파이프라인 깊이 | `8` | 설계 9.2절의 10장 한도와 함께 `ImageReader.maxImages` 산정에 참고 |
