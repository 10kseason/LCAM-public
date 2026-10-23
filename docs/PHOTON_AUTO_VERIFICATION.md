# PHOTON 0.7.0 AUTO/HDR 검증

날짜: 2026-10-23. 기기: 연결된 SM-S938N. 후보 패키지:
`dev.lunacam.app.photon.debug`, 버전 `0.7.0-auto-hdr-debug`, 코드 8.
이 문서는 0.7.0의 당시 결과입니다. 이후 버전의 시작 모드·저장 동작·성능을 증명하지 않습니다.

## 수용 기준

- 목표는 셔터를 누른 뒤 **다음 촬영을 받아들이기까지 1,200ms 이하**이며, 대기열에 의한 지연도 포함합니다. 최종 저장 완료 시간으로 해석했던 이전 기준은 사용자 요청에 따라 정정했습니다.
- 당시 AUTO가 주 경로였으며 HDR과 질감 처리 스위치는 별개이고 초기값은 OFF였습니다.
- 새 AUTO 촬영은 JPEG 한 장을 공개 저장합니다. 새 RAW3 촬영은 합성 DNG 한 장을 저장하며 합성 근거가 부족하면 기준 DNG 한 장을 저장합니다. 기존 갤러리 사진은 건드리지 않습니다.
- 전체 해상도, 보수적인 질감 보호, 정렬 실패 시 합성 거부를 유지합니다.
- 에이전트 검증에서는 실제 사진 픽셀을 보거나 내보내거나 디코딩하지 않았습니다.

## 자동 검증 근거

`output/photon-v0.7.0/quality-gates.log`: Spotless, 디버그·릴리스 앱 및 시험 빌드,
**JVM 시험 130회**(진단 모듈 디버그·릴리스 변형을 포함한 고유 시험 126개),
**호스트 네이티브 대상 4/4**가 통과했습니다. 호스트 대상은 버전, TextureGuard, RAW 합성, HDR 합성입니다.
디버그·릴리스 lint는 오류 0개, 의존성·대상 버전 경고 13개, Compose 할당 안내 1개였습니다. 의존성 갱신은 이 변경의 범위 밖이었습니다.

휴대폰 `PhotonHdrDeviceTest`: 생성한 4080x3060 입력으로 **2/2** 통과했습니다. 네이티브 합성은 **49ms**, 변경 픽셀은 786,432개, 이동은 (0,0)이며 정렬·수용 조건을 통과했습니다. 중간톤 표본과 짧은 노출 입력을 보존했습니다.
결과는 `photon-hdr-benchmark.json`에 있습니다. 카메라 획득, JPEG 디코딩·인코딩, MediaStore 기록, 대기열을 제외한 합성만 측정했으므로 셔터 시간은 아닙니다.

휴대폰 프리뷰·기능 조회·RAW 합성 표본 묶음은 **4/4**이며 셔터나 사용자 사진에 접근하지 않았습니다.
최종 AUTO 저장·프리뷰·TextureGuard 묶음은 **11/11**(`device-final.log`)입니다. 별도의 HDR·RAW 묶음과 합치면 **고유 기기 시험 15개**이며 수명주기 변경 후 프리뷰 시험을 반복했습니다.
최종 출력 하나, 유효한 HDR+질감 통합, 잘못된 노출의 기준 영상 복귀, 불완전 획득 거부, 정리 전 저장 확정 후 재시도, EXIF, 프리뷰·복귀, 핀치 줌을 검사했습니다.

초기 AUTO 저장 묶음은 **3/4**였습니다. 색잡음 표본이 JPEG 색차 축소 과정에서 사라져 사실상 평탄한 이미지에 변경 픽셀이 있을 것으로 기대한 시험 오류였습니다. 표본을 2픽셀 색차 블록으로 바꾸었고 제품 필터 임계값은 유지했습니다. 수정한 시험은 통과했습니다.

