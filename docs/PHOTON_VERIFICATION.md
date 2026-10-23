# LUNA PHOTON 첫 스프린트 검증 — 2026-10-23

> 첫 스프린트의 역사적 검증 기록입니다. 현재 시작 모드·구현 범위·성능은 최신 버전 검증 문서에서 확인하세요.

## 구현과 선택 이유

기존 LunaCam Camera2 프리뷰/상세 진단과 네이티브 골격을 보존하고 앱 모듈의
`photon/` 패키지에 CameraX AUTO와 별도 capability scanner를 추가했다.
기존에는 프리뷰만 가능했지만 이제 사용자 버튼으로 JPEG를 MediaStore에 저장한다.
CameraX가 세션 수명과 저장을 관리하며, 기존 RAW 연구 경로와 동시에 열지 않는다.
새 debug 앱은 `dev.lunacam.app.photon.debug`로 기존 앱과 나란히 설치한다.

주요 변경 파일:

- `app/src/main/kotlin/dev/lunacam/app/photon/`: 촬영 컨트롤러·화면, 진단 조회·화면, 정책.
- `app/src/main/kotlin/dev/lunacam/app/LunaCamApp.kt`: AUTO/PHOTON 진단 진입점.
- `app/src/main/AndroidManifest.xml`, `res/xml/photon_report_paths.xml`: 앱 이름, 제한된 공유 경로.
- `app/build.gradle.kts`, `gradle/libs.versions.toml`: CameraX 1.6.2, 테스트 및 후보 버전.
- `feature-diagnostics/.../CapabilityExplorerScreen.kt`: 실제 촬영 동작에 맞춘 권한 설명.
- `app/src/test/`, `app/src/androidTest/`: 정책·오류·실기기·수동 저장 사진 검증.
- `README.md`, `docs/DESIGN.md`, `docs/PHOTON_SPRINT1.md`: 새 경로와 이전 단계 구분.

## 실기기 근거

실측 대상: Samsung SM-S938N, SDK 37. 진단 데이터에 기기 일련번호, 계정, 위치,
파일 경로, 빌드 지문 또는 이미지 픽셀을 포함하지 않았다. 이전 9월 자료는 이번 결과로
취급하지 않았다. 이번 실행의 원본 JSON과 로그는 `output/photon-v0.1/`에 있다.

| 항목 | 결과 | 근거 |
| --- | --- | --- |
| 앱 설치·실행 | 성공, 사용자가 카메라 권한 허용 | ADB 설치/실행 및 instrumentation |
| CameraX 프리뷰 | ID 0, 1, 2, 3 모두 STREAMING | `device-session-smoke.json` |
| 백그라운드 복귀 | CREATED → RESUMED 뒤 STREAMING | 동일 JSON |
| 공개 기능 진단 | 4개 카메라, probeErrors 0 | `capabilities-v1-final-device.json` |
| 제조사 확장 조회 | 4개 모두 Night supported, HDR unsupported | 동일 JSON; 확장 촬영 검증은 아님 |
| 사용자 촬영 JPEG | 2개, 각 4080×3060, 유효한 JPEG 헤더 | `jpeg-verification.json` |
| 세로 사진 | EXIF 6, 표시 크기 3060×4080 | 2,563,568 bytes |
| 가로 사진 | EXIF 1, 표시 크기 4080×3060 | 3,038,575 bytes |
| 저장/GPS | 둘 다 IS_PENDING=0, GPS 없음 | 기기 내 메타데이터 검사 |
| 자동 촬영 | 0장 | 테스트가 셔터를 누르지 않음 |

사진 검사는 앱 소유자와 `Pictures/LUNA PHOTON/` 조건으로 제한했다. 기기 안에서
JPEG 헤더와 EXIF만 읽었고 이미지 픽셀을 디코딩하거나 PC로 가져오지 않았다.

RAW 공개 크기는 ID 0/2: 4080×3060, ID 1: 4000×3000, ID 3: 3392×2544이다.
ID 0은 LEVEL_3, MANUAL_SENSOR 및 RAW를 노출하며 physical IDs는 2/5/6/7이다.
physical ID가 있다는 사실은 각각을 별도 CameraX 렌즈로 열 수 있다는 의미가 아니다.
현재 선택 UI는 실제 CameraX에 노출된 0/1/2/3만 제공한다.

## 자동 검증

- 새 JVM 정책 검사 5개: 확장 unknown/unsupported 구분, 예외 메시지 비노출,
  missing-key 근거, 방향 경계, 레터박스 탭 제외.
- `p2JvmTest`, `p1JvmTest`, `p0JvmTest`: 성공. 변경 없는 일부 태스크는 Gradle UP-TO-DATE.
  XML 결과는 고유 테스트 106개, variant 중복 포함 110개 레코드, 실패/오류 0개.
