"""맥 기본 한국어 목소리(say -v Yuna)를 pipecat TTS로 쓴다. 키·한도 없이 실시간 통화 시연용.

P2 엔진 코드는 고치지 않고, bridge 가 통화 직전에 엔진의 TTS 자리만 이걸로 바꿔 끼운다(use_mac_voice).
"""
import asyncio
import os
import tempfile
from collections.abc import AsyncGenerator

from pipecat.frames.frames import ErrorFrame, Frame, TTSAudioRawFrame
from pipecat.processors.frame_processor import FrameProcessor
from pipecat.services.tts_service import TTSService

RATE = 24000
VOICE = os.environ.get("MAC_VOICE", "Yuna")
SPEED = os.environ.get("MAC_VOICE_RATE", "190")  # 분당 단어 수, 기본 175보다 조금 빠르게(전화 말투)


class MacSayTTSService(TTSService):
    def __init__(self, **kwargs):
        super().__init__(push_start_frame=True, push_stop_frames=True, sample_rate=RATE, **kwargs)

    def can_generate_metrics(self) -> bool:
        return True

    async def run_tts(self, text: str, context_id: str) -> AsyncGenerator[Frame, None]:
        await self.start_tts_usage_metrics(text)
        with tempfile.NamedTemporaryFile(suffix=".wav") as f:
            proc = await asyncio.create_subprocess_exec(
                "say", "-v", VOICE, "-r", SPEED, "-o", f.name, "--file-format=WAVE", f"--data-format=LEI16@{RATE}", text,
                stdout=asyncio.subprocess.DEVNULL, stderr=asyncio.subprocess.PIPE)
            _, err = await proc.communicate()
            if proc.returncode != 0:
                yield ErrorFrame(error=f"say 실패: {err.decode(errors='ignore')}")
                return
            data = open(f.name, "rb").read()
        pcm = data[data.find(b"data") + 8:]  # WAV 머리 뒤 PCM
        await self.stop_ttfb_metrics()
        step = RATE * 2 // 10  # 0.1초씩
        for i in range(0, len(pcm), step):
            yield TTSAudioRawFrame(audio=pcm[i:i + step], sample_rate=RATE, num_channels=1, context_id=context_id)


class PassThrough(FrameProcessor):
    """엔진의 고정 문장 캐시(ElevenLabs 목소리)를 건너뛴다 — 목소리가 섞이지 않게."""

    def __init__(self, *args, **kwargs):
        super().__init__()

    async def process_frame(self, frame, direction):
        await super().process_frame(frame, direction)
        await self.push_frame(frame, direction)


def use_mac_voice():
    """엔진(callbot.session)의 TTS 만들기를 맥 목소리로 바꾼다. 같은 프로세스 안에서만 영향."""
    from callbot import session
    session.make_tts = lambda opts, secrets: MacSayTTSService()
    session.CachedSpeech = PassThrough
