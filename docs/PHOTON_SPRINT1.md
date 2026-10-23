# LUNA PHOTON 첫 스프린트 (2026-10-23)

## 범위와 보존

사용자의 PDF 기반 계속 진행 요청에 따라 기존 LunaCam P0–P2 위에 AUTO 촬영과
PHOTON 진단 화면을 추가한다. 기존 Camera2 프리뷰 및 상세 진단을 유지한다.
원본 PDF는 수정하지 않는다. 이 폴더에는 Git 저장소가 없으므로 변경 전 소스와
기존 debug APK를 `output/photon-v0.1/baseline/`에 로컬 보관했다.

PDF의 첫 스프린트만 이번 구현 범위다. HDR, LUT, RAW 저장/현상, Pro 제어,
초해상도, 동영상, 제조사 확장 촬영은 후속 단계다. 확장은 지원 조회만 한다.

## 구현 선택

- CameraX 1.6.2 Preview + ImageCapture: 기존 Camera2 코드를 교체하지 않고 AUTO 경로 추가.
- 앱 모듈 내부 `photon` 패키지: 첫 스프린트에 불필요한 새 Gradle 모듈을 만들지 않는다.
- 4:3 미리보기/저장 framing, CameraX 좌표 매핑, 실제 카메라 목록과 줌 범위 사용.
- JPEG는 촬영 버튼으로만 MediaStore의 Pictures/LUNA PHOTON에 저장. 위치 태그 없음.
- capabilities-v1 JSON은 기존 상세 리포트와 별도 형식. 키 조회 실패는 probeErrors,
  조회하지 못한 값은 null/unknown, 확인된 미지원만 unsupported로 기록.
- JSON 저장은 시스템 문서 선택기, 공유는 좁은 FileProvider 경로와 시스템 공유창 사용.
- INTERNET/저장소/위치/마이크 권한을 추가하지 않는다.

## 검증 계획

1. 새 코드 컴파일 및 의미 있는 정책/진단 테스트.
2. 기존 JVM 회귀 테스트, Spotless, Android lint, debug/release APK 빌드.
3. 연결 기기 조회, 설치/실행, 공개 API capability 재조회와 앱 오류 확인.
4. 사용자 직접 촬영 후 JPEG/EXIF/저장 결과 검증. 자동 촬영 및 100장 반복은 별도 동의 필요.

ADB 첫 확인은 기기 0대였다. 이전 9월 리포트는 현재 실측으로 재사용하지 않는다.
실제 실행 결과 및 제한은 `PHOTON_VERIFICATION.md`에 기록한다.
