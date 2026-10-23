# 소스 출처 및 유사 코드 검사

2026-10-24에 공개 준비 중인 LUNA PHOTON 0.16.1 소스와 아래 다섯 공개 저장소를 비교했다. 검사한 범위와 기준에서는 다른 앱의 처리 알고리즘을 그대로 가져온 것으로 판단할 만한 긴 코드 일치를 발견하지 못했다. 이는 모든 코드의 독창성이나 무단 복제가 없음을 보증하는 결과가 아니다.

## 비교 범위

로컬 코드 202개 파일을 대상으로 했다. 운영·도구 파일 126개와 검사 파일 76개이며, 확장자별로 Kotlin 167개, C++ 25개, 헤더 8개, Python 2개다. Kotlin 문자열에 들어 있는 GLSL 셰이더 5개도 별도 추출하여 총 207개 단위를 비교했다.

대상 폴더는 `app`, `camera-camera2`, `domain`, `feature-camera`, `feature-diagnostics`, `native`, `processing-api`, `processing-native`, `experiments`다. 생성된 빌드 파일(`.cxx` 포함), 캐시, 외부 비교 원문, 임시 결과는 제외했다. 외부 저장소에서는 Java·Kotlin·C/C++·헤더·GLSL·Python 운영·도구 소스 1,083개 파일을 비교했다. 외부 검사 폴더는 제외했다. 감사용 `tools/compare_sources.py`는 이 카메라 소스 비교 범위에 포함하지 않는다.