최종 4080x3060 합성 표본의 **HDR+질감 디코딩·처리·인코딩·EXIF·공개 저장 경로는 664ms**였습니다. HDR 49ms, 질감 순회 189ms, 나머지 426ms입니다. HDR은 786,432픽셀을 바꾸었고 회색조 표본에서 질감 변경은 0개였습니다. 별도 색차 표본으로 질감 경로의 변경을 확인했습니다. 최종 JPEG는 정확히 한 장을 저장한 뒤 시험에서 삭제했습니다. 664ms에는 실제 카메라 촬영이 없으며 다음 셔터까지의 시간은 별도 측정입니다.

패키징한 디버그 APK SHA-256:
`86d790752f0bfb8a7b07ba89a474edbc0d930eccc9e69cd786ad10cc1ca87e1d`.

## 실제 촬영 검증 절차

사용자가 셔터를 누릅니다. AUTO 기록은 앱 전용 `photon-auto-receipts/*.json`에 저장합니다.
`shutterReadyMs`는 수명주기가 RESUMED이고 카메라가 준비되었으며 대기열에 여유가 있을 때만 기록합니다. `sensorReadyMs`는 이후 대기열 대기를 제외하고 `totalMs`는 최종 저장까지 포함합니다.
RAW 기록은 `photon-raw-jobs/*.json`이며 획득·대기열·합성·저장 시간을 분리합니다.
0.16.1 감사 수정에서는 저장 성공 기록을 다음 셔터 준비와 분리하고, 준비 시점이 아직 없으면 명시적인 null을 먼저 기록하도록 변경했습니다. 이는 위 0.7.0 측정 결과를 변경하지 않습니다.

선택 실행 시험 `PhotonAutoSavedTest`(`verifySavedAuto=true`)와 `RawSavedDngTest`(`verifySavedRaw=true`)는 저장 기록이 가리키는 MediaStore 소유권·완료 상태, JPEG/TIFF 헤더, EXIF만 확인합니다. 사진을 촬영하거나 픽셀 내용을 보지 않습니다.

### v0.7 실제 지연 기준 실패

사용자가 HDR·질감 ON으로 촬영한 두 장은 다음 셔터까지 **2,933ms와 2,223ms**, 획득에 **2,922ms와 2,218ms**가 걸렸습니다.
둘 다 `exposure_settle_exceeded_hdr_budget`을 반환했습니다. 노출 보정 비동기 완료 대기가 예산을 소모하여 실제 짧은 노출 획득·합성을 하지 않았습니다. 이후 질감·저장은 423/411ms였습니다.
이는 사용자의 1,200ms 이하 요구에 실패합니다. 합성 표본의 49/664ms 결과로 이 실패를 대신할 수 없습니다. v0.7.1에서는 EV 안정화 대기를 수동 센서 노출 지정으로 바꾸었습니다.

메타데이터만 읽는 재검사에서는 저장 기록의 MediaStore 행을 찾지 못했습니다. 해당 기록은 처리 결과·시간의 근거이지만 사진의 현재 존재나 EXIF를 증명하지 않습니다. 이후 시험은 요청한 최신 작업만 확인하도록 한정하여 삭제된 과거 사진이 후속 검증을 막지 않게 했습니다. 선택된 행이 없으면 여전히 실패합니다.

실제 장면의 HDR·질감 화질은 미검증입니다. 저장 공간 부족·전원 중단 복구, 지속 연사, 발열, 모든 렌즈, 움직이는 피사체, Android 콜백의 절대 시간 보장은 이 시험으로 입증하지 못합니다. 당시 메모리에만 있던 미완료 RAW 작업은 프로세스 종료 시 유실될 수 있었습니다. 두 작업이 모두 차면 의도적으로 다음 획득을 기다리게 합니다.

## 재현

```powershell
.\gradlew.bat spotlessCheck :app:testDebugUnitTest p0JvmTest p1JvmTest p2JvmTest nativeSmokeTest :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest --no-configuration-cache '-Pkotlin.compiler.execution.strategy=in-process'
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e class dev.lunacam.app.photon.PhotonHdrDeviceTest,dev.lunacam.app.photon.PhotonAutoStoreDeviceTest dev.lunacam.app.photon.debug.test/androidx.test.runner.AndroidJUnitRunner
```

시험 APK는 합성 시험 사진을 만들고 그 출력만 삭제합니다. 과거 버전·APK와 소스 기준본은 버전별 출력 디렉터리에 보존합니다.
