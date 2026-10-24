# LUNA PHOTON 유지보수 시작 안내

후속 수정에서는 먼저 이 문서의 소스 지도와 검증 계약을 읽고 현재 사용자 요청 범위 안에서 수정하세요. 현재 시작 모드·공통 화면은 `PHOTON_0160_VERIFICATION.md`, RAW HDR·연속 줌은 `PHOTON_0150_VERIFICATION.md`, RAW AUTO 초기 기준은 `PHOTON_0140_VERIFICATION.md`, AUTO/RAW·Pro 상태는 `PHOTON_0130_VERIFICATION.md`, 히스토그램은 `PHOTON_0120_VERIFICATION.md`, SER 화질 근거는 `PHOTON_0110_VERIFICATION.md`에 있습니다.

이 문서는 소스 지도와 확인된 제약을 정리한 것으로, 공개·사진 업로드·사용자 파일 삭제·무관한 작업의 허가가 아닙니다. 현재 사용자 지시를 우선합니다. 과거 인계 문서는 이전 버전을 설명하므로 오래된 주장을 사용하기 전에 현재 소스와 최신 검증 문서를 확인하세요.

## 0.16.1 감사 수정 상태

AUTO 저장 성공 기록은 다음 셔터 준비와 분리합니다. 준비를 아직 관찰하지 못했으면 `shutterReadyMs`와 `within1200Ms`를 명시적인 null로 저장하고, 이후 실제 준비가 관찰되면 시간을 갱신합니다. 앱이 백그라운드에 있다는 이유로 이미 저장한 사진의 완료 기록을 누락하면 안 됩니다. `PhotonCaptureTiming.kt`가 이 공통 규칙을 담당합니다.

히스토그램 기기 시험은 현재 시작 모드에 의존하지 않고 AUTO 화면을 명시적으로 구성합니다. JPEG 저장본 시험은 같은 앨범의 DNG를 JPEG로 검사하지 않도록 MIME 형식을 제한합니다. 이 절은 소스 수정의 설명이며 **검증 완료를 뜻하지 않습니다**. 새 빌드·선별 시험 결과는 해당 버전의 감사·검증 기록에서 별도로 확인해야 합니다.

## RAW AUTO 기본 시작과 공통 UI(0.16.0)

`PHOTON_0160_VERIFICATION.md`는 이전의 AUTO 기본 시작·RAW AUTO 선택 진입 설명을 대체합니다. 사용자가 RAW AUTO 시작과 미지원 기기의 AUTO 대체 진입을 명시적으로 선택했습니다. 일시적 카메라 오류는 재시도할 수 있어야 합니다.

모든 모드는 `PhotonCameraChrome`을 공유하며 Night의 고정 1x/4:3 처리는 유지합니다. RAW `TextureView`에는 `RawPreviewContainer`의 네이티브 자르기와 Compose `clipToBounds`를 함께 유지해야 합니다. 변환만 적용하면 주변 UI 밖으로 넘칠 수 있습니다.
비율은 `RawBurst`에 고정하고 JPEG·DNG·대기 작업 복구에 일관되게 사용합니다. 히스토그램은 선택 활성화이며 RESUMED 상태에서만 제한된 표본을 읽고 프리뷰 픽셀을 저장하지 않습니다. 자연스러운 RAW JPEG 현상은 AUTO TextureGuard/DetailGuard와 독립적으로 유지합니다.

## RAW HDR와 연속 줌(0.15.0)

RAW AUTO의 HDR 스위치는 기본 ON이며 셔터 시점에 고정합니다. ISO를 고정한 상태에서 기준·1/4·4배 노출 시간을 요청하고 긴 노출은 80ms로 제한합니다. 미지원이거나 노출 분리가 부족하면 기준 한 장을 사용합니다. 물리 프레임 결과와 잡음·색·초점·음영 근거가 합성 여부를 결정합니다.
`NativeRawHdr`는 기존 RAW3/SER와 독립적입니다. 출력은 JPEG 현상 전용인 짧은 노출 기준의 정규화된 16비트 RAW이며 기준 색 정보, 검정 0·흰색 65535, 실제 이득을 적용합니다. 국소 근거가 없거나 현상 이득을 지원하지 않으면 기준 JPEG를 사용하며 거부 결과를 HDR로 표시하지 않습니다.