- 실제 기기 테스트 3개: capability, 렌즈 전환/복귀, 사용자 저장 JPEG 검사 모두 통과.
- native host smoke: 1/1 통과.
- Spotless, debug/release lint, debug/release APK: 성공. lint의 기존 SDK/버전 고정 관련
  업데이트 경고는 남아 있다. SDK 37 기기에서 실행했지만 compile/target SDK는 기존 36 유지.
- APK는 arm64-v8a. debug는 개발 서명, release는 unsigned이며 공개 배포하지 않았다.

최종 권한·APK 해시·설치 재검증은 `output/photon-v0.1/artifact-manifest.json`과
`packaged-permissions.txt`, `device-packaged-tests-retry.log`를 참조한다.

최종 manifest의 요청 권한은 CAMERA와 AndroidX의 서명 수준 내부 수신자 보호 권한뿐이다.
INTERNET/ACCESS_NETWORK_STATE/저장소/위치/마이크 권한은 없다. 설치된 debug APK의
SHA-256도 보관 APK와 일치한다 (`installed-apk.json`).

## 발견 및 수정한 문제

- PowerShell의 Gradle `-P` 인자 분리: 따옴표로 해결.
- Intent의 `setClipData()` 반환형 연결 오류: `apply`로 수정.
- Kotlin 포맷 검사 2건: 문자열 분리 및 프로젝트 포맷 적용.
- 터치 영역 검사에서 MeteringPoint 내부 API 접근: 공개 Preview resolutionInfo와
  순수 FIT_CENTER 기하 계산으로 교체, 회귀 검사 추가. lint 억제로 우회하지 않았다.
- JPEG 테스트에서 전이 의존성을 직접 참조: 테스트에는 Android 내장 EXIF 파서 사용.
- Media3 간접 의존성이 추가한 ACCESS_NETWORK_STATE: 정지 사진 경로에는 필요 없어
  manifest merger에서 명시적으로 제거. 최종 APK 권한 목록으로 확인한다.
- 마지막 재설치 후 Android의 ONE_TIME 카메라 권한이 만료되어 최초 최종 테스트에서
  프리뷰/진단 2개가 permission denied로 중단됐다. JPEG 검사 1개는 통과했다.
  앱의 권한 안내 화면을 다시 열고 사용자에게 재허용을 요청했다. 이를 앱 촬영 오류나
  통과한 검사로 숨기지 않으며 해당 로그를 보존한다.
  사용자 재허용 후 ONE_TIME 플래그가 해제된 것을 확인했고, 같은 최종 APK에서
  3개 검사 모두 통과했다 (`device-packaged-tests-retry.log`). 테스트 APK만 제거하고
  실제 앱을 다시 열어두었다. 사용자 사진 2장과 기존 LunaCam 앱은 보존했다.

## 아직 통과하지 않은 인수 조건

- 100장 연속 촬영, 장시간 발열·배터리·메모리, 정량 FPS/셔터 지연.
- 눈으로 보는 프리뷰/저장 구도 일치, 정확한 초점 위치, 실제 화질과 블라인드 비교.
- 다수 회전 반복, 다른 카메라 앱의 점유, 권한 거부/회수 및 저장 공간 부족 강제 실패.
- 시스템 파일 선택기를 통한 JSON 저장과 실제 수신 앱 공유의 전체 UI 흐름.
- LUT, RAW/DNG 저장·현상, 수동 Pro 제어, 제조사 확장 촬영, HDR 병합, 초해상도, 동영상.

이 결과는 **기본 JPEG 촬영 MVP와 기기 진단**의 검증이다. 화질 향상, 모든 렌즈의
사진 저장, 모든 오류 복구 경로 또는 100장 안정성을 검증했다는 의미가 아니다.

## 재현

```powershell
.\gradlew.bat :app:testDebugUnitTest p2JvmTest p1JvmTest p0JvmTest --no-configuration-cache
.\gradlew.bat spotlessCheck :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest --no-configuration-cache
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
# 앱을 열어 사용자가 카메라 권한을 허용하고 세로/가로 사진을 직접 저장한 다음:
adb shell am instrument -w -r -e verifySavedPhotos true dev.lunacam.app.photon.debug.test/androidx.test.runner.AndroidJUnitRunner
```

사진 검사 없이 프리뷰/진단만 검사하려면 `-e class dev.lunacam.app.photon.PhotonDeviceTest`를
사용한다. 시스템 갤러리의 다른 사진을 읽거나 지우는 명령은 필요하지 않다.

공식 API 확인: [CameraX 릴리스](https://developer.android.com/jetpack/androidx/releases/camera),
[사진 저장](https://developer.android.com/media/camera/camerax/take-photo),
[확장 API](https://developer.android.com/media/camera/camerax/extensions-api).