| 비교 저장소 | 고정 커밋 | 비교 파일 수 | 확인한 대표 고지 |
| --- | --- | ---: | --- |
| [PhotonCamera](https://github.com/eszdman/PhotonCamera/tree/b2eff821a858033515eb2700e7292befecc95225) | `b2eff821a858033515eb2700e7292befecc95225` | 688 | 저장소 `LICENSE`: GPL 3 |
| [Open Camera](https://sourceforge.net/p/opencamera/code/ci/0dd4cbe78872df2c6e4eb6cee3fb0d5637b0f52e/tree/) | `0dd4cbe78872df2c6e4eb6cee3fb0d5637b0f52e` | 80 | `gpl-3.0.txt`; 별도 구성요소 고지도 존재 |
| [GrapheneOS Camera](https://github.com/GrapheneOS/Camera/tree/0aa4cb0dccf7f32919a35a431771cec67716dd7e) | `0aa4cb0dccf7f32919a35a431771cec67716dd7e` | 55 | 저장소 `LICENSE`: MIT |
| [DNGProcessor](https://github.com/amirzaidi/DNGProcessor/tree/29a188be16b1e7ba48fd953a73f0f3a03c681a77) | `29a188be16b1e7ba48fd953a73f0f3a03c681a77` | 100 | 저장소 `LICENSE`: LGPL 3 |
| [Android camera-samples](https://github.com/android/camera-samples/tree/2f10e46fc7a09f7208f8d6de72482ecf3e5fb85a) | `2f10e46fc7a09f7208f8d6de72482ecf3e5fb85a` | 160 | 비교한 Android 샘플 헤더: Apache 2.0 |

위 고지는 비교 대상의 출처를 식별하기 위한 기록이다. 비교 저장소를 LUNA PHOTON의 의존성으로 추가하거나 해당 코드를 제품 소스에 복사하지 않았다. 개별 외부 파일과 포함된 구성요소에는 저장소 대표 고지와 다른 조건이 있을 수 있다.

## 방법과 결과

파일 전체의 SHA-256을 비교하고, 주석과 공백을 제거한 연속 토큰을 비교했다. 문자열 내용은 하나의 문자열 토큰으로 처리하되, 추출한 GLSL은 다시 코드로 분석했다. 추가 검사에서는 언어 키워드를 유지하고 다른 식별자를 같은 표식으로 바꿔 변수명 변경에 대한 민감도를 높였다. 수치 상수와 연산자는 유지했다.

64비트 이동 해시는 후보 탐색에만 사용했고, 후보 토큰 배열의 실제 동일성도 다시 확인했다. 반복 구간이 많다는 이유로 후보를 생략하는 상한은 두지 않았다.

| 검사 | 기준 | 결과 |
| --- | --- | ---: |
| 파일 바이트 전체 일치 | SHA-256 동일 | 0개 |
| 원래 식별자 유지 | 연속 50토큰 이상 | 후보 4건 |
| 식별자 정규화 | 연속 140토큰 이상 | 후보 140건 |
| 패키지·import·include 선언 제외 | 연속 40토큰 이상 | 0건 |
| 선언 제외 및 식별자 정규화 | 연속 100토큰 이상 | 0건 |

처음 발견한 144개 후보는 양쪽 소스 위치를 대조한 결과 모두 패키지와 `import` 선언 영역이었다. 정확 일치 4건은 `PhotonCameraScreen.kt`와 `RawCameraScreen.kt`의 Compose 도형·그래픽·화면 구성 import가 Android QR 스캐너 샘플의 import와 겹친 사례다. `CornerRadius`, `Offset`, `Size`, `Color`, `Stroke` 등 공통 API 이름을 사용하는 부분이며, 촬영 제어·RAW 합성·현상 알고리즘 본문의 일치는 아니었다.

식별자 정규화 후보도 패키지 경로를 같은 표식으로 바꾸면서 길어진 import 선언 일치였다. 선언을 제외한 더 낮은 기준의 추가 검사에서는 처리 본문 후보가 나오지 않았다. 이는 일반적인 Android API 사용과 코드 출처 의심 후보를 구분하기 위한 검사이며, 같은 API나 같은 수학 공식을 사용한다는 사실만으로 복제를 판정하지 않는다.

검사 도구에는 공백·주석만 바꾼 합성 복제와 식별자를 바꾼 합성 복제를 넣어 각각 탐지되는지 확인하는 양성 대조가 포함되어 있다. 식별자를 바꾼 대조가 원문 일치로 잘못 보고되지 않는지와 내장 셰이더 추출도 확인했다.

## 기록과 재실행

재현 도구와 원문을 포함하지 않는 검사 기록을 공개 소스에 포함했다.

- [비교 도구](../tools/compare_sources.py): 표준 Python 라이브러리만 사용한다.
- [비교 기록](audit/source-comparison.json): 비교 커밋, 파일별 해시, 기준, 후보 위치, 양성 대조 결과를 담았다.
- [후보 검토 기록](audit/source-comparison-review.json): 최종 소스에서 144개 후보가 선언 영역에만 해당하는지 다시 확인한 기록이다.

공개 기록의 파일 경로는 저장소 기준 상대 경로이며 외부 소스 본문은 포함하지 않는다. 외부 비교 저장소의 복제본과 이전 감사 기록은 공개 대상에서 제외한 `output/public-audit-0.16.0/`에 보존했다. 최초 비교에 섞였던 `.cxx` 생성 파일 2개를 제외하고 최종 소스를 다시 비교한 결과가 위 기록이다.

비교 도구 SHA-256은 `f558f9b1b50acd359fd04a451221a43362c37da7e1136e7b354556870be7cb59`다. Python 3와 Git을 준비하고 각 저장소를 표의 커밋으로 고정한 뒤 다음 형태로 실행한다. 비교 폴더 바로 아래의 각 저장소 이름은 `PhotonCamera`, `OpenCamera`, `GrapheneCamera`, `DNGProcessor`, `camera-samples`를 사용했다.

```text
python tools/compare_sources.py --workspace . --comparators output/public-audit-0.16.0/comparators
```

기본 결과는 `--workspace`로 지정한 프로젝트의 `output/source-comparison.json`에 저장한다. 다른 위치는 `--output <경로>`로 지정하며, 이미 존재하는 결과는 `--overwrite`를 명시해야 덮어쓴다. 기존 결과 경로에 해당 옵션 없이 실행했을 때 비교 시작 전에 거절되고 기존 파일 해시가 유지되는 것도 확인했다.

코드가 바뀌면 파일 수와 해시, 후보 위치도 달라질 수 있으므로 이전 결과를 새 소스에 그대로 적용하지 않는다. 검사 범위는 JSON에 기록된 파일별 해시로 식별한다. Gradle wrapper 등 배포용 공통 도구와 라이브러리는 작성한 카메라 알고리즘과 구분하며, 이 비교가 의존성 라이선스 고지를 대체하지 않는다.

## 확인하지 못하는 범위

- MotionCam은 이번 비교 대상에 포함하지 않았다.
- 비공개·상용 카메라 앱의 내부 소스는 확인하지 않았다.
- 위 다섯 저장소의 다른 커밋, 모든 인터넷 저장소와 과거 개발 이력까지 대조하지 않았다.
- 기준보다 짧은 복사, 구조를 크게 바꾼 코드, 다른 언어로 옮긴 코드, 동적으로 조립한 문자열과 셰이더는 놓칠 수 있다.
- 주석·문서·아이콘·이미지·상표의 유사성이나 라이선스 준수 전체를 이 코드 토큰 검사로 판정하지 않았다.
- 일치가 없어도 출처 문제가 없다고 단정할 수 없고, 일치가 있어도 곧바로 무단 복제를 뜻하지 않는다. 새 근거가 발견되면 해당 파일과 원 출처를 다시 검토한다.
