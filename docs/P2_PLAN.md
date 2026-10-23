# P2 구현 계획서 — 안정적인 프리뷰 수명주기

> 상태: **승인 완료 · 구현 완료** (실기기 검증과 APK 조립은 미완, 9장 참고)
> 작성일: 2026-09-20 / 구현 완료일: 2026-09-20
> 선행: P0/P1 완료 (`p1JvmTest` 36 태스크 통과, 2026-09-20 검증)
> 근거 문서: `docs/DESIGN.md` §4.1(FR-PRV-001~006), §5.2, §5.6, §6.3~6.6, §7.1, §7.3, §8.5, §9.1~9.3, §9.10, §22(P2)

---

## 0. 승인받은 선택 사항

| 항목 | 결정 |
|---|---|
| 화면 진입 구조 | 진단 화면(P1)을 기본 유지, 상단에 `프리뷰 열기` 버튼 추가 → `PreviewScreen` 전환 |
| 동시성 스택 | `kotlinx-coroutines` 도입 (설계 §9.3 Mutex/timeout, §6.5 dispatcher 계약을 문서대로 구현) |
| 상태 보유 | `androidx.lifecycle` ViewModel 도입 (§5.6 "camera owner는 activity recreation보다 긴 ViewModel") |
| 조작 범위 | 탭 초점(FR-PRV-004/005) + EV 보정(FR-PRV-006) 둘 다 P2에 포함 |

## 0.1 계획자 판단으로 정한 사항 (승인 시 함께 확정)

1. **모듈 의존 방향 확장**: `feature-camera` → `domain` + `camera-camera2`.
   §6.3의 명문 금지 4개 항목(domain의 Android type 금지 / camera-camera2의 Compose 의존 금지 /
   processing-native의 Camera2 인지 금지 / storage-media의 native handle 인지 금지)은 모두 지킨다.
   `android.view.Surface`는 domain이 받을 수 없으므로 preview surface 계약은 `camera-camera2`에 둔다.
   승인 시 §6.3에 이 허용을 명시적으로 추가 기재한다(§0.1 문서 갱신 규칙).
2. **`CameraPlaceholderScreen.kt`는 수정·삭제하지 않는다.** 새 `PreviewScreen`을 나란히 추가한다.
3. **P1 코드 경로는 로직 변경 없음.** `MainActivity`는 화면 전환 분기와 버튼만 증축한다.
4. `CameraState`의 `Capturing`은 §7.1 원문대로 sealed 계층에 포함하되 **P2에서는 진입 경로를 만들지 않는다**
   (전이표에만 존재, 생산자는 P3).

---

## 1. 산출물 범위

### 포함
- SurfaceView 브릿지 (§9.1) — `AndroidView` 호스팅, surface 크기 안정화 후 세션 구성
- 카메라 상태 머신 + `generationId` (§7.1, §7.3)
- preview-only 세션 (§9.2의 Output 1만; RAW `ImageReader`는 P3)
- 회전/transform (FR-PRV-001/002)
- 탭 초점 (FR-PRV-004/005), EV 보정 (FR-PRV-006)
- background/reopen/error 복구 (§9.3, §5.6)

### 명시적 제외 (P3 이후)
- `ImageReader` / RAW 스트림 / 캡처 / 셔터 버튼 / 저장 / 3A snapshot / 하이라이트 보호 노출
- 히스토그램 YUV 스트림 (§9.2에서 v0.1 기본 세션 제외로 규정됨)
- 렌즈 전환·줌 (FR-PRV-003: 후면 메인 1× 고정)
- volume key 셔터 (FR-PRV-008, 셔터가 없는 P2에서는 무의미)

---

## 2. 모듈별 신규 파일

### 2.1 `domain` — 순수 Kotlin, 기기 없이 100% 테스트 가능
`dev.lunacam.domain.preview` 패키지 신설.

