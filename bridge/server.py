"""차 앱 ↔ AI 통화 엔진(Project_2_AI-Call/engine) 다리.

차 앱이 "이 가게들에 차례로 물어봐"를 보내면, 한 곳씩 AI가 전화로 묻고
'된다'는 답이 나오면 멈춘다. 앱은 진행 상황을 GET 으로 받아 간다.

실행 (P2 엔진의 가상환경 사용):
  ../../Project_2_AI-Call/engine/.venv/bin/python server.py            # 0.0.0.0:8790
에뮬레이터 안 앱에서는 http://10.0.2.2:8790 으로 붙는다.

mode
  sim    가게마다 정해 둔 직원 대사(clerk)로 통화 — 키 없이 반복 시연 (기본, 텍스트 모드 + 각본 LLM)
  sim-llm 같은 대사 + 실제 Gemini (무료 한도 사용)
  local  맥 마이크·스피커 — 사람이 정비소 직원 역할 (시연 영상용)
  live   local 과 같지만 ElevenLabs 없이: 듣기 Whisper(맥), 말하기 맥 기본 목소리(Yuna). 무료
가게마다 "mode" 를 따로 줄 수 있다 (예: 앞 두 곳 sim, 마지막 곳 live). 환경변수 LIVE_LAST=1 이면 마지막 가게를 live 로.
"""
import asyncio
import json
import os
import re
import sys
import uuid
from pathlib import Path

from aiohttp import web

ENGINE = Path(__file__).resolve().parents[2] / "Project_2_AI-Call" / "engine"
sys.path.insert(0, str(ENGINE))

try:  # AI 통화 엔진이 있으면 실제 대화 파이프라인으로, 없으면(공개 저장소) 각본만 재생
    from callbot import scripted  # noqa: E402
    from callbot.llm import default_llm  # noqa: E402
    from callbot.request import CallRequest, load_secrets  # noqa: E402
    from callbot.session import EngineOptions, run_call  # noqa: E402
    from callbot.sim import Clerk, SimAdapter  # noqa: E402
    from callbot.transports import TransportSpec  # noqa: E402
    HAS_ENGINE = True
except ImportError:
    HAS_ENGINE = False

# "안 돼요"류 답이면 다음 가게로 넘어간다 (결과 카드의 한 줄 요약 기준)
NO = re.compile(r"안 ?돼|안 ?된|어렵|불가|꽉|없대|없어|못 |마감|휴무|내일")

JOBS: dict[str, dict] = {}
LOCK = asyncio.Lock()  # 무료 한도 때문에 통화는 한 번에 하나


def positive(card: dict) -> bool:
    return card.get("status") == "answered" and not NO.search(card.get("answer_one_line", ""))


def scripted_card(shop: dict) -> dict:
    """각본 모드: 마지막 직원 대사로 결과 카드를 만든다(실제 LLM 없이 반복 시연용)."""
    lines = shop.get("clerk") or []
    said = next((l for l in reversed(lines) if not l.startswith("(") and "입니다" not in l and l not in ("네, 맞아요.", "네.")), "")
    if re.search(r"AI|에이아이", said) and re.search(r"안 받", said):
        return {"status": "refused", "answer_one_line": "AI 전화는 안 받는대요.", "evidence": [f"직원: {said}"], "confidence": "high"}
    return {"status": "answered", "answer_one_line": shop.get("summary") or said, "evidence": [f"직원: {said}"], "confidence": "high"}


async def replay(job: dict, shop: dict) -> dict:
    """엔진 없이 각본 재생: 단계만 시간차로 흉내 내고 각본 카드를 돌려준다."""
    for step, wait in (("connected", 1.5), ("asking", 2.5), ("confirming", 2.0)):
        if job["cancel"].is_set():
            break
        job["steps"].append(step)
        await asyncio.sleep(wait)
        if step == "asking" and (shop.get("card") or {}).get("status") == "refused":
            break
    return {"card": shop.get("card") or scripted_card(shop)}


