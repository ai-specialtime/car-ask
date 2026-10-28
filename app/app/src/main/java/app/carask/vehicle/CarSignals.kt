package app.carask.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WheelArea
import ai.pleos.playground.vehicle.api.model.telltale.Telltales
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.WheelValue
import ai.pleos.playground.vehicle.constant.control.OilLevelState
import ai.pleos.playground.vehicle.constant.telltale.TelltaleState
import ai.pleos.playground.vehicle.listener.OilLevelStateListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import ai.pleos.playground.vehicle.listener.TelltaleInfoListener
import ai.pleos.playground.vehicle.listener.TirePressureListener
import android.car.Car
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** 차에서 읽어 온 지금 상태. 못 읽은 값은 null, 실패 이유는 errors에. */
data class CarState(
    val tirePressureKpa: Map<String, Float?> = emptyMap(),
    val lowTireThresholdKpa: Float? = null,
    val warnings: List<String> = emptyList(),
    val engineOil: String? = null,
    val seatsOccupied: Map<String, Boolean?> = emptyMap(),
    val gear: String? = null,
    val errors: Map<String, String> = emptyMap(),
) {
    val passengers: Int get() = seatsOccupied.values.count { it == true }
    val parked: Boolean get() = gear == "P"
    /** 공기압이 경고 기준보다 낮은 바퀴 (기준을 못 읽으면 180kPa로 본다) */
    val lowTires: List<String>
        get() = tirePressureKpa.filter { (_, v) -> v != null && v < (lowTireThresholdKpa ?: 180f) }.keys.toList()
}

/**
 * Pleos Vehicle SDK + 안드로이드 차량 속성(기어)을 묶어 CarState 하나로 내보낸다.
 * 값이 바뀌면 콜백으로 갱신된다(Virtual Console에서 바꾼 상태가 바로 반영).
 */
class CarSignals(context: Context) {
    private val app = context.applicationContext
    private val vehicle = Vehicle(app)
    private var car: Car? = null
    private val _state = MutableStateFlow(CarState())
    val state: StateFlow<CarState> = _state

    private val wheels = mapOf(
        "앞 왼쪽" to WheelValue.LEFT_FRONT, "앞 오른쪽" to WheelValue.RIGHT_FRONT,
        "뒤 왼쪽" to WheelValue.LEFT_REAR, "뒤 오른쪽" to WheelValue.RIGHT_REAR,
    )
    private val seats = mapOf(
        "운전석" to SeatValue.ROW_1_LEFT, "조수석" to SeatValue.ROW_1_RIGHT,
        "뒤 왼쪽" to SeatValue.ROW_2_LEFT, "뒤 오른쪽" to SeatValue.ROW_2_RIGHT,
    )
    private val listeners = mutableListOf<() -> Unit>()
    private val handler = Handler(Looper.getMainLooper())
    private var propertyManager: CarPropertyManager? = null

