# PHOTON 해상도 조사 및 도입 조건

2026-10-23. 0.10.0 코드와 기존 Galaxy S25 Ultra 기기 증거를 기준으로 한 후속 조사다.
이 문서는 새 해상도 모드를 활성화하지 않는다. 실제 사진 픽셀이나 촬영 화면을 읽지 않았다.

## 현재 결론

- 기본 후면의 확인된 일반 출력은 **4080×3060 = 12,484,800픽셀(12.4848MP)**이다.
  제품상 “12MP급”이라고 부를 수 있지만 실제 크기를 4000×3000으로 축소할 이유는 없다.
- **현재 기기·펌웨어의 조사한 공개 Camera2 경로에는 16MP급 원본 출력이 없다.**
  공개/물리 ID 7개에서 일반·고해상도 목록·최대 모드까지 조회했고, 가장 큰 출력은 12.4848MP다.
  이는 해당 공개 API 노출 범위의 결론이며 하드웨어 센서 자체의 물리적 해상도 한계가 아니다.
- “16MP 이하 처리 가능”이라는 코드의 안전 한도는 기기의 16MP 촬영 지원을 뜻하지 않는다.
  확대 보간으로 16MP 파일을 만들고 원본 해상도 증가라고 표시하지 않는다.
- 후속 고해상도 실험과 나이트샷의 기본 비교 기준은 현재 검증된 **12MP급 원본**이다.
  고해상도 목록이 나오더라도 세션·사용자 촬영·지연·메모리 검증 전에는 기본값을 바꾸지 않는다.

## 확보한 기기 증거

| 증거 | 범위 | 확인한 내용 / 한계 |
| --- | --- | --- |
| `LunaCam-capability-report (1).json` | 2026-09-21 P1 공개 카메라 전체 | 일반 형식별 크기·최소 프레임 시간·스톨. high-resolution 크기 별도 수집 없음 |
| `output/photon-v0.7.0/photon-capabilities-live.json` | 2026-10-23 공개 카메라 | JPEG/YUV/RAW 일반 크기 재확인. 최대 모드와 고해상도 목록 미수집 |
| `output/photon-v0.7.5/photon-raw-lens-capabilities.json` | 공개 ID 및 물리 ID 2/5/6/7 | 물리 RAW 크기 확인. JPEG/YUV 고해상도 지원을 증명하지 않음 |
| `output/photon-v0.10.0/photon-auto-verification.json` | 사용자가 찍은 AUTO 두 장의 기존 헤더·영수증 | 두 출력 모두 4080×3060. 픽셀 열람 없이 저장 크기와 지연 확인 |
| `output/photon-v0.10.0/photon-resolution-metadata.json` | 2026-10-23 04:44:29 UTC, ID 0/1/2/3/5/6/7 | 후속 metadata-only 검사 PASS, 조회 오류 0. 일반/high/max 목록을 모두 수집 |
| `output/photon-v0.10.0/resolution-summary.json` | 위 원본의 형식·모드별 집계 | 가장 큰 출력 12.4848MP, 기본 모드 high-resolution 목록 모두 0개 |
| `docs/DEVICE_MATRIX.md` | 위 P1 증거를 정리한 역사적 기록 | 새 펌웨어의 고해상도 지원이나 전체 촬영 성능을 대신하지 않음 |

공개 및 물리 카메라의 일반 `getOutputSizes` 최대값은 다음과 같다. 이는 기기에서 선언한 크기이며,
모든 크기·형식 조합의 촬영을 실제 검증했다는 뜻은 아니다.

| ID / 공개 여부 | 역할 | JPEG / YUV / RAW 각각의 일반 최대값 | 픽셀 수 |
| --- | --- | --- | --- |
| 0 / 공개 | 논리 후면 / 기본 광각 | 4080×3060 | 12.4848MP |
| 1 / 공개 | 전면 | 4000×3000 | 12MP |
| 2 / 공개·물리 | 후면 초광각 | 4080×3060 | 12.4848MP |
| 3 / 공개 | 전면의 다른 공개 카메라 | 3392×2544 | 8.629248MP |
| 5 / 물리 | 후면 기본 광각 | 4080×3060 | 12.4848MP |
| 6 / 물리 | 후면 망원 약 2.7× | 4000×3000 | 12MP |
| 7 / 물리 | 후면 망원 약 5.1× | 4080×3060 | 12.4848MP |

