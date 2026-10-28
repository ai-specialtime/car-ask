# 차가 알아서 물어봐 — 개발 환경 메모 (현대차그룹 차량용 앱 공모전, 작업판 #905)

## Pleos Playground 콘솔 (2026-10-28)
- 프로젝트 "차가 알아서 물어봐" 생성 (개발사 AI특별시, 카테고리 모빌리티 서비스). 키는 `secrets/pleos.env` (커밋 금지).
- SDK 권한: **Vehicle State 11개 모두 '사용 가능'** — 차량 안전 상태(경고등·타이어 공기압), 좌석, 주행(기어·브레이크), 도어 등. 별도 신청 없음.
- NaviHelper(경로 조회·탐색 설정) 사용 가능. FusedLocation·STT는 콘솔에서 확인 필요.
- **TextToSpeech, Vehicle Control 8개는 '준비 중'** → 음성 안내는 안드로이드 기본 TTS로, 차량 제어는 쓰지 않는다.

## 에뮬레이터 (맥 arm64)
- 이미지: `crp_car_arm64_emulator.zip` (Pleos Connect v2.1.0, API 34, 4.7GB, sha1 24d28eb2…) → `~/Library/Android/sdk/system-images/android-34/pleos_car_emulator/arm64-v8a/`
- AVD `pleos_connect`: `hw.lcd` 2560×1440 density 160, `hw.gpu.mode=host`, RAM 4GB, 콜드 부팅. (avdmanager 기본값은 320×640 폰 화면이라 꼭 바꿀 것)
- 실행: `~/Library/Android/sdk/emulator/emulator -avd pleos_connect -no-snapshot-load -no-boot-anim -no-audio`
- **맥이 바쁠 때(16GB, 다른 작업 중) 버티는 설정** (10-28): 화면 1920×1080 density 120(레이아웃은 2560×1440 mdpi와 같은 dp), 오디오 끔, RAM 8GB(실제 사용 ~1GB).
  안 쓰는 내장 앱 끄기 → 부하 30~60 → 5: `adb shell pm disable-user --user 10 <패키지>` (ai.umos.ambientai, musicvisualizer, drivingview, globalsearch, semanticsearch, mediaapp, phoneprojection, ai.pleos.gleoai.model.provider). 되돌리기: `pm enable`.
  CPU가 모자라면 system_server watchdog가 시스템을 재시작해 차량 서비스가 끊긴다(`dumpsys dropbox --print system_server_watchdog`). 서운 iOS 시뮬레이터 등 다른 시뮬레이터는 꺼 둔다.
- Java: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`

## Virtual Console
- `adb forward tcp:8088 tcp:8088` → 브라우저 `http://127.0.0.1:8088/`
- **브라우저 탭이 앞에 열려 있어야 동작**(`browser.active`). 탭이 뒤에 있으면 `emulator_inactive`·`DDS OFFLINE`.
- HTTP로도 조작 가능(PUT, 시연 자동화에 사용):
  - 경고등: `PUT /vehicle/telltales/simulator?type=TPMS&state=On&color=Yellow` (EngineOilPressure, EngineTemperature, ABS 등 37종, Off/On/Blink)
  - 타이어 공기압: `PUT /vehicle/tire-pressure?area=1114113&pressure_kpa=150` (앞왼 1114113, 앞오 1114191, 뒤왼 1179649, 뒤오 1179727)
  - 착석: `PUT /vehicle/seat?area=passenger&value=true&kind=occupied` (driver, passenger, rear_left, rear_right)
  - 기어: `PUT /vehicle/gear?gear=Drive` (Park, Reverse, Neutral, Drive)
  - 상태 확인: `GET /vehicle/state`, 전체 목록: `GET /vehicle/catalog`
- 콘솔은 **별도 브라우저 창**으로 열어 둔다(탭 뒤로 가면 끊김).
- 확인됨(10-28): TPMS·엔진오일 경고등 On, 앞왼 타이어 150kPa, 3좌석 착석, 기어 D가 DDS에 반영(`bridge_ready: true`, actual 값 갱신). 차 화면에 공조·주행거리 변화 표시됨. 홈 화면 기어 표시는 P 그대로 → 앱에서 SDK로 읽히는지는 MVP 첫 화면에서 확인.

## 앱에서 SDK로 읽기 (10-28, Vehicle SDK 2.3.2)
| 값 | API | 결과 |
|---|---|---|
| 경고등(TPMS 등) | `Safety.getTelltalesInfo` / `registerTelltales` (PRIVATE_CAR_SAFETY) | ✅ 콘솔에서 켜면 즉시 콜백 |
| 기어 | 안드로이드 `VehiclePropertyIds.GEAR_SELECTION` (CAR_POWERTRAIN) | ✅ 실시간 P↔D |
| 타이어 저압 기준 | `Tire.getLowTirePressure` | ✅ 137kPa |
| 좌석 착석 | `Seat.getCurrentSeatOccupancy` | ⚠️ 읽히지만 콘솔 변경이 안 옴(늘 4명) |
| 타이어 실제 공기압 | `Tire.getTirePressure` | ❌ TIRE_PRESSURE 미지원 |
| 엔진오일 | `Safety.getEngineOilLevelState` | ❌ ENGINE_OIL_LEVEL 미지원 |
- **D단에서는 시스템이 앱 화면을 막는다**("주행 중에는 사용할 수 없습니다") → 통화 흐름은 백그라운드 서비스 + 음성, 화면은 P단에서만. 기획서의 '주행 중 음성만' 원칙과 일치.
- 대표 장면은 **TPMS 경고등**으로 시작한다(실제 공기압 값은 못 읽음).

## 통화 다리 (bridge/)
- `bridge/server.py`: 차 앱 → `POST /ask {shops, question, mode}` → P2 엔진으로 가게에 차례로 묻고 '된다'면 멈춤 → `GET /ask/{id}`. 에뮬레이터에서는 `http://10.0.2.2:8790`.
- 실행: `cd bridge && ../../Project_2_AI-Call/engine/.venv/bin/python server.py`
- mode: `sim`(각본, 키·한도 안 씀) / `sim-llm`(실제 Gemini) / `local`(맥 마이크·스피커, 사람이 직원 역할 — 영상용)
- `bridge/demo_tire.json`: 가상 타이어 가게 3곳(꽉 참 → AI 거절 → '지금 오면 10분'). 시험 결과 3번째 가게 선택 ✅