`RawZoomPolicy`는 달성 가능한 물리 시야각을 먼저 고른 뒤 디지털 크롭을 적용하고 확대 방향 전환에 히스테리시스를 둡니다. `RawBurst.digitalZoom`은 구도를 고정합니다. 프리뷰는 4:3 맞춤 프레임 안에서 소프트웨어 크롭을 사용하며 탭 좌표도 이에 맞춥니다.
`RawDngCrop`은 생성한 DNG의 기본 크롭 태그만 검증·수정하고 전체 센서 픽셀·보정 정보는 유지합니다. RAW AUTO는 손대지 않은 비공개 기준본을 보존하고 별도 임시 DNG를 수정합니다. 복구는 작업에 고정된 설정으로 재계산합니다. `SCALER_CROP_REGION`이나 논리 카메라 메타데이터로 물리 RAW의 기하 정보를 바꾸어 표시하지 않습니다.

0.15 실제 HDR 표본은 실제 1/.25/4 노출, 합성, 1.4× JPEG 크롭의 근거입니다. 다만 암부 색잡음이 남았고 다음 촬영 준비는 1,744ms로 목표 1,200ms를 충족하지 못했습니다. 선별한 기기 시험 18개가 통과했습니다. 앱 UiAutomation 시험과 Android CLI의 별도 계측 도우미를 동시에 실행하지 마세요. 새로운 조건 일치 실사진 비교가 나올 때까지 이 화질·시간 한계를 유지합니다.

## 실험 RAW AUTO 초기 기준(0.14.0의 역사적 상태)

당시 RAW AUTO는 별도 `RawCameraViewModel`, 물리 RAW 입력 하나, `RawAutoPhotoStore` 출력 이름공간을 쓰는 선택 경로였습니다. 검증된 색 메타데이터를 재사용하되 새 `NativeSerRender.renderNatural` 진입점만 호출했습니다. 당시 AUTO 시작을 유지하라는 지시는 0.16.0의 RAW AUTO 기본 시작으로 대체되었습니다. RAW DNG 동작과 Night 현상 호환성은 계속 보존합니다.
암부 강도는 작업별로 고정하며 보정에는 보고된 EV를 사용합니다. 프리뷰는 최종 톤을 보여주지 않습니다. 0.14 초기 구현에는 다중 노출 HDR가 없었습니다. 합성 표본 통과를 실사진 화질 승인으로 해석하지 않습니다.
비공개 JPEG 준비 표식은 메타데이터 검증 뒤 복구를 허용합니다. 당시 검증·저장·지연 범위와 다음 단계는 해당 버전의 최종 기록을 확인하세요.

## RAW 작업 흐름과 제어(0.13.0)

헤더의 AUTO/RAW 직접 전환은 카메라 우선 진입을 유지합니다. 사용자가 앱을 종료할 때는 이벤트 발생 시점의 Activity 범위 획득·처리 상태를 모두 확인하며 숨겨진 RAW 작업도 포함합니다. 저장 실패 후 조용히 종료하지 않습니다. 이 기록만으로 운영체제의 프로세스 종료에 대한 영속성을 보장하지 않습니다.
RAW 제어 지원은 선택한 물리 카메라 메타데이터와 명시적으로 지원되는 요청 키를 사용합니다. Night의 새 제어 기본값은 null로 유지하여 RAW Pro 상태가 SER 촬영에 유입되지 않게 합니다. 새 태그와 일치하는 실제 센서 제어 결과를 기다리고 작업 요청을 고정하며, 실제 DNG 메타데이터를 보존하고 같은 설정으로 복원합니다. 당시 검증과 남은 실제 촬영 검사는 `PHOTON_0130_VERIFICATION.md`에 있습니다.