| 파일 | 책임 |
|---|---|
| `CameraState.kt` | §7.1 sealed 계층 + `PreviewRuntime`, `CameraGeneration` |
| `CameraStateMachine.kt` | 허용 전이표 검증, stale generation 무시 규칙 |
| `CameraFailure.kt` | §9.3 오류 분류 (`DISABLED`, `IN_USE`, `MAX_IN_USE`, `DEVICE`, `SERVICE`, `DISCONNECTED`, `OPEN_TIMEOUT`, `CONFIGURE_FAILED`) |
| `RecoveryPolicy.kt` | 오류 → `Retry(backoffMs)` / `RecreateSession` / `ReopenDevice` / `Blocked(사유)` 결정. 재시도 한도 §9.3 그대로 |
| `Matrix3x3.kt` | 3×3 행렬, 곱/역행렬(실패 시 null), 점 변환 |
| `PreviewTransform.kt` | §9.1 원문 필드. `sensorToView`, `viewToSensor`, `visibleSensorRect`, `bufferSize`, `displayRotationDegrees`, `sensorOrientationDegrees`, `mirrored` |
| `PreviewTransformFactory.kt` | (sensorOrientation, displayRotation, facing, activeArray, bufferSize, viewSize) → `PreviewTransform?` |
| `PreviewSizeSelector.kt` | §8.5 순서대로 ≤1920×1080 → ≤1280×720 → smallest acceptable. 뷰 종횡비 일치 우선(FR-PRV-001) |
| `MeteringRegionMapper.kt` | view 탭 → sensor metering rect. clamp + 최소 유효 크기 보장(FR-PRV-004/005) |
| `ExposureCompensationSpec.kt` | range/step 양자화, 지원 없음(range 0..0) 처리 (FR-PRV-006) |
| `PreviewCameraContracts.kt` | `PreviewCameraSelection`(P1 role scoring 결과에서 BACK_WIDE 최고점 선택), `TapPoint`, `PreviewUiCommand` |

### 2.2 `camera-camera2` — Android Camera2 구현
`dev.lunacam.camera.camera2.preview` 패키지 신설.

| 파일 | 책임 |
|---|---|
| `PreviewSurfaceTarget.kt` | `android.view.Surface` + buffer size를 감싼 계약 (feature 모듈이 생성해 넘김) |
| `Camera2PreviewController.kt` | open/configure/preview/close. `Mutex` 직렬화 + generation token (§9.3). open timeout 3초, timeout 후 도착한 `onOpened`는 즉시 close |
| `CameraDeviceOpener.kt` | `openCamera` → `suspendCancellableCoroutine` 래핑 |
| `CaptureSessionConfigurator.kt` | `SessionConfiguration` 구성 + `onConfigureFailed` 처리 |
| `PreviewRequestFactory.kt` | §9.10 준수 — preview builder를 capture builder와 **절대 공유하지 않음**. `CONTROL_MODE_AUTO`, continuous AF(지원 시), AE/AWB auto, EV, AF/AE region |
| `Camera2FailureMapper.kt` | `StateCallback` 오류 코드 / `CameraAccessException` → 도메인 `CameraFailure` |
| `CameraExecutors.kt` | §6.5 — single-thread camera executor (콜백 순서 보존) |

`camera-camera2`는 Compose에 의존하지 않는다(§6.3 준수).

### 2.3 `feature-camera` — UI
| 파일 | 책임 |
|---|---|
| `PreviewSurfaceHost.kt` | `AndroidView` + `SurfaceView` + `SurfaceHolder.Callback`. surface created/changed/destroyed 이벤트만 위로 전달 |
| `CameraPreviewViewModel.kt` | 컨트롤러 소유, `StateFlow<PreviewUiState>` 노출, 회전 생존 |
| `PreviewUiState.kt` | 화면 표시용 상태 (Previewing / Recovering / Blocked / PermissionRequired) |
| `PreviewScreen.kt` | 프리뷰 + AF 인디케이터 + EV 슬라이더 + 오류/차단 안내 + 뒤로가기 |

