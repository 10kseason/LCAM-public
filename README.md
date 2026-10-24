# LUNA PHOTON / LunaCam Lab

하늘의 밝은 부분과 거리의 어두운 부분을 자연스럽게 함께 담는 것을 목표로 하는 Android 계산사진 실험 앱입니다. 현재 로컬 개발 후보는 **0.17.0**이며 공개 릴리스는 **0.16.1 개발 미리보기**입니다. 사진 품질과 기기 호환성 검증은 계속 진행 중입니다.

## 촬영 모드

| 모드 | 동작 |
| --- | --- |
| RAW AUTO | 지원 기기에서 기본으로 열립니다. 다중 노출 RAW 정렬·합성과 자연스러운 암부 보정으로 JPEG 한 장을 만듭니다. 처리 실패 시 기준 DNG 보존을 우선합니다. |
| AUTO | 제조사 카메라 처리와 선택적 질감·디테일·HDR 후처리를 사용합니다. RAW AUTO 미지원 기기의 시작 모드입니다. |
| RAW | RAW 한 장 또는 보존 우선 합성 결과를 DNG로 저장합니다. 지원 범위에서 ISO·셔터·초점·화이트밸런스를 조절합니다. |
| SER Night | 별도 야간 처리 경로로 동노출 RAW를 합성하고 현상합니다. RAW AUTO 현상기와 구분됩니다. |

모드들은 같은 화면 구조와 바로 전환 버튼을 사용합니다. RAW 계열은 지원되는 물리 카메라와 디지털 크롭을 조합한 연속 줌, 화면 비율, 터치 초점·노출, 격자, 수평계, 선택적 히스토그램을 제공합니다. 최소 줌은 실제 기기의 렌즈 지원에 따라 달라지며 모든 기기에서 0.6배를 보장하지 않습니다. 확대된 프리뷰는 뷰파인더 경계 안에 표시합니다.

## 설치와 사용 범위

0.17.0에서는 네 촬영 모드 모두 시스템 표시 영역을 제외한 카메라 화면의 72%를 미리보기 영역으로 예약합니다. 사진 전체 구도를 유지하므로 영역 안에 여백이 생길 수 있습니다. 세로 화면은 아래쪽, 가로 화면은 옆쪽에 조작부를 배치합니다. 촬영 요청마다 Android 시스템 셔터음을 한 번 호출하며 앱에 무음 촬영 옵션은 없습니다. 실제 소리 출력은 기기·지역의 시스템 정책에 따르며 음압이나 법적 적합성을 인증한 것은 아닙니다.

RAW AUTO HDR은 black 근처의 유효한 암부 측정값을 합성에 사용하고, 노출로 환산한 잡음 분산에 따라 가중치를 정합니다. 가장 짧은 프레임이 항상 20% 이상 섞이던 제한을 없앴습니다. 1~6% 합성 암부를 측광 1장과 비교하는 회귀 검사를 추가했으며, 실제 하늘·거리의 화질 비교는 다음 촬영 단계입니다.

