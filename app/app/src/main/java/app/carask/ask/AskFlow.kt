package app.carask.ask

import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.navi.helper.NaviHelper
import android.content.Context
import android.util.Log
import app.carask.vehicle.CarSignals
import app.carask.vehicle.CarState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** 가게 한 곳에 물어본 결과 */
data class ShopResult(val name: String, val distanceM: Int, val status: String, val summary: String, val ok: Boolean)

data class AskState(
    val phase: Phase = Phase.WATCHING,
    val trigger: String? = null,          // 무엇 때문에 시작했나 (예: "타이어 공기압 경고")
    val calling: String? = null,          // 지금 전화 중인 가게
    val step: String? = null,             // connected · asking · confirming
    val results: List<ShopResult> = emptyList(),
    val chosen: ShopResult? = null,
    val spoken: List<String> = emptyList(), // 운전자에게 소리로 말한 문장
    val error: String? = null,
) {
    enum class Phase { WATCHING, CALLING, FOUND, NONE_FOUND, FAILED }
}

/**
 * 대표 장면: 차량 경고등(TPMS) → 근처 타이어 가게에 AI가 차례로 전화 → '된다'는 곳으로 길 안내.
 * 주행 중(D)에는 시스템이 화면을 막으므로 모든 안내는 소리로 하고, 결과 화면은 P단에서 본다.
 */