### 2.4 `app` — 배선만
- `AppGraph`: `cameraControllerFactory` 추가 (§6.4 수동 DI 유지)
- `MainActivity`: `screen` 상태 추가, 진단 ↔ 프리뷰 전환. **기존 P1 로직 라인은 수정하지 않음**

---

## 3. 의존성 추가 (Step 0에서 실제 해석으로 버전 확정)

`gradle/libs.versions.toml`에 추가:
- `kotlinx-coroutines-android`
- `androidx-lifecycle-viewmodel-compose`, `androidx-lifecycle-runtime-compose`
- (테스트) `kotlinx-coroutines-test`

> **버전은 이 계획서에 추측값을 적지 않는다.** Step 0에서 실제 의존성 해석을 돌려
> 통과한 버전만 핀하고, `README.md`의 Pinned baseline 표에 반영한다.
> Compose BOM은 lifecycle을 커버하지 않으므로 별도 버전이 필요하다.

`app`의 `versionCode 2 → 3`, `versionName "0.2.0-p1" → "0.3.0-p2"`.

---

## 4. 테스트 계획 (전부 JVM, 기기 불필요)

### `domain`
| 테스트 | 검증 |
|---|---|
| `Matrix3x3Test` | 곱/역행렬 정확도, 특이행렬 invert → null |
| `PreviewTransformFactoryTest` | displayRotation 0/90/180/270 × sensorOrientation 0/90/270 × front mirrored 조합. sensor→view→sensor 왕복 오차 ≤ eps. 0 크기 surface → null |
| `PreviewSizeSelectorTest` | 뷰 종횡비와 다른 후보에서 stretch 없는 선택, §8.5 fallback 순서, 후보 없음 처리 |
| `MeteringRegionMapperTest` | 중앙/모서리 탭 기대 좌표, 화면 밖 clamp, 최소 크기 보장, layout 전 탭 무시 |
| `ExposureCompensationSpecTest` | step 1/3·1/6 양자화, range clamp, 미지원(0..0) 비활성 |
| `CameraStateMachineTest` | 허용/불허 전이 전수, stale generation 콜백이 상태를 덮지 않음 |
| `RecoveryPolicyTest` | §9.3 오류별 정책과 재시도 한도(IN_USE 최대 2회, DEVICE는 세션 1회+reopen 1회, DISABLED 자동 재시도 없음, SERVICE 무한 반복 없음) |

### `feature-camera`
| 테스트 | 검증 |
|---|---|
| `CameraPreviewViewModelTest` | fake 컨트롤러 + `kotlinx-coroutines-test`. start/stop/재시작 시 open·close 호출 횟수, surface 준비 전 세션 미구성, 회전 시 재오픈 없음 |

### Gradle 태스크 신설 (루트 `build.gradle.kts`)
- `p2JvmTest` → `:domain:test`, `:feature-camera:test`
- `p2Check` → `spotlessCheck`, `:app:lintDebug`, `:app:lintRelease`, `:domain:test`, `:feature-camera:test`, `:feature-diagnostics:test`, `:processing-api:test`, `nativeSmokeTest`
- 기존 `p0*`, `p1*` 태스크는 **변경하지 않는다**

---

## 5. 실기기 완료 조건 (§22 P2) — `docs/DEVICE_MATRIX.md`에 P2 섹션 추가

| 조건 | 측정 방법 |
|---|---|
| 20회 open/close/rotate | 화면 전환·회전 20회 반복, 매회 프리뷰 복귀 확인, logcat 오류 0 |
| preview stretch 없음 | 기준 격자 피사체로 세로/가로 종횡비 왜곡 육안 확인 + 선택된 preview size 기록 |
| tap 좌표 테스트 | 화면 4모서리+중앙 탭 → AF 인디케이터 위치와 실제 초점 대상 일치 |
| 다른 카메라 앱 전환 후 복구 | 기본 카메라 앱 실행 → 복귀 시 자동 재오픈, `DISCONNECTED` 복구 경로 확인 |

실기기 값은 확보 전까지 `[DEVICE-VERIFY]`로 유지한다.

