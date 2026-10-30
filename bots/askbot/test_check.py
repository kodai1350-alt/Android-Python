import asyncio
import json
import math
from datetime import datetime

from aiohttp import ClientSession, web

import askbot
from askbot import CHECK_BATCH, JST, MAX_CANDIDATES, Msg, Photo, candidate_line, check_candidates
from test_llm import free_port, start_app

T0 = datetime(2026, 4, 18, 12, tzinfo=JST)


def msg(i, content="", links=""):
    return Msg(i, 1, 100, 100, False, "百鬼", content, links, T0)


def answering(*replies, seen=None):
    """A fake llama-server answering each request with the next {"relevant": [...]} (or raw text)."""
    replies = list(replies)

    async def handler(request):
        if seen is not None:
            seen.append(await request.json())
        reply = replies.pop(0)
        text = reply if isinstance(reply, str) else json.dumps({"relevant": reply})
        resp = web.StreamResponse()
        await resp.prepare(request)
        await resp.write(("data: " + json.dumps({"choices": [{"delta": {"content": text}}]}) + "\n\n").encode())
        await resp.write(b"data: [DONE]\n\n")
        return resp

    return handler


def check(handler, items):
    async def go():
        port = free_port()
        runner = await start_app(port, handler)
        try:
            async with ClientSession() as s:
                return await check_candidates(s, f"http://127.0.0.1:{port}", "Eラーニングの話", items)
        finally:
            await runner.cleanup()

    return asyncio.run(go())


def test_keeps_only_relevant_in_order():
    items = [msg(1, "a"), msg(2, "b"), msg(3, "c")]
    assert check(answering([3, 1]), items) == ([items[0], items[2]], True)


def test_out_of_range_numbers_ignored():
    items = [msg(1, "a"), msg(2, "b")]
    assert check(answering([0, 2, 9]), items) == ([items[1]], True)


def test_batches_of_CHECK_BATCH():
    seen = []
    items = [msg(i, f"候補{i}") for i in range(MAX_CANDIDATES)]
    batches = math.ceil(MAX_CANDIDATES / CHECK_BATCH)
    kept, checked = check(answering(*([[1]] * batches), seen=seen), items)
    assert len(seen) == batches
    assert kept == items[::CHECK_BATCH] and checked
    assert seen[0]["response_format"]["json_schema"]["schema"] == askbot.RELEVANT_SCHEMA
    assert "Eラーニングの話" in seen[0]["messages"][-1]["content"]


def test_llm_failure_returns_unchecked():
    items = [msg(1, "a"), msg(2, "b")]
    assert check(answering("これはJSONではない"), items) == (items, False)


def test_candidate_line_trims_to_80_chars():
    line = candidate_line(3, msg(1, "あ" * 100))
    assert line == "3. 4月18日 百鬼: " + "あ" * 80


def test_candidate_line_uses_links_and_photo_descriptions():
    assert candidate_line(1, msg(1, "", "https://schoo.jp/\nSchoo")) == "1. 4月18日 百鬼: https://schoo.jp/ Schoo"
    photo = Photo(11, 1, 1, 100, 100, False, "kodai", T0, "料理", "ラーメン")
    assert candidate_line(2, photo) == "2. 4月18日 kodai: 料理 ラーメン"
