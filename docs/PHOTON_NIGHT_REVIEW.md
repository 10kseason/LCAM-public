# PHOTON Night: Shadow Evidence Reserve 독립 설계 검토

2026-10-23. **설계 검토이며 구현·배포·실사진 화질 검증 결과가 아니다.**
이번 작업은 현재 코드와 기존 검증 문서, 공개 1차 자료만 읽었다. 사용자 사진 픽셀은 읽지 않았다.
최종 제품 설계 및 릴리스 결정은 별도이며, 기존 AUTO/HDR/RAW 동작은 변경하지 않는다.

## 판단

Shadow Evidence Reserve(SER)의 차별화 방향은 “어두우니 밝힌다”가 아니라
**추가 관측으로 확인한 신호 여유만큼 암부 표시 이득을 허용하고, 근거가 없으면 기준을 유지한다**는
정책이다. 기준 프레임의 세부 구조, 촬영 시간, 출력 잡음 예산을 동시에 제약한다.
이는 PHOTON용 구성·판정 규칙의 제안이다. 동노출 버스트, 정합, 잡음 가중 합성, 톤 매핑 자체는
기존 연구에 있다. 세계 최초·독점 알고리즘·타사보다 우월하다는 주장은 입증하지 않았다.
동노출 RAW 버스트의 선행 사례는 [Hasinoff 등, 2016](https://research.google/pubs/burst-photography-for-high-dynamic-range-and-low-light-imaging-on-mobile-cameras/)에 명시되어 있다.

권장 순서는 다음과 같다.

1. AUTO JPEG 한 장에서 제한적인 표시 톤 개선을 검증한다. 이 단계는 암부 신호 복원이라고 부르지 않는다.
2. 기존 RAW 세션에서 SER의 합성·불확실성 지도를 오프라인 합성 입력으로 검증한다.
3. 실제 RAW→JPEG 색 처리 경로와 나이트 촬영 시간까지 검증된 뒤에만 AUTO 나이트로 연결한다.

GPU는 픽셀 계산을 줄일 수 있지만 노출·센서 읽기·JPEG 인코더·AF 대기시간을 단축하지는 않는다.
기본 4080×3060은 12,484,800픽셀이다. 16MP 표시는 실제 선택 센서/형식의 지원 출력 크기와
결과 메타데이터로 확인할 때만 허용한다. 현재 검증 문서는 후면 RAW를 4080×3060 또는
4000×3000으로 기록하므로 지금 16MP 지원이 확인됐다고 말할 수 없다.

## 현재 코드에서 확인한 재사용 범위

| 경로 | 이미 있는 기능 | 나이트에 그대로 쓸 수 없는 부분 |
| --- | --- | --- |
| `PhotonHdrCapture.kt` | 500ms 이내 미터링, 수동 노출/ISO, AF/AWB 고정, 요청 대기 중 JPEG 작업 겹치기, 복원·취소 처리 | 기준+약 1/4노출 JPEG는 하이라이트용이다. 암부 증거를 모으는 동노출 버스트가 아니다. 1100ms는 추가 요청 접수 예산이며 완료 보장이 아니다. |
| `raw/RawBurstCamera.kt` | 동일 물리 센서의 수동/AE-lock RAW, 결과·버퍼 대응, 1장 또는 3장 `captureBurst`, 실제 NR/EDGE 확인 | 입력 개수는 1 또는 3이며 2장 경로가 없다. 현재 15초 timeout은 나이트 1.2초 성능 보장이 아니다. AUTO CameraX와 별도 세션이다. |
| `raw_fusion.cpp` | black/white·노출·NoiseProfile 정규화, 기준 선택, CFA 위상 보존 정합, 왕복 일치, 전 채널 움직임/세부 재현 검사, 원본 복귀 | 정확히 3입력, 전역 평행이동만 가능. 셀 ±8 탐색으로 RAW 픽셀 ±16 범위이며 경계 최적점은 거절한다. 회전·시차·국소 뒤틀림을 보정하지 않는다. |
| RAW 저장 | 기준 메타데이터·active area 보존, 변경 합성 DNG의 잘못된 단일 NoiseProfile 제거 | 현재 결과는 DNG이다. AUTO용 demosaic·white balance·색 변환·렌즈 음영·톤 곡선이 연결되어 있지 않다. DNG를 이름만 JPEG로 바꿀 수 없다. |
| TextureGuard/DetailGuard | CPU/GPU 동일성, 같은 GPU 업로드의 두 패스, 작은 변경량과 질감 보존 gate | 렌더링된 RGB 공간의 제한적 보정이다. 깊은 암부 복원의 물리적 근거 또는 강력한 노이즈 제거기로 간주하면 안 된다. |
| `PhotonAutoPhotoStore.kt` / 처리 gate | 백그라운드 처리, 원본 fallback, 최종 JPEG 한 장, pending commit, 두 작업 제한 | RAW 작업 버퍼·렌더러가 추가되면 기존 메모리·지연 수치를 재사용할 수 없다. 품질 판단에 changedPixels 수를 쓰면 안 된다. |

기존 RAW는 정규화 신호가 black+1 이하이거나 포화에 가까운 샘플을 제외한다.
어두운 CFA 채널 하나가 불확실해도 네 채널 합성을 거절한다. 안전한 동작이지만 아주 어두운 영역에서는
나이트 합성률이 낮을 수 있다. 단순히 threshold를 낮춰 해결하면 색 유령·black bias를 받아들일 수 있다.
기준 프레임 자동 선택은 “첫 프레임 유지”와 다르므로 SER의 reference 정책을 명시해야 한다.
권장 초기 정책은 후보들의 선명도/노출 검증 후 기준을 한 번 확정하고, tile마다 기준을 바꾸지 않는 것이다.

현재 `Neff`는 전체 평균만 공개한다. SER 톤 결정에는 픽셀/작은 tile의 채택 근거와 불확실성이 필요하다.
추가 진단 출력이 필요하지만 이번 문서는 해당 API나 생산 코드를 변경하지 않는다.

## 촬영 정책: 짧다는 의미를 먼저 고정

“기준 1장 + 최대 2장의 짧은 동노출 보조”에서 동노출은 기준과 같은 **실제 exposure time, ISO,
조리개, 센서/픽셀 모드**를 뜻해야 한다. 보조만 시간을 줄이고 ISO를 높여 밝기를 맞추는 방식은
같은 광자 관측이 아니다. 노이즈 분포도 달라지므로 별도 정책이다.

정지 영역은 짧은 여러 관측의 합으로 이익을 얻지만 노출이 이미 흔들려 흐려진 기준은 복원할 수 없다.
전체 장면을 긴 노출로 먼저 만들고 움직인 영역만 나중에 고치겠다는 접근은 초기 범위에서 제외한다.
매우 낮은 조도에서 노출 시간과 움직임을 함께 다루는 문제는 기존 저조도 연구에서도 별도 핵심 문제다.
[Liba 등, 2019](https://research.google/pubs/handheld-mobile-photography-in-very-low-light/)

기준+보조 모두 flash OFF, 렌즈/줌/초점 고정, 실제 결과 검증을 요구한다. preview metadata가 오래됐거나
AE/AWB/AF가 준비되지 않았으면 무리한 수동 burst를 시작하지 않고 한 장으로 돌아간다.
저조도에서 AF 실패·렌즈 전환·OIS 이동·빛 깜박임은 독립적인 거절 사유다.
노출 상한 숫자는 렌즈와 움직임 검증 전 고정하지 않는다. ISO 상승을 광량 증가로 계산하지 않는다.

현재 RAW API를 재사용하는 초기 실험은 1장 또는 3장만 선택한다. 미래에 1/2/3장을 지원하려면
프레임 수를 명시하는 bounded API가 필요하다. 2장을 3장처럼 복제해 Neff를 늘려서는 안 된다.
`captureBurst`에 이미 제출한 3장을 예산 초과 후 1장으로 간주하고 셔터를 풀어서도 안 된다.

## SER 신호와 불확실성의 정의

아래 수식은 제안이며 임계값·분포 가정은 검증해야 한다. RAW 센서 선형 공간을 전제로 한다.
JPEG에 역 sRGB 변환을 적용했다고 센서 선형 데이터가 복원되는 것은 아니다.

프레임 i, CFA 채널 c에서 black 제거·white 정규화한 관측을 u_i, 기준 대비 유효 노출 비를 e_i라 하면:

```text
u_i = (raw_i - black_i,c) / (white_i - black_i,c)
x_i = u_i / e_i
v_i = (S_i,c * max(u_i, 0) + O_i,c) / e_i²
```

Android NoiseProfile은 CFA별 `sqrt(S*x+O)` 형식이다. 모든 기기가 제공하는 필수 값은 아니다.
JPEG의 ISP 처리가 끝난 RGB에 이 센서 모델을 그대로 적용하지 않는다.
[CaptureResult.SENSOR_NOISE_PROFILE](https://developer.android.com/reference/android/hardware/camera2/CaptureResult#SENSOR_NOISE_PROFILE)

black/white·노출·ISO·CFA·active area·물리 ID·timestamp가 결과와 일치해야 한다.
현재 관측된 O=0은 지원 가능한 메타데이터지만 실제 깊은 암부의 분산이 0이라는 증거가 아니다.
숫자 안정성용 epsilon을 측정된 read-noise floor처럼 취급하지 않는다. black drift, 행/열 패턴,
열 변화, 양자화, 정합 오차를 모델에 넣을 근거가 없으면 추가 신호 여유는 미확인으로 둔다.

### 합성은 기준에 붙어 있고, 증거 지도는 출력을 밝힐 허가만 준다

정합·움직임·색/세부 재현 gate를 통과한 후보의 신뢰 가중치를 r_i∈[0,1]로 둔다.
이는 검증 전까지 확률이 아니라 정책 점수다. 기존 RAW처럼 후보가 기준보다 큰 가중치를 갖지 않도록 한다.

```text
w_0 = 1 / v_0
w_i = r_i * min(w_0, 1 / v_i)
mu = sum(w_i*x_i) / sum(w_i)
z = x_0 + lambda * clamp(mu-x_0, -delta_max, delta_max)
```

lambda=0이면 기준 그대로다. 초기 delta_max는 기존 RAW의 `min(0.01/e_0, 2*sqrt(v_0))`를
출발점으로 비교할 수 있지만 암부 전용 최적값이라고 확정하지 않는다. 세부를 무조건 평활화한 뒤
샤프닝으로 다시 만드는 단계는 두지 않는다. 기준의 모든 고주파를 무조건 그대로 더하면 기준 잡음까지
되살아나므로, “질감 완전 보존”과 “강한 노이즈 제거”를 동시에 보장하지 않는다.

clip이 작동하지 않는 고정 가중치 구간에서 실제 선형 계수를 a_i라 하면:

```text
a_0 = 1-lambda + lambda*w_0/sum(w)
a_i = lambda*w_i/sum(w)                    (i > 0)
v_independent = sum(a_i² * v_i)
v_fusion_bound = v_independent + covariance_bound
mse_conservative = (sqrt(v_fusion_bound) + sqrt(model_error_mse_bound))²
Neff = 1 / sum(a_i²)                       (동일 분산·독립 표본에서만 표본수 해석)
```

모델 오차와 합성 잡음의 상관을 모르면 단순 분산 합은 보수 상한이 아니다. 편향을 포함한 모델 오차
MSE 상한과 RMS 삼각 부등식으로 교차항을 포함한다. 이는 보정된 입력 상한이 있다는 조건이다.
잡음에 따라 선택한 gate, clamp, 공간 필터, warp는 추정 편향과 상관을 만들므로 위 오차식을
최종 출력의 정확한 분포라고 선언하지 않는다. 반복 합성 입력에서 실제 출력 오차를 측정하고
예측보다 작게 잡는 경우를 거절해야 한다. 현재 RAW의 단순 weight Neff를 lambda 이후 Neff 또는
톤 매핑 후 SNR로 그대로 기록하지 않는다.

32×32 또는 64×64 센서 영역의 tile은 불확실성 집계용 초기 후보다. tile 전체를 한 픽셀로 평균내는
것이 아니다. 내부의 낮은 신뢰도 분위수·moving fraction·채널별 최소 여유를 사용하고, 최종 보존은
픽셀 단위로 시행한다. 반복 무늬/어두운 평탄 영역의 모호한 정합은 “움직임 없음”의 증거가 아니다.
약한 글자·직조·머리카락의 edge/polarity 재현 검사는 평균 잔차보다 우선한다.

## 톤 리프트: SNR를 만들지 않고 잡음 여유를 소비한다

톤 증가 자체는 신호와 잡음을 함께 키우므로 센서 SNR를 높이지 않는다.
독립 동일 노이즈 3장을 완전히 평균낸 이상적 경우에도 SNR 배수는 sqrt(3)≈1.73이다.
같은 출력 잡음을 유지하며 늘릴 수 있는 선형 밝기 이득의 예시는 log2(sqrt(3))≈0.79EV뿐이다.
이는 특정 센서의 DR 증가량이나 실제 0.79EV 달성을 뜻하지 않는다. 상관·fallback·기준 가중치 보존으로
실제 여유는 더 작다. 보조 프레임 수만 보고 +2EV/+3EV를 허용하지 않는다.

기준 렌더러의 톤 곡선을 T0, SER 곡선을 T, 표준편차를 sigma라 하면 픽셀 주변의 국소 조건은:

```text
abs(T'(z)) * sigma_SER <= min(display_noise_ceiling, abs(T0'(x_0))*sigma_0)
R_tile = conservative lower estimate of sigma_0 / sigma_SER
extra_EV_tile <= max(0, log2(R_tile))
```

R_tile의 하한을 뒷받침할 모델/검증이 없으면 SER 추가EV=0이다. 센서 잡음·색·움직임 중 하나라도
불확실하면 그 영역의 T=T0, z=x0로 복귀한다. 기본 렌더링 자체의 노출/표시 밝기 선택과
“버스트가 확보한 추가EV”는 별도 기록한다. dark mean이 양수라는 이유만으로 신호 복원 성공이라고 하지 않는다.

지나치게 낮은 신호에서는 conservative signal/std 비도 낮아야 하며 tone lift를 금지한다.
그 기준을 ISO만으로 고정하지 않고 렌즈·노출·센서 온도/관측 잡음별로 검증한다.
예측 신호의 black bias와 상관을 제외하지 못하면 통계적 confidence interval이라는 표현을 쓰지 않는다.

적용 곡선은 monotone, black point 고정, 하이라이트 shoulder 보존을 요구한다.
RGB 공통 gain과 채널 headroom으로 색 clipping을 막되, 이를 지각적 색 보존 보장이라고 부르지 않는다.
공간 gain 지도를 부드럽게 만들 때도 moving/invalid mask 밖으로 이득을 번지게 하면 안 된다.
유효 영역 내부에서만 gain을 낮추며 경계로 갈수록 1로 복귀시켜 halo와 patchwork 밝기를 검사한다.
후속 DetailGuard를 무조건 켜서 lifted noise를 재강조하지 않는다. 기본 검증 조합은 sharpen OFF다.

## JPEG 한 장의 안전한 초기 모드

현재 AUTO의 기존 JPEG 저장·복귀 경로를 유지하는 것이 가장 작은 실험이다.
이 단계는 “암부 표시 개선”으로 표기하고 `evidenceFrames=1`, `calibratedSnr=false`를 기록한다.
이미 ISP가 제거한 질감, black에 잘린 신호, JPEG 양자화 손실은 복원했다고 주장하지 않는다.

- 정확한 sRGB/지원 색공간만 허용하고 gain map/지원하지 않는 색공간이면 원본으로 복귀한다.
- rawNoiseProfile 대신 렌더링 영역의 보수적인 block/noise/clip 위험 지표를 쓴다. 이것도 SNR 보증은 아니다.
- 사용자 opt-in의 작은 전역 monotone shadow curve부터 비교한다. 초기 +0.25EV 같은 상한은
  검증용 파라미터 예시이지 성능/화질 사실이 아니다. 국소적인 강한 대비·색 복원은 초기 범위에서 제외한다.
- 완전 검정·포화·의심스러운 밴딩/압축 블록은 유지하고, 효과가 없으면 JPEG를 재인코딩하지 않는다.
- texture/detail ON/OFF 기존 의미를 유지하고 나이트가 숨겨진 강한 필터를 켜지 않는다.
- 원본 private JPEG는 최종 한 장의 pending commit 성공까지 유지한다. 실패하면 원본 한 장만 저장한다.

밝게 보이는 결과가 곧 더 많은 정보가 된 것은 아니다. 톤만 다른 입력은 동일 톤으로 정규화한
정량 비교와 사용자의 직접 보기로 평가해야 한다.

## 1.2초 목표와 GPU/메모리 예산

판정값은 기존 `shutterReadyMs`와 동일하게 셔터 입력부터 실제 다음 촬영 접수 가능 시점까지다.
`captureMs`, `processingMs`, `finalSaveMs`는 별도로 남긴다. foreground/lifecycle·카메라 준비·빈 queue까지
모두 충족해야 다음 촬영 가능이며 GPU를 background에 넣었다는 사실만으로 달성했다고 하지 않는다.

JPEG/RAW 출력은 기기별 stall 시간을 가질 수 있고, JPEG 엔진은 직전 요청의 영향도 받는다.
지원 크기뿐 아니라 `getOutputMinFrameDuration`, `getOutputStallDuration`과 실제 측정을 확인해야 한다.
[StreamConfigurationMap](https://developer.android.com/reference/android/hardware/camera2/params/StreamConfigurationMap#getOutputStallDuration(int,%20android.util.Size))

새 요청 접수 조건은 다음처럼 phase별 실제 상위 지연을 예산에 넣는 방식으로 설계한다.

```text
elapsed + predicted_remaining_sensor_and_callbacks
        + predicted_restore + guard_margin <= 1200ms
```

예측은 렌즈·크기·노출·온도·출력 형식별 측정으로 만든다. 평균 한 번만 쓰지 않는다.
이미 제출한 작업은 완료/복원을 기다리고, 초과했다면 초과로 기록한다. CameraX의 per-file 취소 부재를
무시하거나 RAW preview restoration이 끝나기 전에 셔터를 풀지 않는다.
CameraX→Camera2 세션 변경이 필요한 AUTO 나이트라면 rebind 시간도 포함해야 한다.
이 비용을 숨기려는 상시 RAW 버퍼/새 서비스는 이번 제안에 포함하지 않는다.

현재 검증된 숫자의 적용 범위는 좁다.

- [0.10.0 AUTO](PHOTON_0100_VERIFICATION.md): HDR OFF의 사용자 촬영 두 번은 다음 셔터954/860ms,
  저장까지1307/1175ms였다. 밤 장면·연속 포화 queue의 근거는 아니다.
- [0.7.5 RAW](PHOTON_075_VERIFICATION.md): 사용자 3장 촬영787ms, 다음 셔터790ms,
  합성331ms, 총1289ms 사례가 있다. 실제 야간 영상 품질은 조사하지 않았다.
- [0.8.0 GPU](PHOTON_080_VERIFICATION.md): 합성12MP 입력의 두 기존 패스 전체 warm160/168ms.
  새로운 정합·noise map·톤 리프트 GPU 패스의 속도나 전력은 아직 측정하지 않았다.

12.48MP에서 RAW16 한 버퍼는 약23.8MiB, RGBA8 한 버퍼는 약47.6MiB이다.
3RAW+출력RAW만 약95.3MiB이고 기존 RAW guide도 약35.7MiB 추가된다.
3RGBA+출력RGBA는 약190.5MiB이며 업로드 복제·GPU ping-pong·encoder·두 작업 queue는 별도다.
따라서 “두 pending jobs”를 곧 “두 나이트 버스트가 동시에 메모리에 있음”으로 구현하지 않는다.
GPU는 한 worker/한 처리 slot, 타일별 scratch 재사용, 한 번의 upload/readback을 우선 검증한다.
GPU fallback은 부분 수정 출력이 아닌 보존 기준에서 시작해야 한다. NPU는 현재 학습 모델이 없으므로
별도 효용이나 속도를 가정하지 않는다.

## 실패 조건과 출시 전 gate

| 실패 위험 | 필요한 판정/회귀 |
| --- | --- |
| 짧은 노출의 검정/밴딩이 그대로 밝아짐 | black drift·row/column noise·quantization을 포함한 synthetic, 평균 bias와 암부 RMSE, 무근거 tile 추가EV=0 |
| 손떨림·회전·rolling shutter·시차 | 전역 이동 밖 조건은 거절. 왕복/uniqueness 기준을 약하게 해서 acceptance를 늘리지 않음 |
| 사람·머리카락·작은 글자·위브 | 대조2..8의 선/점/글자구멍, 이동한 경계, polarity/contrast 보존. 평균 PSNR만으로 승인하지 않음 |
| LED/PWM·노출·AWB 변화 | 실제 노출/ISO/색 상태와 행별 잔차 검사. 같은 request였다는 이유로 같은 광량으로 간주하지 않음 |
| 보조 신뢰도 과대평가 | 동일 프레임 중복, 상관 잡음, 후보가 모두 같은 오정합, S/O 누락·O=0·black/white 변경 회귀 |
| tile 경계 halo·밝기 들쭉날쭉 | 보존 mask 밖 byte 보존, gain 경계의 연속성, clipping/색 이동/밴딩 검사 |
| 잘못된 품질 주장 | 밝기 통일 비교, 실제 noise variance와 예측 오차 비교, 채택률·변경률을 화질 향상률로 쓰지 않음 |
| 지연/열/메모리 | 렌즈별 cold/warm·저조도·연속 촬영 p50/p95/max 및 초과 건수, queue 포화·메모리 부족·열 상태 |
| 저장/취소/복원 | 단일 output, NR/EDGE·EXIF·실제 크기 보존, source private 유지, pending rollback, 늦은 callback 격리 |

합성 정적 장면은 기준 대비 잡음 감소와 bias, moving 영역은 기준 복귀, 구조 영역은 대비/위상 보존을
동시에 통과해야 한다. GPU/CPU 동일성은 모든 confidence map·gain·픽셀·변경count까지 비교한다.
부동소수점 경로의 완전 동일성을 확보하지 못하면 허용 오차와 gate 차이를 별도 명세하고
경계에서 보수적으로 fallback해야 한다. 기존 정수 Texture/Detail의 byte parity 기준은 유지한다.

최종 real-photo 평가는 사용자가 직접 확인하거나 별도 동의한 범위에서만 수행한다.
이번 검토로 확인된 것은 설계 가능성과 기존 코드의 제약이며, 실사진 암부 개선·1.2초 야간 연속 촬영·
16MP 출력은 아직 검증되지 않았다.