---

## 6. 작업 순서

| Step | 내용 | 검증 |
|---|---|---|
| 0 | 의존성 버전 핀 (`libs.versions.toml`) | 의존성 해석 성공 |
| 1 | `domain/preview` 순수 로직 + 7개 테스트 클래스 | `:domain:test` |
| 2 | `camera-camera2/preview` 구현 | 컴파일 + lint |
| 3 | `feature-camera` SurfaceView 브릿지 / ViewModel / PreviewScreen + VM 테스트 | `:feature-camera:test` |
| 4 | `app` 배선 (AppGraph, MainActivity 증축) | `:app:assembleDebug` |
| 5 | Gradle 태스크 + 문서 (`README.md`, `docs/DESIGN.md` §6.3/§9.1, `DEVICE_MATRIX.md`, `Memory for ai and Human Worker.txt`) | `spotlessCheck` |
| 6 | 전체 게이트 | `p2JvmTest`, `p2Check`, `p1Check` 회귀, `assembleDebug`/`assembleRelease` |

> 체크아웃 경로에 non-ASCII가 없다면 `subst` 우회는 불필요하다. JNI 빌드 실패 시 README의 절차를 사용한다.

---

## 7. 수정되는 기존 파일 (모두 증축, 로직 변경 없음)

| 파일 | 변경 |
|---|---|
| `gradle/libs.versions.toml` | 라이브러리 항목 추가 |
| `build.gradle.kts` (루트) | `p2JvmTest`/`p2Check` 태스크 추가 |
| `app/build.gradle.kts` | `:feature-camera` 의존 + versionCode/Name |
| `feature-camera/build.gradle.kts` | domain/camera-camera2/coroutines/lifecycle 의존 + `test` 설정 |
| `camera-camera2/build.gradle.kts` | coroutines 의존 |
| `domain/build.gradle.kts` | coroutines 의존 (순수 로직만이면 불필요 — Step 1에서 확정) |
| `app/.../AppGraph.kt` | 필드 추가 |
| `app/.../MainActivity.kt` | 화면 전환 분기 + 버튼 추가 |
| `README.md`, `docs/DESIGN.md`, `docs/DEVICE_MATRIX.md`, `Memory for ai and Human Worker.txt` | 문서 갱신 |

**삭제되는 파일 없음. 기존 함수 시그니처 변경 없음.**

---

## 8. 리스크

| 리스크 | 대응 |
|---|---|
| lifecycle/coroutines 버전이 Compose BOM 2026.04.01 / Kotlin 2.3.21과 충돌 | Step 0에서 해석 검증 후 핀. 충돌 시 승인 요청 후 재선택 |
| S25 Ultra에서 SurfaceView transform 제어 부족 | §9.1 규정대로 ADR 작성 후 `TextureView` 검토 — P2 내 임의 전환 금지 |
| ViewModel 생존 중 `onStop`에서 카메라 미해제 → 타 앱 차단 | §5.6대로 `onStop`에서 반드시 close, ViewModel은 상태만 보존 |
| P1 회귀 | `p1Check`를 Step 6 게이트에 포함 |

---

## 9. 구현 결과 (2026-09-20)

### 확정된 버전 (Step 0)

| 라이브러리 | 핀 | 비고 |
|---|---|---|
| kotlinx-coroutines | 1.11.0 | 최신 안정 |
| AndroidX Lifecycle | 2.10.0 | **2.11.0은 compileSdk 37 + AGP 9.1 요구로 배제**. 실제 빌드 실패로 확인 후 하강 |

### 신규 테스트

| 모듈 | 테스트 수 |
|---|---|
| `domain` (preview 8개 클래스 + 기존 P1) | 77 |
| `feature-camera` (`CameraPreviewViewModelTest`) | 13 |

### 계획 대비 변경