- Android 15(API 35) 이상, `arm64-v8a`가 대상입니다. RAW 기능에는 Camera2 RAW 출력 지원이 필요합니다.
- 배포 APK는 개발 키로 서명한 시험용입니다. 이전 버전과 서명 키가 같으면 앱 데이터를 유지한 업데이트가 가능합니다.
- 다운로드는 [릴리스 목록](https://github.com/10kseason/LCAM-public/releases), 변경·검증 상태는 [릴리스 문서](docs/RELEASES.md)를 확인하세요.
- 저장 중 앱 내부 종료를 보호하고, 디스크에 기록된 대기 작업은 복구를 시도합니다. 센서 입력을 디스크에 쓰기 전 강제 종료되면 복구할 수 없습니다.
- 프리뷰와 히스토그램은 최종 RAW 현상 결과를 그대로 보여주는 기능이 아닙니다. 움직이는 피사체, 역광, 렌즈 전환, 장시간 촬영은 추가 실사 검증이 필요합니다.

앱은 카메라 권한을 사용합니다. 인터넷·위치·마이크·광범위한 사진 라이브러리 접근 권한을 요청하지 않습니다. 촬영 결과는 MediaStore에 저장하며, 외부 사진은 사용자가 선택한 항목만 엽니다. 공개 저장소에 실사 사진, 기기 진단 보고서, 개인 백업 링크, 서명 키를 포함하지 않습니다.

사진 파일에는 촬영 시각·노출 등 메타데이터가 남습니다. Android `DngCreator`가 만드는 DNG의 `Software`에는 펌웨어 빌드 정보가 자동 기록됩니다. 여기에 표시되는 `user/release-keys`는 빌드 유형·서명 구분이며 사용자 계정이나 실제 키가 아닙니다. 기종·펌웨어 정보는 드러나므로 메타데이터가 없는 파일로 간주해서는 안 됩니다. RAW AUTO·SER Night의 현상 JPEG는 해당 항목에 앱 처리기 이름을 기록합니다.

## 빌드

JDK 17 이상과 Android SDK 플랫폼 36, Build Tools `36.0.0`, NDK `29.0.14206865`, SDK CMake `3.31.6`이 필요합니다. `local.properties`의 `sdk.dir` 또는 `ANDROID_HOME`으로 자신의 SDK 경로를 설정하세요. 개인 경로와 서명 키는 커밋하지 마세요.

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleRelease
```

개발 APK는 `app/build/outputs/apk/debug/app-debug.apk`, 서명하지 않은 릴리스 APK는 `app/build/outputs/apk/release/app-release-unsigned.apk`에 생성됩니다. 개발 앱 ID는 `dev.lunacam.app.photon.debug`입니다. 릴리스 APK를 설치하려면 별도 서명이 필요합니다.

## 검사

```powershell
.\gradlew.bat spotlessCheck :app:testDebugUnitTest p0JvmTest p1JvmTest p2JvmTest :app:lintDebug :app:lintRelease nativeSmokeTest --no-configuration-cache
python -m unittest discover -s experiments/photon-night
```

네이티브 호스트 검사는 Windows에서 CMake와 Visual Studio 2022 C++ Build Tools, 다른 운영체제에서는 Ninja가 필요합니다. 호스트 CMake와 Android SDK CMake는 별도 설치 항목입니다. 기기 검사는 `:app:assembleDebugAndroidTest`로 빌드하며 실제 촬영을 수행하는 검사와 합성 입력만 사용하는 검사를 구분해서 실행해야 합니다. 세부 절차는 [유지보수 안내](docs/AI_MAINTENANCE.md)를 참조하세요.

## 설계·검증 문서

- [전체 설계](docs/DESIGN.md) · [유지보수와 소스 경계](docs/AI_MAINTENANCE.md)
- [RAW 알고리즘](docs/PHOTON_RAW_ALGORITHM.md) · [RAW HDR·줌 검증](docs/PHOTON_0150_VERIFICATION.md)
- [기본 RAW AUTO·공통 화면 검증](docs/PHOTON_0160_VERIFICATION.md)
- [0.16.1 코드 감사와 수정 검증](docs/CODE_AUDIT_0161.md)
- [0.17.0 암부 HDR·큰 미리보기·셔터음 검증](docs/PHOTON_0170_VERIFICATION.md)
- [다른 카메라 소스와의 비교 범위·결과](docs/SOURCE_PROVENANCE.md)
- [외부 구성요소와 라이선스](THIRD_PARTY_NOTICES.md)

공개 코드 검토와 유사도 비교는 독창성이나 법적 무침해의 보증이 아닙니다. 비교 대상과 판정 한계는 출처 문서에 기록합니다. 이 저장소 자체의 이용허락 라이선스는 아직 지정하지 않았습니다. 저장소 공개만으로 임의의 오픈소스 라이선스가 부여되지는 않으며 외부 구성요소는 각각의 라이선스를 따릅니다.
