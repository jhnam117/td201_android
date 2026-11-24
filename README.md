# TD-201 Manager - Android 앱 (APK) 프로젝트

TD-201 PC Manager V1.8.2 (파이썬) 화면과 기능을 HTML로 옮겨 담은 설치형 안드로이드 앱입니다.
Android 7.0 이상 태블릿(가로 화면)에서 동작합니다.

- 화면/기능: `app/src/main/assets/www/index.html` (PC 버전 td201_gui.py 를 함수 단위로 옮긴 것)
- USB 시리얼: 앱에 내장된 드라이버 (FTDI, CP210x, CH340/CH341, PL2303, CDC-ACM)
- 연결: 태블릿 - OTG 케이블 - USB-UART 어댑터 - TD-201 (115200 8N1)
- 같은 index.html 은 PC 의 Chrome / Edge 에서도 열립니다 (Web Serial).

## 1. GitHub에서 APK 만들기 (PC에 설치할 것 없음)

1. https://github.com 에 로그인합니다.
2. 오른쪽 위 `+` > **New repository**
   - Repository name: `td201-android` (아무 이름이나 가능)
   - **Private** 선택
   - 나머지 항목(README 추가 등)은 건드리지 않고 **Create repository**
3. 빈 저장소 화면에서 **uploading an existing file** 링크를 누릅니다.
4. 이 폴더 **안의 모든 항목**(`app`, `gradle`, `build.gradle`, `settings.gradle`, `gradle.properties`,
   `gradlew`, `gradlew.bat`, `README.md`, `.gitignore`)을 전부 선택해 브라우저 창에 끌어다 놓습니다.
   폴더 자체(TD201_Android_Project)가 아니라 안의 내용물을 올려야 합니다.
5. 아래쪽 **Commit changes** 를 누릅니다.
6. `.github` 폴더는 끌어다 놓기로 잘 올라가지 않습니다 (TD-102 때와 같음). 다음처럼 직접 만듭니다.
   - 저장소 화면에서 **Add file > Create new file**
   - 파일 이름 칸에 `.github/workflows/build-apk.yml` 입력
   - 이 폴더의 `.github/workflows/build-apk.yml` 내용을 메모장으로 열어 그대로 붙여 넣기
   - **Commit changes**
7. 위쪽 **Actions** 탭으로 이동합니다. `Build APK` 가 자동으로 실행됩니다 (2~5분).
8. 초록색 체크가 되면 그 항목을 눌러 들어가, 맨 아래 **Artifacts** 의 `TD201Manager-apk` 를 내려받습니다.
   압축을 풀면 `TD201Manager.apk` 가 들어 있습니다.

빨간 X 가 뜨면(빌드 실패) 그 항목을 눌러 나오는 오류 로그를 복사해 전달해 주세요.

## 2. 태블릿에 설치

1. `TD201Manager.apk` 를 태블릿으로 옮깁니다 (USB 케이블, 메일, 메신저, 드라이브 등).
2. 태블릿의 파일 앱에서 APK 를 누릅니다.
3. "출처를 알 수 없는 앱 설치" 를 물으면 허용하고 설치합니다.
4. Play 프로텍트 경고가 나오면 "무시하고 설치" 를 선택합니다 (스토어에 등록되지 않은 앱이라 나오는 안내입니다).

TD-102 Manager 와는 별개의 앱입니다 (앱 ID `com.technohands.td201`). 둘 다 설치해 쓸 수 있습니다.

## 3. 사용

1. OTG 케이블로 USB-UART 어댑터를 태블릿에 꽂습니다.
2. "TD-201 Manager 를 여시겠습니까?" 창이 뜨면 확인합니다.
   ("항상 열기"를 체크하면 다음부터 USB 권한을 다시 묻지 않습니다.)
3. 오른쪽 위 **Port** > Baud rate 확인 > **Connect**. USB 권한을 물으면 허용합니다.
   어댑터가 여러 개 꽂혀 있으면 포트 선택 창이 뜹니다.
4. 이후 사용법은 PC 버전과 같습니다. 태블릿에서 달라진 점:
   - 왼쪽 위 **☰** 버튼: 왼쪽 패널(Address / Status / Received data / Test) 숨기기/보이기.
     화면 폭이 좁은 태블릿(약 1150 dp 미만)에서는 처음에 숨겨져 있고, 열면 탭 위에 겹쳐 보이며 바깥을 누르면 닫힙니다.
   - 숫자 칸의 **− / +** 버튼 = PC 의 스핀 화살표 (누르면 값이 바뀌고 바로 전송, 설정 항목만).
     키보드로 입력한 뒤 Enter(완료) 를 눌러도 전송됩니다.
   - 그래프: 손가락으로 좌우로 끌면 이동(Follow off), **두 번 탭**하면 다시 Follow.
     X 확대/축소는 `X −` / `X +` 버튼 또는 X span.
   - Jog: 버튼을 누르고 있는 동안 이동, 손을 떼면 `st`. 손가락이 버튼 밖으로 나가도 뗄 때까지 유지되고,
     전화/홈 버튼 등으로 앱이 가려지면 즉시 정지(`st`) 합니다.
   - Save CSV / 로그 SAVE: 저장 위치를 고르는 화면이 뜹니다.
   - 경고/확인 창은 앱 안의 창으로 뜨며, 창이 떠 있는 동안에도 수신 데이터는 계속 처리됩니다.
   - 뒤로 가기 버튼: 종료 확인. 앱이 켜져 있는 동안 화면이 꺼지지 않습니다.
   - FTDI 어댑터는 Latency Timer 가 자동으로 1 ms 로 설정됩니다 (그래프가 매끄럽게 그려짐).

## 4. 수정할 때

- 화면/기능 수정: `app/src/main/assets/www/index.html` 만 고치면 됩니다.
  PC 버전(td201_gui.py) 을 고치면 같은 내용을 이 파일에도 옮겨야 합니다.
- GitHub 웹에서 해당 파일을 열어 연필 아이콘으로 고치거나, **Add file > Upload files** 로
  새 파일을 같은 위치(`app/src/main/assets/www/`)에 올리면 APK 가 자동으로 다시 만들어집니다.
- 새 APK 를 배포할 때는 `app/build.gradle` 의 `versionCode` 를 1씩 올리세요
  (`versionName` 은 PC 버전 번호와 맞춰 둠).
- `app/td201.keystore` 는 앱 서명 키입니다. 지우거나 바꾸지 마세요.
  바뀌면 기존 앱을 삭제해야만 새 버전을 설치할 수 있습니다.
- Android Studio 로도 빌드할 수 있습니다: 이 폴더를 Open 한 뒤 Build > Build APK(s).

## 5. 참고

- 태블릿의 "Android System WebView" 가 너무 오래된 버전이면 화면이 정상 동작하지 않을 수 있습니다.
  Play 스토어에서 WebView 와 Chrome 을 업데이트하세요.
- USB 호스트(OTG)를 지원하지 않는 태블릿에서는 어댑터가 인식되지 않습니다.
- USB 시리얼 드라이버는 usb-serial-for-android v3.9.0 (MIT 라이선스) 소스를 포함한 것입니다.
  라이선스: `app/src/main/java/com/hoho/android/usbserial/LICENSE.txt`
- RS-485 (주소 03~32) 는 PC 버전과 마찬가지로 아직 시험하지 않았습니다.