1. `PreviewSizeSelector.select`에 `startTier` 파라미터와 `nextTier()`를 추가했다. preview-only 세션에서는
   HD 등급이 FULL_HD의 부분집합이라 도달 불가능한 죽은 코드였는데, `CONFIGURE_FAILED` 복구가 한 단계
   낮춰 재구성하는 §8.5 본래 의도로 연결했다. DESIGN.md §8.5에 기재.
2. `RecoveryPolicy`에 §9.3이 다루지 않는 `OPEN_TIMEOUT` / `CONFIGURE_FAILED` / `ACCESS_ERROR` 규칙을
   정의했다. DESIGN.md §9.3에 "(P2에서 추가)"로 기재.
3. `PreviewTransformFactory`의 visible rect 계산에 서브픽셀 허용오차(1e-6)를 넣었다. 행렬 누적 오차로
   `ceil(2700.0000000001) = 2701`이 되어 모든 구성에서 visible rect가 1픽셀 커지는 결함이 있었다.
4. `CameraState.Configuring(profile: StreamProfile)`의 `StreamProfile`을 P2 범위인
   `(cameraKey, previewSize)`로 정의했다. P3에서 RAW size를 증축한다.
5. `SurfaceHolderPreviewSurfaceProvider.kt` 파일명은 ktlint `standard:filename` 규칙에 맞춰
   계획서의 `PreviewSurfaceHost.kt`에서 변경했다.
6. `feature-camera`가 `PreviewCameraController` 인터페이스를 통해서만 `camera-camera2`에 의존한다는
   점을 DESIGN.md §6.3에 명시적으로 기재했다.

### 미완 항목

| 항목 | 사유 | 해결 방법 |
|---|---|---|
| `:app:assembleDebug` / `assembleRelease` | SDK에 CMake 3.31.6 미설치. `configureCMakeDebug`가 `[CXX1300]`으로 실패. **P2 코드와 무관하며 Kotlin 모듈은 전부 컴파일됨** | SDK cmdline-tools 설치 후 `sdkmanager "cmake;3.31.6"`. 핀된 baseline을 바꾸는 선택이므로 임의로 수행하지 않음 |
| 5장 실기기 완료 조건 4가지 | 호스트에서 검증 불가 | `docs/DEVICE_MATRIX.md`의 P2 절차를 S25 Ultra에서 수행 |

---

## 10. 실기기 1차 테스트 후 수정 (2026-09-21, 0.3.1-p2)

S25 Ultra(SM-S938N, sdkInt 37)에서 프리뷰 표시는 성공. P1 capability 리포트를 코드와 대조해
결함 2건을 찾아 같은 날 수정했다. 상세는 `docs/DEVICE_MATRIX.md`의 "P2 defects" 절.

| 결함 | 내용 | 수정 |
|---|---|---|
| D1 | 계산한 `PreviewTransform`을 `SurfaceView`에 적용하지 않아, 화면 프레이밍과 탭 역변환이 서로 다른 가정을 사용 | `displayedViewRect`를 transform에 추가하고 `PreviewScreen`이 `Modifier.aspectRatio()`로 레이아웃. letterbox 띠 탭은 clamp 대신 무시 |
| D2 | `PreviewSizeSelector`가 **화면** 종횡비로 골라 4:3 센서에서 1920×824(2.33:1) 선택 → 수직 화각 43% 손실, P3 RAW와 프레이밍 불일치 | `select`가 `targetAspectRatio`를 받고 컨트롤러가 **센서 active array** 비율을 전달. 실기기 후보에서 1440×1080 선택 |

### 확정된 설계 결정

프리뷰 프레이밍은 **4:3 WYSIWYG + letterbox**로 한다. §5.2의 "edge-to-edge 프리뷰" 문구와
충돌하므로 §5.2와 §9.1에 결정을 기재했다. RAW 카메라에서는 화면을 꽉 채우는 것보다
"보이는 것 = 찍히는 것"이 우선이다.

### 검증

`p2Check`, `p1Check`, `assembleDebug`, `assembleRelease` 전부 통과. 신규 회귀 테스트:
실기기 preview size 후보 목록에서 `1920x824`가 다시 선택되지 않음을 고정.

