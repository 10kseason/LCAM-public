# PHOTON 0.5.0 질감 보호·핀치 줌 검증

이 문서는 0.5.0 당시 결과를 보존한다. 0.6.0 네이티브 가속 및 RAW 구현의 최신 근거는
[PHOTON_RAW_VERIFICATION.md](PHOTON_RAW_VERIFICATION.md)를 참조한다.

2026-10-23. 개발 후보이며 공개 배포하지 않았다. 설계와 한계는
[PHOTON_TEXTURE_ALGORITHM.md](PHOTON_TEXTURE_ALGORITHM.md)를 참조한다.

## 변경 및 선택 이유

- 기존 AUTO JPEG 저장과 슬라이더 줌에 핀치 줌과 선택적 저장 후처리를 추가했다.
- 핀치는 실제 렌즈의 줌 범위 안에서 작동하며 연속 요청의 취소는 정상 처리한다.
  두 손가락 동작 뒤 마지막 손가락을 뗄 때는 초점 탭을 만들지 않는다.
- 후처리는 기본 OFF이며 설정을 기억한다. ON은 원본을 먼저 저장한 뒤 별도 처리본을
  만든다. 불확실한 수정이나 JPEG 재압축 손실을 사용자가 원본과 비교할 수 있게 했다.
- 방향별 이웃 합의와 작은 색 변화 예산을 사용하는 자체 코드를 작성했다.
  선명화·톤업은 추가하지 않았다. 기존 카메라 알고리즘이나 모델을 가져오지 않았다.
  공통 영상처리 원리를 사용하며 학술적 신규성은 검증하지 않았다.
- CameraX에 이미 포함된 AndroidX EXIF 1.4.2를 직접 의존성으로 명시해 사용한다.
  GPS/MakerNote/일련번호를 복사하지 않는 허용 목록 정책을 적용했다.

## 코드와 검사 범위

| 영역 | 주요 파일 | 검증 |
| --- | --- | --- |
| 제한적 색잡음 처리 | `TextureGuard.kt` | 상수색/회색 질감/색 경계, 고정 seed 잡음, 변화 상한, 행 스트리밍 |
| 저장 처리 | `PhotonPhotoProcessor.kt` | 합성 JPEG 처리·저장, 원본 SHA-256, EXIF 방향/GPS, 잘못된 입력 |
| 줌·설정·화면 | `PhotonCameraController.kt`, `PhotonCameraScreen.kt`, `PhotonPreviewTouchListener.kt`, `PhotonPolicy.kt` | 기기 줌 상태 변화, 핀치/탭 분리, 설정 지속 |
| 설명 및 버전 | README, DESIGN, 알고리즘 문서, Gradle 버전 | 0.5.0-texture-experiment / versionCode 6 |

경로는 `app/src/main/kotlin/dev/lunacam/app/photon/` 기준이다.
자동 검사는 사용자의 사진 픽셀을 읽거나 셔터를 누르지 않는다.

## 합성 화질 검사

- 고정 seed 73, 128×128 평탄한 명도에 색잡음을 더한 fixture의 RGB MSE 비율:
  **0.9196500162**, 변경 픽셀 2,386개. 이 fixture에서 약 8.04% 오차 감소.
- JPEG 재인코딩 **전** `R+2G+B` 동일, RGB 채널별 최대 변화 3 검증.
- 지정한 상수색·회색 미세 명암·강한 색 체크 패턴·색 경계 fixture는 픽셀 동일.
- in-place 행 처리 결과와 별도 출력 결과 동일, 테두리 보존 및 중단 콜백 확인.

이 수치는 실제 카메라 화질 향상률이 아니다. 약한 실제 색 질감과 JPEG 재압축 손실은
이 fixture만으로 평가할 수 없다. 실제 사진 A/B 검증 전 기본 ON으로 승격하지 않는다.

## 실행 근거

최종 빌드 로그, 기기 검사 로그, 수치 JSON 및 APK는 `output/photon-v0.5.0/`에 보관한다.