## 히스토그램과 시작 화면 초기 기준(0.12.0의 역사적 상태)

당시 기본 진입은 AUTO였으며 0.16.0부터 RAW AUTO로 대체되었습니다. 진단 화면 우선 진입이나 시작 시 자동 기능 스캔은 복원하지 않습니다. 권한 UI로 카메라 구성을 제어합니다. 당시 RAW는 AUTO 직접 이동, Night는 AUTO 복귀를 제공했습니다. 저장된 작업과의 호환성을 위해 과거 직렬화 경로 이름은 AUTO로 표시합니다.
히스토그램은 선택 활성화인 제한된 프리뷰 Y 분석이며 픽셀·집계값을 저장하지 않습니다. 모든 분석 프레임을 닫고 일시정지·해제 때 비우며 실행기를 종료하고 늦게 온 세대·비율 결과를 거부합니다. 분석 스트림 추가로 일반 정지 사진 해상도를 낮추지 않으며 조합을 유지할 수 없으면 사용자에게 대체 경로를 표시합니다.
소스는 `PhotonHistogram.kt`, `PhotonHistogramAnalyzer.kt`, `PhotonHistogramOverlay.kt`, `PhotonCameraController.kt`이며 당시 근거는 `PHOTON_0120_VERIFICATION.md`입니다.

## 현재 제품 구성

Android/Kotlin/Compose 앱입니다. CameraX AUTO JPEG와 별도 Camera2 RAW 경로, 네이티브 C++ 영상 처리를 사용하며 큰 AUTO 영상은 GLES 연산을 사용합니다. 대상 아키텍처는 arm64-v8a입니다. 학습된 잡음 제거 가중치나 NPU 처리 경로는 없습니다. 버전은 `app/build.gradle.kts`에서 관리하며 `README.md`는 후보 버전별 근거를 연결합니다.

0.11.0에서 별도 SER Night 화면을 추가했습니다. RAW 세션은 셔터 전 화면 진입 시 열며 AUTO JPEG·RAW DNG 알고리즘과 분리합니다. 네이티브 SER 준비와 GLES 3.1 합성·마스크·색 현상이 앱에 있습니다. Python 실험은 고정 가중치 수학 시제품이며 센서 보정이 아닙니다. `PHOTON_RESOLUTION_PLAN.md`에는 카메라 ID 7개의 메타데이터 전용 조사가 있고, 노출된 최대 크기는 16MP가 아닌 12.4848MP입니다.

AUTO는 최종 JPEG 한 장을 공개 저장합니다. TextureGuard와 선택적 DetailGuard는 별도 제어이며 DetailGuard는 상위 질감 스위치가 켜져야 합니다. 선택적 HDR는 기준·짧은 노출을 요청하고 정렬·방사량·움직임 근거를 통과할 때만 합성하며 거부 시 기준본을 보존합니다. RAW3는 합성 또는 기준 DNG 한 장을 저장합니다. 과거 코드를 가져와 원본+처리본 쌍이나 공개 RAW 원본 세 장을 다시 만들지 않습니다.

## 소스 책임 지도