    /** 변화 콜백이 안 오는 환경(에뮬레이터 부하 등)을 대비해 2초마다 다시 읽는다. */
    private val poll = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, POLL_MS)
        }
    }

    private fun fail(key: String, e: Exception) {
        Log.w(TAG, "$key 실패: ${e.javaClass.simpleName} ${e.message}")
        _state.update { it.copy(errors = it.errors + (key to (e.message ?: e.javaClass.simpleName))) }
    }

    fun start() {
        vehicle.initialize()
        readTires()
        readWarnings()
        readEngineOil()
        readSeats()
        readGear()
        handler.postDelayed(poll, POLL_MS)
    }

    private fun refresh() {
        vehicle.getSafety().getTelltalesInfo({ t ->
            _state.update { if (it.warnings == warningsOf(t)) it else { Log.d(TAG, "poll telltales=${warningsOf(t)}"); it.copy(warnings = warningsOf(t)) } }
        }, {})
        val seat = vehicle.getSeat()
        seats.forEach { (name, s) ->
            seat.getCurrentSeatOccupancy(SeatArea(s), { v ->
                _state.update { if (it.seatsOccupied[name] == v) it else it.copy(seatsOccupied = it.seatsOccupied + (name to v)) }
            }, {})
        }
        propertyManager?.let { pm ->
            runCatching { gearName(pm.getIntProperty(VehiclePropertyIds.GEAR_SELECTION, 0)) }
                .onSuccess { g -> _state.update { if (it.gear == g) it else { Log.d(TAG, "poll gear=$g"); it.copy(gear = g) } } }
        }
    }

    private fun readTires() {
        val tire = vehicle.getTire()
        tire.getLowTirePressure(WheelArea(WheelValue.LEFT_FRONT), { v ->
            Log.d(TAG, "lowTireThreshold=$v")
            _state.update { it.copy(lowTireThresholdKpa = v) }
        }, { fail("타이어 기준", it) })
        wheels.forEach { (name, w) ->
            val area = WheelArea(w)
            val set = { v: Float? ->
                Log.d(TAG, "tire $name=$v")
                _state.update { it.copy(tirePressureKpa = it.tirePressureKpa + (name to v)) }
            }
            tire.getTirePressure(area, { set(it) }, { fail("타이어 $name", it) })
            val l = object : TirePressureListener {
                override fun onTirePressureUpdated(pressure: Float?) = set(pressure)
                override fun onFailed(e: Exception) = fail("타이어 $name", e)
            }
            runCatching { tire.registerTirePressure(area, l) }.onFailure { fail("타이어 $name", it as Exception) }
            listeners += { tire.unregisterTirePressure(area, l) }
        }
    }

    private fun warningsOf(t: Telltales?): List<String> =
        t?.telltales.orEmpty().filter { it.state != TelltaleState.OFF }.map { it.type.name }

    private fun readWarnings() {
        val safety = vehicle.getSafety()
        safety.getTelltalesInfo({ t ->
            Log.d(TAG, "telltales=${warningsOf(t)}")
            _state.update { it.copy(warnings = warningsOf(t)) }
        }, { fail("경고등", it) })
        val l = object : TelltaleInfoListener {
            override fun onTelltaleInfoUpdated(telltales: Telltales?) {
                Log.d(TAG, "telltales updated=${warningsOf(telltales)}")
                _state.update { it.copy(warnings = warningsOf(telltales)) }
            }
            override fun onFailed(e: Exception) = fail("경고등", e)
        }
        runCatching { safety.registerTelltales(l) }.onFailure { fail("경고등", it as Exception) }
        listeners += { safety.unregisterTelltales(l) }
    }

    private fun readEngineOil() {
        val safety = vehicle.getSafety()
        val set = { v: OilLevelState? -> _state.update { it.copy(engineOil = v?.name) } }
        safety.getEngineOilLevelState({ set(it) }, { fail("엔진오일", it) })
        val l = object : OilLevelStateListener {
            override fun onOilLevelUpdated(level: OilLevelState?) = set(level)
            override fun onFailed(e: Exception) = fail("엔진오일", e)
        }
        runCatching { safety.registerEngineOilLevelState(l) }.onFailure { fail("엔진오일", it as Exception) }
        listeners += { safety.unregisterEngineOilLevelState(l) }
    }

    private fun readSeats() {
        val seat = vehicle.getSeat()
        seats.forEach { (name, s) ->
            val area = SeatArea(s)
            val set = { v: Boolean? ->
                Log.d(TAG, "seat $name=$v")
                _state.update { it.copy(seatsOccupied = it.seatsOccupied + (name to v)) }
            }
            seat.getCurrentSeatOccupancy(area, { set(it) }, { fail("좌석 $name", it) })
            val l = object : SeatOccupancyListener {
                override fun onSeatOccupancyUpdated(occupancy: Boolean?) = set(occupancy)
                override fun onFailed(e: Exception) = fail("좌석 $name", e)
            }
            runCatching { seat.registerSeatOccupancy(area, l) }.onFailure { fail("좌석 $name", it as Exception) }
            listeners += { seat.unregisterSeatOccupancy(area, l) }
        }
    }

    /** 기어는 Pleos Gear API가 사라질 예정이라 안드로이드 표준 GEAR_SELECTION 속성으로 읽는다. */
    private fun readGear() {
        runCatching {
            val c = Car.createCar(app)
            car = c
            val pm = c.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
            propertyManager = pm
            val cb = object : CarPropertyManager.CarPropertyEventCallback {
                override fun onChangeEvent(value: CarPropertyValue<*>) {
                    val g = gearName(value.value as? Int)
                    Log.d(TAG, "gear=$g")
                    _state.update { it.copy(gear = g) }
                }
                override fun onErrorEvent(propId: Int, zone: Int) = fail("기어", Exception("error $propId"))
            }
            pm.registerCallback(cb, VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE)
            listeners += { pm.unregisterCallback(cb) }
        }.onFailure { fail("기어", it as? Exception ?: Exception(it)) }
    }

    private fun gearName(v: Int?): String? = when (v) {
        VehicleGear.GEAR_PARK -> "P"
        VehicleGear.GEAR_REVERSE -> "R"
        VehicleGear.GEAR_NEUTRAL -> "N"
        VehicleGear.GEAR_DRIVE -> "D"
        null -> null
        else -> "기타($v)"
    }

    fun stop() {
        handler.removeCallbacks(poll)
        listeners.forEach { runCatching { it() } }
        listeners.clear()
        car?.disconnect()
        vehicle.release()
    }

    companion object {
        const val TAG = "CARASK"
        private const val POLL_MS = 2000L
    }
}