물리 RAW는 2/5/7이 4080×3060, 6이 4000×3000이다. 0.7.5 보고서의 각 `rawSizes`에
그 크기만 있다. 새 진단으로 물리 5/6/7의 JPEG/YUV 일반 최대값도 위 표와 같음을 확인했다.
공개 0과 물리 5는 서로 다른 독립 센서 두 개가 아니다.

특이점: P1의 공개 ID 2는 `ULTRA_HIGH_RESOLUTION_SENSOR` capability가 없는데도
`android.scaler.streamConfigurationMapMaximumResolution` 키를 보고했다.
당시 저장된 값은 `StreamConfigurationMap(outputFormats=34, 35, 54, 256)`이라는 **형식 요약**뿐이다.
동시에 maximum pixel/active array는 4080×3060이었다. 따라서 이것은 16MP나 50MP 증거가 아니다.
capability 부재만으로 모든 maximum-resolution 키의 부재를 단정해서도 안 된다.

후속 진단으로 누락된 내용을 다음과 같이 확정했다.

- 7개 ID 모두 기본 map의 JPEG/YUV/RAW/PRIVATE **high-resolution 크기 0개**다.
- maximum-resolution map은 ID 2/5/7에만 있다. 세 ID 모두 조사한 JPEG/YUV/PRIVATE 출력은
  **1920×1080(2.0736MP)뿐**이며, 해당 map에 RAW는 없다. 0/1/3/6은 map 자체가 없다.
- ID 2/7의 1920×1080은 최대 map의 일반 목록에, ID 5의 같은 크기는 최대 map의 high-resolution
  목록에 있다. ID 5의 최소 프레임 시간이 약 90.91ms여서 “high-resolution” 분류가 반드시
  더 많은 픽셀을 뜻하지 않는 실제 예다. 기본 map의 high-resolution 목록은 여전히 모두 비어 있다.
- 7개 ID 모두 `ULTRA_HIGH_RESOLUTION_SENSOR=false`, `supportsPixelModeRequest=false`다.
  이 작은 maximum map 선언만으로 최대 센서 모드 촬영이 가능하다고 확정하지 않는다.
- 조회 오류는 `[]`이며 `cameraOpened=false`, `pixelsRead=false`, `photosCaptured=0`이다.
  새 세션이나 실제 사진을 만들지 않고 기존 진단의 고해상도 조회 공백을 해소했다.

## 일반 출력, 고해상도 목록, 최대 센서 모드의 차이

| 구분 | 조회 / 설정 | 의미와 현재 상태 |
| --- | --- | --- |
| 일반 출력 | 기본 `SCALER_STREAM_CONFIGURATION_MAP.getOutputSizes(format)` | 현재 AUTO와 RAW가 선택하는 범위. 위 표로 확인 |
| 기본 모드의 고해상도 목록 | 같은 map의 `getHighResolutionOutputSizes(format)` | 일반 burst 속도를 만족하지 못하는 별도 목록. 새 진단에서 조사한 7개 ID·4개 형식 모두 0개 |
| 최대 센서 모드 | `SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION` 및 `SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION` | 별도 sensor pixel mode. 현재 2/5/7의 작은 map만 있고 pixel-mode 요청 키는 모두 미노출. 16MP 경로 없음 |
| 물리 카메라 | 논리 카메라의 `physicalCameraIds`별 characteristics | 논리 카메라의 지원 표를 물리 센서 모두에 그대로 적용할 수 없음 |
| 앱 확대 저장 | 리사이즈/업스케일 | 센서가 읽은 원본 해상도 증가가 아니므로 이번 범위에 추가하지 않음 |