| 담당 기능 | 프로젝트 루트 기준 진입점 |
| --- | --- |
| 앱 이동 / 권한 흐름 | `app/src/main/kotlin/dev/lunacam/app/LunaCamApp.kt`, `MainActivity.kt` |
| AUTO UI / 구도 / 고정 셔터 / 옵션 패널 | `app/src/main/kotlin/dev/lunacam/app/photon/PhotonCameraScreen.kt` |
| 카메라 상태·설정·준비·작업 설정·재시도 | `app/src/main/kotlin/dev/lunacam/app/photon/PhotonCameraController.kt`, 같은 디렉터리의 `PhotonCaptureTiming.kt` |
| 핀치 / 초점 경계 / 회전 정책 | 같은 디렉터리의 `PhotonPreviewTouchListener.kt`, `PhotonPolicy.kt` |
| 초점 표시 / AF 유지 / 비동기 노출 제어 | `PhotonControlPolicy.kt`, `PhotonCameraController.kt` |
| 격자 / 수명주기에 따른 중력 센서 / 화면 기준 수평계 | `PhotonViewfinderAssist.kt`, `PhotonLevelPolicy.kt` |
| 앱의 최근 저장 기록 / 제한된 썸네일 / 외부 뷰어 선택 | `PhotonPhotoViewer.kt` |
| 비율 ID와 정수 크롭 기하 | `PhotonAspectRatio.kt` |
| 센서 노출 브래킷 / 복원 | `PhotonHdrCapture.kt` |
| 디코딩·처리·크롭·JPEG 인코딩·EXIF·단일 파일 저장 확정 | `PhotonAutoPhotoStore.kt` |
| 공통 고부하 처리 잠금 | `PhotonProcessingGate.kt` |
| RAW 카메라 / 물리 입력 / DNG | `app/src/main/kotlin/dev/lunacam/app/photon/raw/` |
| Night 수명주기 / 획득 여유 / SER 설정 / 복구 | `app/src/main/kotlin/dev/lunacam/app/photon/night/SerNightViewModel.kt` |
| Night UI / 물리 RAW 메타데이터 / 단일 JPEG·DNG / 저장 기록 | 같은 `night/` 디렉터리의 나머지 파일 |
| Night 노출 제한 / 제출 보호 / 실제 단계 시간 | `raw/`의 `NightCaptureTiming.kt`, `RawBurstCamera.kt` |
| SER 준비 / CPU 기준 / 전체 해상도 색 현상 | `native/lunacore/src/ser_night.cpp`, `ser_render.cpp` |
| GLES SER 세 단계 / 네이티브 API | `nativebridge/`의 `GpuSerNight.kt`, `NativeSerNight.kt`, `NativeSerRender.kt` |
| GPU 업로드·교대 셰이더·읽어오기 / 자원 정리 | `processing-native/src/main/kotlin/dev/lunacam/processing/nativebridge/GpuTextureGuard.kt` |
| JNI 래퍼와 네이티브 연결 | 같은 `nativebridge/`의 나머지 파일, `native/lunacore/src/*_jni.cpp` |
| CPU TextureGuard / DetailGuard / RAW / HDR | `native/lunacore/src/texture_guard.cpp`, `detail_guard.cpp`, `raw_fusion.cpp`, `hdr_fusion.cpp` |
| 커널 호스트 시험 | `native/lunacore/tests/`, 등록 위치 `native/lunacore/CMakeLists.txt` |
| Kotlin 기하 / 정책 시험 | `app/src/test/kotlin/dev/lunacam/app/photon/` |
| 휴대폰 시험 / 합성 성능 측정 / 헤더 전용 재검사 | `app/src/androidTest/kotlin/dev/lunacam/app/photon/` |

## 유지해야 할 계약

