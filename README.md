# 차가 알아서 물어봐

> 차가 경고등·탑승 인원·도착 예정 시간을 보고, AI가 운전자 대신 근처 가게에 전화로 "지금 되나요?"를 물어본 뒤 되는 곳으로 바로 길을 안내하는 Pleos Connect 앱.

## 대표 장면
1. 주행 중 **타이어 공기압(TPMS) 경고등**이 켜짐 → 앱이 Vehicle SDK 콜백으로 감지
2. 음성 안내: "타이어 공기압 경고가 떴어요. 근처 타이어 가게 3곳에 지금 봐 줄 수 있는지 제가 전화로 물어볼게요."
3. AI가 가게에 차례로 전화 (첫마디에 AI임을 밝힘) → 안 되는 곳은 건너뛰고 '된다'는 곳에서 멈춤
4. "동네타이어에서 지금 가면 10분이면 된대요. 길 안내할게요." → NaviHelper로 경로 설정
5. 정차(P단)하면 물어본 가게별 결과를 화면으로 확인 (주행 중엔 시스템이 앱 화면을 막으므로 음성만)

## 사용한 Pleos SDK / API
| SDK | API | 쓰임 |
|---|---|---|
| Vehicle – Safety | `getTelltalesInfo`, `registerTelltales` | 경고등(TPMS 등)으로 문의 시작 |
| Vehicle – Tire | `getLowTirePressure` | 저압 기준값 |
| Vehicle – Seat | `getCurrentSeatOccupancy`, `registerSeatOccupancy` | 탑승 인원 (식당 인원) |
| Android Car | `VehiclePropertyIds.GEAR_SELECTION` | 주행(D)/정차(P) 판단 |
| NaviHelper | `requestRoute` | 되는 가게로 경로 설정 |

## 구성
```
app/      Pleos Connect 앱 (Kotlin, Jetpack Compose, minSdk 28)
bridge/   앱 ↔ AI 통화 엔진 중계 서버 (Python, aiohttp)
apk/      빌드된 APK
docs/     개발 환경 메모
```

## 실행 방법
### 1. 테스트 환경
- Android Studio + **Pleos Connect Emulator v2.1.0** (API 34, Automotive) + Pleos Connect Virtual Console
- 맥(Apple Silicon)에서 확인. 에뮬레이터 설정은 `docs/SETUP.md`

### 2. 중계 서버 (각본 모드: 키 없이 반복 시연)
```bash
cd bridge
python3 -m venv .venv && .venv/bin/pip install aiohttp
.venv/bin/python server.py          # 0.0.0.0:8790, 에뮬레이터에서는 http://10.0.2.2:8790
```
> 실제 음성 통화(`mode: sim-llm`, `local`)는 별도 AI 통화 엔진이 필요합니다.

### 3. 앱 설치·실행
```bash
adb install -r -g apk/<APK 파일>
adb forward tcp:8088 tcp:8088       # Virtual Console: http://127.0.0.1:8088/ (브라우저 창을 앞에 열어 둘 것)
```
1. 앱 실행 → "경고등을 지켜보고 있어요"
2. Virtual Console에서 Motion › Gear Selection = **D**, Safety › Telltales › **TPMS = On**
3. 음성 안내와 함께 가게 3곳 문의 → 내비 경로 설정
4. Gear = **P** → 앱에서 결과 확인 (또는 화면의 "지금 근처 타이어 가게에 물어보기" 버튼)

## APK
`apk/` 폴더 (`팀명_앱이름_release.apk`)

## 원칙
- 가게에는 항상 AI임을 먼저 밝히고, 거절하면 바로 정중히 끊습니다.
- 주행 중에는 화면 조작 없이 음성만 씁니다.
- 통화 녹음은 저장하지 않고 결과 요약만 남깁니다.