high-resolution 목록은 최소 프레임 속도 기준으로 나뉘며 JPEG 스톨만으로 분류하지 않는다.
따라서 `getOutputSizes`만 읽은 결과를 “기기 전체 최대 해상도”라고 부르면 누락이 생긴다.
[Android StreamConfigurationMap](https://developer.android.com/reference/android/hardware/camera2/params/StreamConfigurationMap#getHighResolutionOutputSizes(int))

CameraX `ResolutionSelector`의 기본 allowed mode는 일반 출력 위주다.
`PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE`는 기본 map의 고해상도 목록을 후보에 포함시키지만,
최대 센서 모드까지 자동으로 활성화하는 설정은 아니다. 실제 선택 결과는 `resolutionInfo`로 확인해야 한다.
[CameraX ResolutionSelector](https://developer.android.com/reference/androidx/camera/core/resolutionselector/ResolutionSelector)

maximum pixel mode는 별도 출력 map과 요청 설정이 필요하고, 양쪽 map에 함께 없는 출력은 같은
요청에 무조건 섞을 수 없다. 이 요청 키는 UHR capability가 없는 기기에도 나타날 수 있다.
따라서 키의 존재와 더 큰 원본 크기의 존재, 실제 동작은 각각 검증한다.
[Android SENSOR_PIXEL_MODE](https://developer.android.com/reference/android/hardware/camera2/CaptureRequest#SENSOR_PIXEL_MODE)

## 현재 코드의 선택과 안전 한도

- `PhotonCameraController.bind`: 4:3 `ResolutionSelector`, `CAPTURE_MODE_MINIMIZE_LATENCY`,
  JPEG 품질 95. 고해상도 allowed mode나 maximum pixel mode를 설정하지 않는다.
  12MP를 숫자로 강제한 것이 아니라 해당 세션에서 CameraX가 선택한 결과이며, 기존 저장 증거는 12.4848MP다.
- `RawBurstCamera.rawSize`: 기본 map의 RAW 중 **16,000,000픽셀 이하** 최대 크기만 고른다.
  명시적으로 고해상도 목록이나 최대 센서 모드를 탐색하지 않는다.
- `PhotonAutoPhotoStore.decode`, 기존 `PhotonPhotoProcessor`, native bridge 및 C++의
  texture/detail/HDR/RAW 처리에는 16,000,000픽셀 한도가 있다. 고해상도 선택만 바꾸면 안전 한도에
  걸려 실패하거나 기준 사진으로 돌아갈 수 있으므로 전체 경로의 메모리 검증이 선행되어야 한다.
- 판매상 “16MP”는 하나의 정확한 크기 이름이 아니다. 예를 들어 4608×3456은 15.925248MP,
  4656×3492는 16.258752MP다. 이 예시는 현재 장치가 해당 크기를 지원한다는 뜻이 아니다.
  지원 여부와 안전 한도는 실제 `(width, height, format, pixel mode, camera ID)`로 판단한다.

## 비율 변경 후 저장 픽셀 수

0.9/0.10의 비율 변경은 원본 4:3 센서 촬영 후 중앙 크롭이다. 화각과 픽셀 수를 줄이는 것이며
해상도 업스케일이 아니다. EXIF 회전으로 화면에서 가로/세로가 바뀌어도 픽셀 수는 같다.

| 선택 | 현재 4080×3060 입력의 인코딩 크기 | 저장 픽셀 수 |
| --- | --- | --- |
| 4:3 원본 | 4080×3060 | 12.4848MP |
| 16:9 | 4080×2295 | 9.3636MP |
| 21:9 | 4074×1746 | 7.113204MP |

`PhotonAspectRatio.centerCrop`은 정확한 정수 비율을 맞추므로 21:9에서 가로도 6픽셀 줄어든다.
16:9/21:9 값은 `docs/PHOTON_090_VERIFICATION.md`의 사용자 촬영 헤더 검사와 일치한다.
현재 구현은 원본을 받은 뒤 처리하므로 최종 크롭 MP가 낮아졌다고 센서 수신이나 모든 처리 비용이
같은 비율로 줄어든다고 볼 수 없다. RAW DNG는 이 JPEG 비율 크롭의 적용 대상이 아니다.

## 1.2초 목표와 비용 판단

1.2초는 **사용자 입력부터 다음 셔터를 실제로 받을 수 있을 때까지**이며, 최종 저장 완료 기한이 아니다.
0.10.0 사용자 AUTO 두 장은 다음 셔터 954/860ms, 처리·저장 350/313ms,
최종 완료 1307/1175ms였다. 두 촬영은 약 5.8초 간격이어서 대기열이 찬 연속 촬영의 보장은 아니다.
원본 크기는 둘 다 4080×3060이고 HDR OFF·질감/디테일 ON이었다.

아래 16MP 열은 **가정상의 정확한 16,000,000픽셀 비용 계산**이며 장치 지원이나 속도 측정이 아니다.

| 단순 버퍼 계산 | 현재 12.4848MP | 가정상 16MP |
| --- | --- | --- |
| ARGB_8888 1개 | 약 47.63MiB | 약 61.04MiB |
| packed uint16 RAW 1개 | 약 23.81MiB | 약 30.52MiB |
| RAW 입력 3개 | 약 71.44MiB | 약 91.55MiB |
| 두 RAW 작업의 입력 6개 | 약 142.88MiB | 약 183.11MiB |

입력 픽셀은 약 28.16% 증가한다. 여기에 결과·정합 가이드·JPEG decode·GPU 업로드/다운로드·
카메라 버퍼가 추가된다. 메모리 증가율을 셔터 지연 증가율로 그대로 쓰면 안 된다.
AF/AE, 노출 시간, 센서 readout, 스톨, GPU/CPU 대기, 발열과 두 건 대기열 포화는 서로 다르게 작용한다.

P1 기본 후면 선언은 RAW min frame 16.67ms/stall 0, JPEG min frame 33.33ms/stall 약 81.15ms였다.
이는 선택 형식의 하한/특성 정보이지 앱의 종단 셔터 시간 예측값이 아니다. 고해상도 모드가 느린
sensor readout을 요구하면 GPU/NPU로 후처리를 빨리 해도 그 센서 시간을 되돌릴 수 없다.
GPU 가속 역시 실제 처리·전송·메모리·다음 셔터를 함께 측정해야 한다.

따라서 초기 나이트샷 설계의 해상도 기준은 현재 12MP급으로 유지한다. 다중 프레임 수와 고해상도를
동시에 늘려 비용 원인을 섞지 않는다. 향후 지원 기기에서의 16MP급 검증은 별도 opt-in 실험으로 분리하고,
실제 수신 크기·한 장 저장·AF/AE·다음 셔터·최종 저장·포화 큐·발열을 통과한 뒤 도입한다.

## 진단 결과와 향후 도입 조건

새 테스트: `PhotonResolutionMetadataDeviceTest.inspectNormalHighAndMaximumResolutionMetadata`.
실행 인자 `probeResolution=true`, 결과 `files/photon-resolution-metadata.json`.
**후속 실기기 검사 PASS**. 원본과 집계는 위 0.10.0 출력 폴더에 보존했다.
이 테스트는 기존 0.10.0 릴리스 APK와 분리된 후속 검증이며 production 코드 변경은 없다.

이 테스트는 공개/물리 카메라 characteristics만 읽는다. 카메라 open, 세션, ImageReader, 촬영,
사진 파일 접근, 화면 캡처를 하지 않는다. 형식별 normal/high/max 크기와 최소 프레임/스톨,
pixel-mode 요청 키, 센서 배열 크기 및 조회 오류를 기록한다. `null`과 조회 오류는 별도로 구분한다.

1. 이번 결과에서는 **16MP급 또는 더 큰 원본 크기가 나오지 않았다**. 현재 기본값은 12MP급으로 유지한다.
2. 새 기기나 펌웨어에서 재조사할 수 있다. 숨은 비공개 제조사 경로나 기본 카메라 앱의 센서 광고를
   PHOTON 지원이라고 옮겨 적지 않으며, 이번 결과를 하드웨어 전체의 물리적 한계로 확대하지 않는다.
3. 향후 더 큰 크기가 나타나도 map 선언만으로 촬영 가능을 확정하지 않는다. 별도 승인된 후속 작업에서
   해당 preview + photo 조합, pixel mode, 물리 metadata 정합을 검증한다.
4. 이후 사용자 촬영의 헤더·실제 크기·소유 URI·지연을 확인하고 12MP 기준과 비교한다.
   원본 픽셀의 추가 열람이나 자동 실제 촬영은 이 조사에 포함되지 않는다.

**현재 수락 상태:** 12MP급 일반 출력 확인 / 조사한 현재 공개 API에 16MP급 경로 없음 /
고해상도 모드 구현 없음 /
소프트웨어 업스케일 추가 없음 / production 코드 변경 없음.
