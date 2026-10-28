"""live 모드(Whisper + 실제 LLM + 맥 목소리)를 사람 없이 시험한다.
가상 직원 목소리도 맥 목소리(Reed)로 만들어 Whisper 가 실제로 알아듣는지까지 본다. 무료(Gemini 무료 한도만 사용).
  ../../Project_2_AI-Call/engine/.venv/bin/python check_live.py
"""
import asyncio
import json
import subprocess
import sys
import tempfile
from pathlib import Path

ENGINE = Path(__file__).resolve().parents[2] / "Project_2_AI-Call" / "engine"
sys.path.insert(0, str(ENGINE))

from callbot import sim  # noqa: E402
from callbot.llm import default_llm  # noqa: E402
from callbot.request import CallRequest, load_secrets  # noqa: E402
from callbot.session import EngineOptions, run_call  # noqa: E402

from mac_voice import use_mac_voice  # noqa: E402


def say_pcm(text: str, _key=None) -> bytes:
    """가상 직원 목소리: 맥 Reed(한국어), 16kHz mono 16bit PCM (엔진의 clerk_pcm 과 같은 모양)."""
    with tempfile.NamedTemporaryFile(suffix=".wav") as f:
        subprocess.run(["say", "-v", "Reed (한국어(한국))", "-o", f.name, "--file-format=WAVE", "--data-format=LEI16@16000", text], check=True)
        data = open(f.name, "rb").read()
    return data[data.find(b"data") + 8:]


async def main():
    use_mac_voice()
    sim.clerk_pcm = say_pcm
    shop = json.loads((Path(__file__).with_name("demo_tire.json")).read_text(encoding="utf-8"))["shops"][2]
    req = CallRequest(business_name=shop["name"], user_name="운전자",
                      questions=["지금 앞 왼쪽 타이어 공기압 경고가 떴는데, 20분 안에 도착하면 바로 봐 줄 수 있는지, 얼마나 걸리는지"])
    opts = EngineOptions(llm=default_llm(), stt="whisper", barge_in=False)
    clerk = sim.Clerk(shop["clerk"], "unused")       # 키 자리에 아무 값 → 소리 모드
    steps: list[str] = []
    rec = await run_call(req, opts, load_secrets(), adapter=sim.SimAdapter(clerk, "connected"), on_step=steps.append,
                         cancel=asyncio.Event(), label="car-live-check")
    print("steps", steps)
    print("transcript")
    for t in rec.get("transcript", []):
        print("  ", t)
    print(json.dumps(rec["card"], ensure_ascii=False, indent=2))
    print("latency", rec["latency"])


asyncio.run(main())
