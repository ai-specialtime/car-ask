package app.carask

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.carask.ask.AskState
import app.carask.ask.ShopResult
import app.carask.vehicle.CarState

private val Bg = Color(0xFF0E1013)
private val Card = Color(0xFF181B20)
private val Line = Color(0xFF2A2F36)
private val Sub = Color(0xFF9AA4AE)
private val Ok = Color(0xFF4CD37A)
private val Warn = Color(0xFFFFB74D)
private val No = Color(0xFF6B7380)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startForegroundService(Intent(this, WatchService::class.java))
        val app = application as CarAskApp
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val car by app.signals.state.collectAsState()
                val ask by app.flow.state.collectAsState()
                Screen(car, ask, onAskNow = app.flow::askNow, onDinner = app.flow::goDinner)
            }
        }
    }
}

@Composable
private fun Screen(car: CarState, ask: AskState, onAskNow: () -> Unit, onDinner: () -> Unit) {
    Row(Modifier.fillMaxSize().background(Bg).padding(40.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
        Column(Modifier.weight(1.6f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Headline(ask)
            ask.results.forEach { ShopRow(it, chosen = it == ask.chosen) }
            if (ask.phase == AskState.Phase.CALLING && ask.calling != null) CallingRow(ask.calling, ask.step)
            Spacer(Modifier.weight(1f))
            if (ask.phase != AskState.Phase.CALLING) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Action("지금 근처 타이어 가게에 물어보기", Color(0xFF2B5CFF), onAskNow)
                    Action("식당으로 출발 (시연)", Color(0xFF2A2F36), onDinner)
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Panel("차 상태") {
                Info("기어", car.gear ?: "-")
                Info("경고등", car.warnings.joinToString { warnName(it) }.ifEmpty { "없음" }, if (car.warnings.isEmpty()) Color.White else Warn)
                Info("탄 사람", "${car.passengers}명")
            }
            Panel("운전자에게 말한 것") {
                ask.spoken.takeLast(5).forEach { Text("“$it”", fontSize = 17.sp, color = Sub, lineHeight = 24.sp) }
                if (ask.spoken.isEmpty()) Text("주행 중엔 화면 대신 소리로 알려 드려요.", fontSize = 17.sp, color = Sub)
            }
        }
    }
}

@Composable
private fun Headline(ask: AskState) {
    val (title, sub) = when (ask.phase) {
        AskState.Phase.WATCHING -> "차를 지켜보고 있어요" to "경고등이 켜지거나 식당 도착 15분 전이면 대신 물어봐요."
        AskState.Phase.CALLING -> "${ask.trigger} · 대신 물어보는 중" to "AI가 가게에 'AI 비서'라고 먼저 밝히고 물어봐요."
        AskState.Phase.FOUND -> (if (ask.trigger?.startsWith("식당") == true) "${ask.chosen?.name} 확인 완료" else "${ask.chosen?.name.orEmpty().withRo()} 안내 중") to (ask.chosen?.summary ?: "")
        AskState.Phase.NONE_FOUND -> "지금 바로 되는 곳이 없어요" to "다른 시간이나 다른 가게를 골라 주세요."
        AskState.Phase.FAILED -> "대신 물어보기를 못 했어요" to (ask.error ?: "")
    }
    Text(title, fontSize = 34.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
    Text(sub, fontSize = 20.sp, color = Sub)
}

@Composable
private fun ShopRow(r: ShopResult, chosen: Boolean) {
    val color = when {
        r.ok -> Ok
        r.status == "refused" -> No
        else -> Warn
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Card)
            .border(if (chosen) 2.dp else 1.dp, if (chosen) Ok else Line, RoundedCornerShape(18.dp)).padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(14.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(r.name, fontSize = 22.sp, color = Color.White, fontWeight = FontWeight.Medium)
            Text(r.summary, fontSize = 19.sp, color = if (r.ok) Ok else Sub)
        }
        Text("%.1fkm".format(r.distanceM / 1000.0), fontSize = 18.sp, color = Sub)
    }
}

@Composable
private fun Action(label: String, color: Color, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(18.dp)).background(color).clickable(onClick = onClick)
            .padding(horizontal = 28.dp, vertical = 16.dp)
    ) { Text(label, fontSize = 22.sp, color = Color.White) }
}

@Composable
private fun CallingRow(name: String, step: String?) {
    val label = when (step) {
        "connected" -> "연결됨"
        "asking" -> "묻는 중"
        "confirming" -> "답 확인 중"
        else -> "거는 중"
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).border(1.dp, Color(0xFF2B5CFF), RoundedCornerShape(18.dp)).padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("📞", fontSize = 22.sp)
        Spacer(Modifier.width(16.dp))
        Text(name, fontSize = 22.sp, color = Color.White, modifier = Modifier.weight(1f))
        Text(label, fontSize = 19.sp, color = Color(0xFF8FA8FF))
    }
}

@Composable
private fun Panel(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Card).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, fontSize = 18.sp, color = Sub)
        content()
    }
}

@Composable
private fun Info(label: String, value: String, color: Color = Color.White) {
    Row {
        Text(label, fontSize = 20.sp, color = Sub, modifier = Modifier.width(90.dp))
        Text(value, fontSize = 20.sp, color = color)
    }
}

private fun warnName(t: String) = when (t) {
    "TPMS" -> "타이어 공기압"
    "ENGINE_OIL_PRESSURE" -> "엔진오일 압력"
    "ENGINE_TEMPERATURE" -> "엔진 온도"
    else -> t
}

/** 받침에 맞춰 '로/으로'를 붙인다 (ㄹ 받침은 '로'). */
private fun String.withRo(): String {
    val c = lastOrNull() ?: return this
    if (c !in '가'..'힣') return "${this}로"
    val jong = (c - '가') % 28
    return if (jong == 0 || jong == 8) "${this}로" else "${this}으로"
}