1. **다음 촬영 준비:** 사용자 목표는 셔터부터 다음 촬영 허용까지 1,200ms 이하이며 최종 JPEG/DNG 저장 완료 시간이 아닙니다. 컨트롤러는 처리와 독립적으로 획득을 해제합니다. 진행 중 두 작업과 공통 처리 뮤텍스로 메모리를 제한하며 대기열이 차면 다음 촬영이 늦어질 수 있습니다. 실제 `shutterReadyMs`와 대기열 압력을 그대로 기록합니다. 합성 커널 시간으로 카메라·AF·저장의 절대 기한을 입증할 수 없습니다. 획득·복원 완료 전에 잠금을 풀지 않습니다.
2. **작업별 설정:** 질감·세부·HDR·비율을 셔터 시점에 복사하고 `job.json`에 보존하여 재시도에 사용합니다. 비율이 없는 과거 작업은 최신 UI 설정이 아닌 ORIGINAL로 복구합니다. 처리 중 UI를 바꾸어도 이미 촬영한 사진의 설정은 바뀌면 안 됩니다.
3. **구도:** 획득 세션은 재연결·센서 경로 지연을 피하도록 4:3을 유지합니다. AUTO `PreviewView`는 선택 비율 프레임 안에 FILL_CENTER로 표시하며 저장 시 HDR·세부 처리 뒤 단 한 번의 JPEG97 인코딩 전에 같은 중앙 크롭을 적용합니다. ImageCapture 요청 시 크롭이 아닙니다. 중앙 크롭은 EXIF 90도 회전·반사와 순서를 바꾸어도 일치합니다.
4. **정수 크기:** 비율 ID는 `original`, `wide`, `cinema`, 표시는 4:3·16:9·21:9이며 마지막은 7:3입니다. `centerCrop`은 원본 안에 들어가는 정확한 정수 배수 중 최대 크기를 사용합니다. 4080x3060에서 WIDE는 4080x2295, CINEMA는 4074x1746입니다. 정수 반올림으로 이상적인 프리뷰보다 긴 변에서 몇 픽셀 더 잘릴 수 있지만 리사이즈·늘리기는 하지 않습니다. 세로는 역수 비율을 표시합니다. EXIF 방향은 유지하고 크기 태그는 인코딩한 래스터를 설명합니다. RAW DNG의 센서 픽셀은 전체 크기를 유지하며 AUTO JPEG 비율 설정을 물려받지 않습니다. RAW 자체 비율은 별도의 기본 크롭 태그로 처리합니다.
5. **초점:** `PreviewView.meteringPointFactory`가 크롭된 프리뷰를 카메라 좌표로 변환합니다. FIT_CENTER는 여백을 제외하고 FILL_CENTER는 보이는 뷰 경계를 사용합니다. 직접 계산한 크롭 오프셋을 한 번 더 적용하지 않습니다. 뷰파인더 밖에는 초점 입력을 연결하지 않습니다. [공식 PreviewView API](https://developer.android.com/reference/androidx/camera/view/PreviewView)를 참고합니다.
6. **대체 경로:** HDR·세부 처리 거부와 내보내기 실패를 구분합니다. 원본 이외 비율을 골랐다면 기준본으로 돌아가도 크롭해야 합니다. 크롭·내보내기 실패 시 완전한 비공개 기준본을 재시도용으로 보존하고 잘못된 구도의 사진을 공개 저장하지 않습니다. 저장 성공 뒤 해당 작업의 비공개 입력만 정리합니다. 저장 확정·재시도가 출력을 중복 생성하면 안 됩니다.
7. **화질:** 기존 TextureGuard 판단은 바이트 호환을 유지합니다. DetailGuard는 약한 구조·글자 구멍·국소 극값을 보호하고 평행 경계 근거를 요구하며 변경은 코드값 2 이하로 제한합니다. 선명화가 새 국소 극값을 만들면 안 됩니다. 더 강한 초기 잡음 제거는 희미한 글자를 흐리게 했습니다. `PHOTON_DETAIL_SPEC.md`와 0.8.0 근거를 참고하며 합성 표본만으로 큰 잡음 감소나 DSLR 수준을 홍보하지 않습니다.
8. **GPU:** 셰이더 제출만이 아닌 준비·업로드·실행·읽어오기·정리를 측정합니다. CPU/GPU 바이트와 두 종류 변경 수가 일치해야 합니다. 대체 처리의 입력은 수정되지 않아야 합니다. 세부 처리는 질감과 같은 문맥·업로드 버퍼를 사용하고 최종 읽어오기는 한 번만 합니다. 상주 작업 스레드를 추가하지 않습니다.
9. **RAW:** 프리뷰+RAW는 같은 물리 센서와 그 특성·물리 촬영 결과를 사용합니다. 물리 메타데이터가 없을 때 논리 메타데이터로 대체하지 않습니다. 합성에서 CFA 위상을 유지하고 프리뷰 회전에서 센서 회전을 중복 적용하지 않습니다. `PHOTON_RAW_ALGORITHM.md`를 참고합니다.
10. **프리뷰 제어:** 길게 누르기는 자동 취소를 끈 AF 유지일 뿐 AE 잠금이 아닙니다. 탭·비율·회전·렌즈 변경·해제 시 과거 표시를 무효화합니다. EV는 현재 카메라의 지원 단계·범위와 비동기 `CameraControl`을 사용하며 sleep이나 촬영 대기를 넣지 않습니다. 기록은 요청·적용·보고 인덱스를 구분합니다. 슬라이더 요청이 센서 적용의 증거는 아닙니다. 재연결 시 EV를 초기화하고 `ExposureBiasValue`는 보존합니다.
11. **보조 표시와 뷰어:** 격자·수평계는 출력에 새기지 않는 오버레이입니다. 활성화되고 RESUMED일 때만 중력 센서(미지원 시 가속도계)를 등록하고 일시정지·해제 때 해지하며 유효하지 않거나 평평한 자세는 숨깁니다. 최근 사진은 앱 자신의 제한된 저장 기록만 읽으며 갤러리를 열거하지 않습니다. 썸네일은 준비 해제 후 처리 잠금 밖에서 수행하는 128px IO 작업입니다. 뷰어 조회·ACTION_VIEW 실행은 명시적 사용자 동작에만 따릅니다. ClipData로 이미지 URI 하나의 읽기 권한만 주며 쓰기·접두사·영구 권한을 주지 않습니다. 사라진 선택 앱은 선택기로 돌립니다. UI 화면 캡처에서는 실시간 프리뷰와 썸네일을 모두 숨기고 `LocalInspectionMode=true`로 썸네일 디코딩을 먼저 막습니다.
12. **Night:** 후면 1x·4:3·4080x3060을 유지합니다. 물리 RAW와 색·잡음 메타데이터를 대응시키고 색 정보 누락을 임의의 단위 변환으로 대신하지 않습니다. SER 기준 프레임 영향은 최소 0.5, 추가 암부 상승은 최대 0.35EV입니다. 근거는 휴리스틱(`calibratedSnr=false`)이며 실제 센서 MSE/SNR의 인증된 한계가 아닙니다. 상승이 없으면 기준 RAW 픽셀을 사용합니다. 첫 완전한 비공개 DNG를 현상 전에 보존하고 JPEG/DNG 재시도 전체에서 UUID당 출력 하나를 유지하며 저장 확정 뒤 해당 작업만 정리합니다. Night 톤 곡선을 기존 TextureGuard/DetailGuard에 다시 통과시키지 않습니다. GPU·CPU는 CFA를 유지하고 합성 표본에서 일치해야 합니다.

### Night의 노출·색·시간 해석

50ms 노출 제한과 ISO 보상은 지연 정책이며 추가 광자를 얻었다는 근거가 아닙니다. ISO 한도에 걸리면 어둡게 남을 수 있습니다. 실제 프리뷰 복원과 대기열 여유가 다음 촬영 준비보다 먼저 확인되어야 하며 제출 예측만으로 1,200ms를 입증하지 않습니다.

리비전 2는 일부 하드웨어 음영 보정이 적용되어 있어도 물리 프레임의 잔여 렌즈 음영 지도를 사용합니다. 활성 배열의 픽셀 중심에서 쌍선형 보간하여 WB 전에 적용합니다. 지도가 없거나 잘못되면 DNG를 보존하고 실제 사진에 단위 보정을 지어내지 않습니다. 채널별 CCM 잘라내기를 피하고 휘도 주위로 색역 밖 색차를 줄이며 범위 초과 하이라이트는 공통 RGB 비율로 축소합니다. CPU/GPU RGB 차이 1 이하를 유지합니다.

Night의 초점·WB 연속 유지 시간은 첫 촬영 후 2초이며 추가 촬영으로 연장하지 않습니다. 탭·만료 시 해제하고 AE는 자동을 유지합니다. 최신 물리 프리뷰로 AF/WB 상태·보정·복원을 확인합니다. 오래된 요청·조회가 준비 완료로 표시하지 못하도록 세대 검사를 유지합니다.
리비전 1에는 색 문제가 있었고 3.4초/32.9초는 사용자가 확인한 갤러리 방문으로 중단된 구간을 포함하므로 단독 처리·AF 시간으로 취급하지 않습니다. 새 지연 주장은 중단 없는 `pipelineRevision=4`, SER ON/ON 쌍을 요구하며 원래 경과 시간을 보존합니다.

리비전 2의 일반 색역·LSC 보정 뒤에도 센서에서 잘린 흰색이 WB 후 자홍색으로 나타났습니다. 리비전 3은 네 CFA 모두의 남은 범위가 max(4DN, 범위의 0.5%) 미만인 매우 좁은 포화 근처만 흰색 쪽으로 줄이며 완전 포화 셀은 흰색으로 만듭니다. 충분히 노출된 채널이 하나라도 남은 부분 포화는 유지했지만 사용자는 여전히 보라색을 보았습니다.

리비전 4는 같은 좁은 RAW 여유 범위에서 어느 채널이든 포화된 근거를 별도로 계산합니다. 이 근거가 있을 때만 디모자이크·WB·LSC 뒤의 카메라 RGB를 CCM 전에 공통 첫 포화 한계 `min(LSC_i*WB_i)` 쪽으로 제한합니다. 전역 채도 저하나 의미 기반 흰색 분류 없이 녹색이 먼저 포화된 중성광을 처리합니다. 비포화 입력은 기존 결과를 정확히 유지합니다. 부분 포화된 유색 하이라이트는 색상이 달라질 수 있으며 이는 제한된 현상 정책이지 잃어버린 색·세부의 복원이 아닙니다. 한계가 1보다 작으면 회색 하이라이트가 남을 수 있고 이를 나누어 국소 노출을 지어내면 안 됩니다.

실제 10비트 WB/CCM 중성 노출 변화, 공간 음영, CFA, 포화 색, 낮은 WB 이득, 명확한 희미한 세부를 검사하고 CPU/GPU RGB 차이 1 이하를 유지합니다. 준비 전 카메라 종료 표식은 전체 측정 시간을 보존하면서 중단된 성능 표본을 거부합니다. 사용자가 검토용 사진 두 장을 제공했으며 제품의 일반 촬영 경로에 패치 통계 조사 코드는 남아 있지 않습니다.
리비전 4에서 사용자가 SER ON으로 촬영한 네 장은 헤더·EXIF·단일 출력·실제 준비 검사(423~486ms)를 통과했고 두 장에 SER가 적용되었습니다. 사용자는 모니터 흰색이 정상으로 보인다고 확인했습니다. 이는 해당 장면의 표본이며 보정된 색, 지속적인 꽉 찬 대기열 지연, 일반 화질 인증을 의미하지 않습니다.

## 검증 절차

수정 전에 이전 후보와 소스 기준본을 새 버전의 `output/photon-vX.Y.Z/` 디렉터리에 보존합니다. 기준본 사본은 편집하지 않습니다. Git 명령·작업 트리를 사용하기 전에 실제 저장소 상태를 확인합니다. 과거 APK나 사용자 사진을 덮어쓰지 않습니다.

모든 Kotlin 수정자가 멈춘 뒤 서식 정리를 별도로 실행하고 Gradle을 동시에 실행하지 않습니다. Windows에서는 구성된 JDK/SDK를 사용하며 개인 경로를 소스에 기록하지 않습니다.

```powershell
.\gradlew.bat spotlessApply --no-configuration-cache
.\gradlew.bat nativeSmokeTest spotlessCheck :app:testDebugUnitTest p0JvmTest p1JvmTest p2JvmTest :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest --no-configuration-cache '-Pkotlin.compiler.execution.strategy=in-process'
```

휴대폰 패키지는 `dev.lunacam.app.photon.debug`, 시험 실행기는 `dev.lunacam.app.photon.debug.test/androidx.test.runner.AndroidJUnitRunner`입니다. 서로 맞는 앱·시험 APK를 설치한 뒤 계측합니다. `OK (N tests)`와 보고서 상태를 함께 확인합니다. ADB 종료 코드 0만으로는 시험 실패나 미지원 GPU 결과를 배제할 수 없습니다.

- `PhotonAspectRatioTest` / `PhotonPolicyTest`: 크롭 기하와 초점·회전 정책.
- `PhotonCaptureTimingTest`: 저장 완료 기록의 명시적 미확정 시간과 이후 실제 준비 시간 갱신. 기기 촬영 없음.
- `PhotonAspectDeviceTest`: 합성 크롭, EXIF 방향, 처리·대체 경로, 단일 출력.
- `PhotonAutoStoreDeviceTest`, `PhotonHdrDeviceTest`: 합성 최종 파일·HDR 회귀.
- `DetailDeviceTest`, `photonDetail=1`: CPU/GPU 세부 처리의 정확한 일치와 전체 시간.
- `PhotonDeviceTest`, `RawSessionDeviceTest`: 사진 촬영 없는 실시간 프리뷰·재열기.
- `PhotonHistogramDeviceTest`: AUTO를 명시적으로 구성한 히스토그램·수명주기 검사. 시작 모드를 가정하지 않음.
- `PhotonUiDeviceTest`, `verifyPhotonUi=true`: 글꼴 배율 1/1.35의 세로·가로 비율 제어와 고정 셔터. 실시간 프리뷰를 숨긴 뒤에만 창 이미지를 복사함.
- `PhotonAutoSavedTest`, `verifySavedAuto=true`, `latestAutoJobs=2`: 앱 자신의 정확한 저장 URI, JPEG 헤더·EXIF·공개 저장 상태. 사진 픽셀 접근 없음. 계측 후 앱 재열기.
- `PhotonResolutionMetadataDeviceTest`, `probeResolution=true`: 공개·물리 ID의 일반·고해상도·최대 해상도 맵. CameraDevice·세션·촬영·사진 접근 없음.
- `SerNightGpuDeviceTest`: 실제 GLES 결과 일치. `photonSerGpu=1`은 12.48MP 업로드·읽어오기·정리 시간도 측정. 합성 영상만 사용하며 셔터 성능 측정이 아님.
- `SerNightStoreDeviceTest`: 격리된 저장 기록·복구·단일 출력 멱등성.
- `NightSessionDeviceTest`, `probeNightSession=true`: 물리 프리뷰 준비·재열기. RAW 획득·셔터 호출 없음.
- `SerNightSavedTest`, `verifySerNightPhotos=true`, `nightJobs=2`: 사용자가 촬영한 Night 리비전 4 SER ON 기록·헤더·EXIF, 정확한 4080x3060, GPU JPEG 한 장, 실제 다음 준비 1,200ms 이하 검사. 준비 전 카메라 종료 표본을 시간 초기화 없이 거부. `serApplied`와 상승 셀 수는 별도 확인하며 기준본 저장이 SER 적용의 증거는 아님.

합성 MediaStore 출력 시험은 자기 UUID의 파일만 삭제해야 합니다. 편의상 실제 셔터를 자동화하거나 개인 사진 픽셀을 열어보거나 내보내지 않습니다. 필요한 소량의 사진은 사용자가 촬영하도록 요청하고 픽셀 검토 허가가 없으면 기록·헤더만 사용합니다. 실시간 뷰파인더는 화면 캡처하지 않으며 UI 시험은 PixelCopy 전에 명시적으로 숨깁니다.

## 근거와 인계

계획·빌드·설치·합성 시험·실제 촬영 시험을 구분합니다. 버전·코드, 설치 APK 해시, 실제 출력 크기·수, 단계별 시간, 생략한 검사를 기록합니다. 0.16.0 기능 근거는 `PHOTON_0160_VERIFICATION.md`이며 이후 감사 수정의 검증 상태는 별도 감사 기록을 확인합니다.
개발 APK와 서명하지 않은 릴리스 빌드는 구분합니다. 미서명 APK는 설치 가능한 공개 배포본이 아닙니다. 과거 실제 측정은 해당 버전의 문서에 남기고 이후 APK에서 측정한 값처럼 제시하지 않습니다.
