package app.carask.ask

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * 운전 중 안내는 소리로만. Pleos TTS SDK는 아직 '준비 중'이라 안드로이드 기본 TTS를 쓴다.
 * 말한 문장은 화면 기록에도 남긴다(onSpoken).
 */
class Voice(context: Context, private val onSpoken: (String) -> Unit) {
    private var ready = false
    private val pending = mutableListOf<String>()
    private lateinit var tts: TextToSpeech

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                tts.language = Locale.KOREAN
                pending.forEach { speakNow(it) }
            }
            pending.clear()
        }
    }

    fun say(text: String) {
        Log.d("CARASK", "🔊 $text")
        onSpoken(text)
        if (ready) speakNow(text) else pending += text
    }

    private fun speakNow(text: String) {
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, text.hashCode().toString())
    }

    fun shutdown() = tts.shutdown()
}