async def call_shop(job: dict, shop: dict, body: dict) -> dict:
    mode = shop.get("mode") or body.get("mode", "sim")
    if not HAS_ENGINE:
        return await replay(job, shop)
    if mode == "live":
        from mac_voice import use_mac_voice
        use_mac_voice()
        req = CallRequest(business_name=shop["name"], questions=[body["question"]], user_name=body.get("user_name", "운전자"))
        opts = EngineOptions(llm=default_llm(), stt="whisper", barge_in=False,
                             transport=TransportSpec("local", body.get("in_device"), body.get("out_device")))
        print(f"\n📞 {shop['name']} 직원 역할로 '네, {shop['name']}입니다' 하고 받아 주세요.\n", flush=True)
        return await run_call(req, opts, load_secrets(), on_step=job["steps"].append, cancel=job["cancel"])
    req = CallRequest(business_name=shop["name"], questions=[body["question"]], user_name=body.get("user_name", "운전자"),
                      phone_e164=shop.get("phone"))
    secrets = load_secrets()
    steps = job["steps"]
    if mode == "local":
        opts = EngineOptions(llm=default_llm(), transport=TransportSpec("local", body.get("in_device"), body.get("out_device")))
        return await run_call(req, opts, secrets, on_step=steps.append, cancel=job["cancel"])
    real = mode == "sim-llm"
    script = None if real else {"llm": shop.get("llm", []), "card": shop.get("card") or scripted_card(shop)}
    opts = EngineOptions(llm=default_llm() if real else scripted.NAME, script=script)
    adapter = SimAdapter(Clerk(shop.get("clerk") or ["네, 여보세요."], None), shop.get("dial", "connected"))
    return await run_call(req, opts, secrets, adapter=adapter, on_step=steps.append, cancel=job["cancel"],
                          label=f"car-{shop['name']}")


async def run_job(job: dict, body: dict):
    async with LOCK:
        for i, shop in enumerate(body["shops"]):
            if job["cancel"].is_set():
                break
            job.update(state="calling", current=i, steps=[])
            try:
                rec = await call_shop(job, shop, body)
                card = rec["card"]
            except Exception as e:  # 한 곳이 실패해도 다음 가게로
                card = {"status": "failed", "answer_one_line": "연결이 안 됐어요.", "_error": f"{type(e).__name__}: {e}"}
            ok = positive(card)
            job["results"].append({"shop": shop, "card": card, "ok": ok})
            if ok:
                job["chosen"] = i
                break
        job["state"] = "cancelled" if job["cancel"].is_set() else "done"


async def create(request: web.Request):
    body = await request.json()
    if not body.get("shops") or not body.get("question"):
        return web.json_response({"error": "shops, question 이 필요해요"}, status=400)
    if os.environ.get("LIVE_LAST") == "1":
        body["shops"][-1]["mode"] = "live"
    jid = uuid.uuid4().hex[:8]
    job = {"id": jid, "state": "queued", "current": None, "steps": [], "results": [], "chosen": None,
           "cancel": asyncio.Event()}
    JOBS[jid] = job
    asyncio.create_task(run_job(job, body))
    return web.json_response(public(job))


def public(job: dict) -> dict:
    return {k: v for k, v in job.items() if k != "cancel"}


async def status(request: web.Request):
    job = JOBS.get(request.match_info["jid"])
    return web.json_response(public(job)) if job else web.json_response({"error": "없는 요청"}, status=404)


async def cancel(request: web.Request):
    job = JOBS.get(request.match_info["jid"])
    if job:
        job["cancel"].set()
    return web.json_response({"ok": bool(job)})


def app() -> web.Application:
    a = web.Application()
    a.add_routes([web.post("/ask", create), web.get("/ask/{jid}", status), web.post("/ask/{jid}/cancel", cancel),
                  web.get("/health", lambda r: web.json_response({"ok": True}))])
    return a


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8790
    print(f"bridge on :{port} ({'engine ' + str(ENGINE) if HAS_ENGINE else '엔진 없음 — 각본 재생 모드'})", flush=True)
    web.run_app(app(), host="0.0.0.0", port=port, print=None)
