# 외부 구성요소 고지

프로젝트의 설명·설계·검증 문서는 한국어로 제공합니다. 아래의 외부 라이선스와 저작권 고지는 권리자의 원문을 보존합니다. 이를 번역문으로 대체하거나 제거하지 않습니다.

이 저장소 자체의 이용허락 라이선스는 지정하지 않았습니다. 외부 구성요소의 라이선스가 프로젝트 자체에 자동으로 적용된다는 의미는 아닙니다.

## 빌드 도구

Gradle Wrapper(`gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`)는 Gradle 프로젝트에서 제공한 빌드 도구입니다. 생성 스크립트의 저작권 및 Apache-2.0 헤더를 보존합니다. 포함된 원문은 [Gradle 8.13 라이선스](licenses/GRADLE-LICENSE.txt), [고지](licenses/GRADLE-NOTICE.txt)입니다. Gradle 배포본 전체를 이 저장소에 복사한 것은 아닙니다.

Android Gradle Plugin, Kotlin, Spotless, ktlint, Android SDK·NDK·CMake 등은 빌드 환경에서 별도로 받는 도구입니다. 버전은 `gradle/libs.versions.toml`과 Wrapper 설정에서 확인할 수 있습니다. 도구 다운로드 및 이용에는 각 배포자의 조건이 적용됩니다.

## 앱 의존성

앱은 AndroidX(CameraX·Compose·Activity·Lifecycle·ExifInterface 등), Kotlin 표준 라이브러리 및 kotlinx.coroutines를 사용합니다. 각 의존성은 빌드 시스템에서 내려받으며, 공개 카메라 앱의 소스를 복사해 포함한 것으로 분류하지 않습니다.

0.16.1의 `releaseRuntimeClasspath`에서 실제 외부 아티팩트 112개를 확인했습니다. [구성요소별 라이선스와 공식 메타데이터](docs/audit/runtime-licenses.json), [묶음 원문 고지](licenses/RUNTIME-NOTICES.txt)를 제공합니다. 고지는 APK의 `assets/licenses/THIRD_PARTY_NOTICES.txt`에도 포함합니다. Apache-2.0 외에 CameraX 일부 구성요소의 BSD-3-Clause와 checker-qual의 MIT 고지도 보존합니다. 상위 POM에서 상속하는 항목은 그 출처를 기록하고, POM에 고지가 없는 javax.inject 1은 공식 소스 배포본의 헤더를 확인했습니다.

검사 도구에는 JUnit 4, AndroidX Test, kotlinx.coroutines-test, JSON-java가 포함됩니다. 검사 의존성은 일반 앱과 별도이며 해당 구성요소의 이용허락을 따릅니다.

## 비교 검토에 사용한 외부 저장소

Open Camera, PhotonCamera, GrapheneOS Camera, DNGProcessor 및 Android 공식 카메라 예제는 로컬에서 코드 유사도·출처 비교에만 사용합니다. 비교 저장소, Git 이력, 원문 복사본은 이 저장소와 배포 APK에 포함하지 않습니다. 비교 범위와 한계는 [출처 검토](docs/SOURCE_PROVENANCE.md)에 기록합니다. 프로젝트 이름이나 Android API 호출의 공통성만으로 복제를 판정하지 않습니다.
