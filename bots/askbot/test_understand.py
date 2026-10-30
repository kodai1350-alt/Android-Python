import asyncio
from datetime import datetime

from aiohttp import ClientSession

from askbot import JST, SEARCH_TOOL, SYSTEM_PROMPT, Query, parse_when, rule_kind, understand
from test_llm import free_port, sse, start_app, tool_delta
import json

NOW = datetime(2026, 10, 30, 12, tzinfo=JST)


def run(question, args, seen=None):
    async def go():
        port = free_port()
        runner = await start_app(port, sse([tool_delta(json.dumps(args, ensure_ascii=False))], seen))
        try:
            async with ClientSession() as s:
                return await understand(s, f"http://127.0.0.1:{port}", question, NOW, api_key="k")
        finally:
            await runner.cleanup()

    return asyncio.run(go())


def test_understand_uses_tool_args():
    q = run("Eラーニングの話で半年前ぐらいに貼ってたURLどれ？",
            {"kind": "link", "words": ["Eラーニング", "オンライン学習"], "when": "半年前ぐらい"})
    since, until = parse_when("半年前ぐらい", NOW)
    assert q == Query("link", ["Eラーニング", "オンライン学習"], since, until, None, "半年前ぐらい")


def test_understand_keeps_a_known_photo_kind_only():
    assert run("猫の写真", {"kind": "photo", "words": ["猫"], "photo_kind": "ペット・動物"}).photo_kind == "ペット・動物"
    assert run("猫の写真", {"kind": "photo", "words": ["猫"], "photo_kind": "ねこ"}).photo_kind is None


def test_understand_fills_words_from_question_when_empty():
    q = run("旅行の日程の話", {"kind": "message", "words": []})
    assert q.words == ["旅行", "日程"]


def test_understand_dates_from_question_when_when_missing():
    q = run("先週の旅行の話", {"kind": "message", "words": ["旅行"]})
    assert (q.since, q.until) == parse_when("先週", NOW)


def test_understand_unknown_kind_falls_back_to_rules():
    assert run("猫の写真見せて", {"kind": "picture", "words": ["猫"]}).kind == "photo"


def test_rule_kind():
    assert rule_kind("半月前のラーメンの写真") == "photo"
    assert rule_kind("英語の勉強サイトのURL") == "link"
    assert rule_kind("先週の旅行の話") == "message"
    assert rule_kind("おはよう") == "other"


def test_request_body_has_tool_and_fixed_prefix():
    seen = []
    run("猫の写真", {"kind": "photo", "words": ["猫"]}, seen)
    _, body = seen[0]
    assert body["messages"][0] == {"role": "system", "content": SYSTEM_PROMPT}
    assert body["messages"][1] == {"role": "user", "content": "猫の写真"}
    assert body["tools"] == [SEARCH_TOOL]
    assert body["tool_choice"] == "required"


def test_understand_ignores_malformed_tool_args():
    q = run("旅行の日程の話", ["not", "a", "dict"])
    assert (q.kind, q.words) == ("message", ["旅行", "日程"])
    q = run("旅行の日程の話", {"kind": "message", "words": "旅行"})
    assert q.words == ["旅行", "日程"]