| 항목 | 결과 | 근거 |
| --- | --- | --- |
| Kotlin 포맷 | 통과 | `final-gates.log` |
| JVM | 고유 테스트 112개, 실패/오류 0 | `jvm-results.json` 및 Gradle XML |
| debug/release 빌드 | 성공 | `final-gates.log` |
| debug/release lint | 각각 오류 0, 경고 13 | 보관한 lint 텍스트; SDK/도구/라이브러리 갱신 권고 |
| 실기기 | SM-S938N, SDK 37, 자동 검사 6개 통과 | `device-tests-final.log` |
| 핀치 | 1× → 약 2.94× → 약 0.85× | `photon-pinch-verification-final.json` |
| 초점 탭 | 핀치 뒤 0회, 독립 단일 탭 1회 | 동일 JSON; 실제 손가락 대신 MotionEvent 주입 |
| 설정 | OFF/ON 저장 확인 후 초기값 복원 | 실기기 테스트 |
| 프리뷰 | 공개 카메라 4개 streaming 및 복귀 통과 | `photon-preview-verification.json` |
| 합성 저장 | 4080×3060, 9,090 ms, 1,042,849 픽셀 변경 | `photon-texture-benchmark-final.json` |
| 저장 안전성 | 원본 SHA-256 동일, EXIF 방향 6 유지, 처리본 GPS 없음, IS_PENDING=0 | 실기기 테스트 |
| 잘못된 입력 | 오류 발생, 원본 해시 동일, 처리본 행 없음 | 실기기 테스트 |
| 설치 대조 | 휴대폰 APK와 보관 debug APK SHA-256 동일 | `installed-apk-verification.json` |
| 권한 | CAMERA와 AndroidX 내부 signature receiver 권한만 | `apk-permissions.txt` |

JVM 112개는 app 11, domain 82, feature-camera 13, feature-diagnostics 4,
processing-api 2다. 같은 테스트의 debug/release 실행은 중복 집계하지 않았다.

초기 포맷 오류를 수정하고 최종 원래 규칙으로 검사했다. 최초 합성 처리 시간은
12,919 ms였으며 작은 색 추출 함수를 inline한 후 8,599 ms, 최종 AndroidX EXIF 경로는
9,090 ms였다. 변경 픽셀 수는 모두 동일하다. 각 1회 개발 빌드 측정이며 열/캐시/런타임
상태를 통제한 통계는 아니다. 현재도 약 9초 대기가 있으므로 빠른 연사 용도로 검증되지 않았다.
15초 예산 초과 시 처리본을 생략하고 이미 저장한 원본을 유지한다.

새 EXIF/설정 관련 lint 경고는 수정했다. 남은 경고를 이유로 도구체인 전체를 교체하지 않았다.
실기기 검사는 테스트가 만든 합성 파일만 삭제하며, 마지막에 테스트 APK를 제거하고
실제 앱을 다시 실행했다. 사용자 사진 픽셀은 조회하지 않았다.

보관 APK:

- `LUNA-PHOTON-0.5.0-debug.apk`: 설치/실기기 검사한 개발 후보.
- `LUNA-PHOTON-0.5.0-release-unsigned.apk`: 빌드 검증용, 서명/공개 배포하지 않음.
- 체크섬은 `artifact-manifest.json` 참조. 이전 0.4.0은 `output/photon-v0.1/`에 유지.

## 남은 검증 및 미구현

- 사람이 실제 손가락으로 느끼는 줌 감도, 사진 ON/OFF의 시각적 품질 비교는 미검증.
- 같은 사진의 원본 / 재압축만 / 처리 후 재압축 3종 비교가 필요하다.
- 장시간 연사, 발열, 저장 공간 부족, 강제 종료 중 저장, OOM 주입은 미검증.
- 디코딩·JPEG 인코딩 중 즉시 취소나 15초 전체 시간 상한을 보장하지 않는다.
- 제조사 ISP 제거, RAW burst 정합·합성, HDR·초해상도는 미구현이다.
  RAW 다중 프레임의 후속 알고리즘은 설계 문서에만 있다.
- 네이티브 코드는 변경하지 않았으며 이번 단계에서 기존 native smoke를 재실행하지 않았다.
- 이전 APK와 수정 전 소스는 보존했다. 원본 PDF·사용자 사진을 수정하지 않았다.