class AskFlow(private val context: Context, private val signals: CarSignals) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val bridge = BridgeClient()
    private val voice = Voice(context) { line -> _state.update { it.copy(spoken = (it.spoken + line).takeLast(8)) } }
    private val navi = NaviHelper(context.applicationContext)
    private val _state = MutableStateFlow(AskState())
    val state: StateFlow<AskState> = _state
    private var job: Job? = null
    private var handledWarnings = emptySet<String>()

    private var askedDestination: String? = null

    /** 목적지·남은 시간: 식당이 목적지이고 도착 15분 전이면 자리·주차를 대신 물어본다(장면 2). */
    private val naviListener = object : NaviHelperEventListener {
        override fun onDestinationInfo(destinationInfo: DestinationInfo) {
            scope.launch { onDestination(destinationInfo) }
        }

        override fun onCurrentLocationInfo(currentLocationInfo: ai.pleos.playground.navi.data.CurrentLocationInfo) {
            Log.d(TAG, "navi 현재 위치 $currentLocationInfo")
        }
    }

    fun start() {
        navi.initialize(onConnected = {
            Log.d(TAG, "navi connected")
            navi.addListener(naviListener)
        }, onFailed = { Log.w(TAG, "navi 연결 실패: $it") })
        scope.launch { signals.state.collect { onCar(it) } }
        scope.launch {
            while (true) {
                delay(5000)
                runCatching { navi.getDestinationInfo() }
                runCatching { navi.getCurrentLocationInfo() }
            }
        }
    }

    private fun onDestination(d: DestinationInfo) {
        val minutes = d.remainTime / 60
        Log.d(TAG, "destination ${d.name} 남은 ${minutes}분 ${d.remainDistance}m")
        if (_state.value.phase == AskState.Phase.CALLING || askedDestination == d.name) return
        if (!d.name.contains("식당") || minutes > 15) return
        askedDestination = d.name
        val people = signals.state.value.passengers.coerceAtLeast(1)
        val eta = LocalTime.now().plusMinutes(minutes.toLong()).format(DateTimeFormatter.ofPattern("a h시 m분", java.util.Locale.KOREAN))
        ask(dinner(people, eta, minutes))
    }

    /** 시연용: 식당으로 목적지를 잡는다 (도착 15분 이내면 곧바로 장면 2가 시작) */
    fun goDinner() {
        val r = JSONObject(context.assets.open(DINNER_ASSET).bufferedReader().readText()).getJSONArray("shops").getJSONObject(0)
        askedDestination = null
        runCatching {
            navi.requestRoute(RouteInfo(latitude = r.getDouble("lat"), longitude = r.getDouble("lng"),
                displayLongitude = null, displayLatitude = null, poiName = r.getString("name"),
                poiId = r.getString("id"), poiSubId = "0", address = r.getString("name")))
        }.onFailure { Log.w(TAG, "식당 길 안내 실패: $it") }
        voice.say("${r.getString("name")}으로 안내를 시작할게요. 도착 15분 전에 자리를 대신 확인할게요.")
    }

    private fun onCar(car: CarState) {
        val fresh = car.warnings.toSet() - handledWarnings
        handledWarnings = car.warnings.toSet()          // 꺼졌다 다시 켜지면 다시 물어본다
        if (_state.value.phase == AskState.Phase.CALLING) return
        if ("TPMS" in fresh) ask(TIRE)
    }

    /** 시연용: 화면 버튼으로 바로 시작 */
    fun askNow() = ask(TIRE)

    private fun ask(s: Scenario) {
        job?.cancel()
        job = scope.launch {
            val body = JSONObject(context.assets.open(s.asset).bufferedReader().readText())
            s.question?.let { body.put("question", it) }
            val shops = body.getJSONArray("shops")
            _state.value = AskState(phase = AskState.Phase.CALLING, trigger = s.trigger, spoken = _state.value.spoken)
            voice.say(s.intro(shops.length()))
            val id = runCatching { bridge.start(body) }.getOrElse {
                fail("통화 연결 서버에 닿지 않아요: ${it.message}")
                return@launch
            }
            var announced = 0
            while (true) {
                val st = runCatching { bridge.status(id) }.getOrElse { fail("진행 상황을 못 받았어요: ${it.message}"); return@launch }
                val results = st.getJSONArray("results").let { arr ->
                    (0 until arr.length()).map { i ->
                        val r = arr.getJSONObject(i)
                        val shop = r.getJSONObject("shop")
                        val card = r.getJSONObject("card")
                        ShopResult(shop.getString("name"), shop.optInt("distance_m"), card.optString("status"),
                            card.optString("answer_one_line"), r.optBoolean("ok"))
                    }
                }
                val cur = st.opt("current").let { if (it is Int) shops.getJSONObject(it).getString("name") else null }
                val steps = st.getJSONArray("steps")
                _state.update { it.copy(calling = cur, step = if (steps.length() > 0) steps.getString(steps.length() - 1) else null, results = results) }
                // 새로 끝난 가게마다 한 줄씩 알려 준다 (안 된 곳)
                while (announced < results.size) {
                    val r = results[announced++]
                    if (!r.ok) voice.say("${r.name}: ${r.summary} 다음 곳에 물어볼게요.")
                }
                when (st.getString("state")) {
                    "done", "cancelled" -> {
                        val chosen = results.firstOrNull { it.ok }
                        if (chosen != null) {
                            _state.update { it.copy(phase = AskState.Phase.FOUND, chosen = chosen, calling = null) }
                            if (s.routeToChosen) {
                                voice.say("${chosen.name}에서 ${chosen.summary} 그쪽으로 길 안내할게요.")
                                route(shops, chosen.name)
                            } else {
                                voice.say("${chosen.name}에 확인했어요. ${chosen.summary}")
                            }
                        } else {
                            _state.update { it.copy(phase = AskState.Phase.NONE_FOUND, calling = null) }
                            voice.say(s.noneFound)
                        }
                        return@launch
                    }
                }
                delay(1000)
            }
        }
    }

    private fun route(shops: org.json.JSONArray, name: String) {
        val shop = (0 until shops.length()).map { shops.getJSONObject(it) }.first { it.getString("name") == name }
        runCatching {
            navi.requestRoute(
                RouteInfo(
                    latitude = shop.getDouble("lat"), longitude = shop.getDouble("lng"),
                    displayLongitude = null, displayLatitude = null,
                    poiName = name, poiId = shop.optString("id"), poiSubId = "0", address = name,
                )
            )
        }.onFailure { Log.w(TAG, "길 안내 실패: $it") }
    }

    private fun fail(msg: String) {
        Log.w(TAG, msg)
        _state.update { it.copy(phase = AskState.Phase.FAILED, error = msg, calling = null) }
        voice.say("지금은 대신 물어보기가 어려워요. 정차하면 다시 해 볼게요.")
    }

    fun stop() {
        scope.launch { job?.cancel() }
        runCatching { navi.release() }
        voice.shutdown()
    }

    private data class Scenario(
        val trigger: String,
        val asset: String,
        val intro: (Int) -> String,
        val question: String? = null,          // null 이면 각본 파일의 질문
        val routeToChosen: Boolean = true,      // '된다'는 곳으로 길 안내 (식당은 이미 목적지)
        val noneFound: String = "지금 바로 봐 줄 수 있는 곳을 못 찾았어요. 정차하면 결과를 보여 드릴게요.",
    )

    companion object {
        private const val TAG = "CARASK"
        private const val DINNER_ASSET = "demo_dinner.json"
        private val TIRE = Scenario("타이어 공기압 경고", "demo_tire.json", { n ->
            "타이어 공기압 경고가 떴어요. 근처 타이어 가게 ${n}곳에 지금 봐 줄 수 있는지 제가 전화로 물어볼게요."
        })

        private fun dinner(people: Int, eta: String, minutes: Int) = Scenario(
            trigger = "식당 도착 15분 전", asset = DINNER_ASSET,
            intro = { "식당 도착 ${minutes}분 전이에요. ${eta}쯤 ${people}명 자리 있는지, 주차는 어디에 하는지 제가 물어볼게요." },
            question = "${eta}쯤 ${people}명 자리 있는지, 주차는 어디에 하면 되는지",
            routeToChosen = false,
            noneFound = "식당에서 자리 확인을 못 했어요. 정차하면 결과를 보여 드릴게요.",
        )
    }
}
