import asyncio
import json
from datetime import datetime

import discord
from aiohttp import ClientSession, web
from types import SimpleNamespace

from askbot import (CLASS_SCHEMA, JST, AskQueue, LlmError, LlmUnreachable, Msg, Photo, PhotoClassifier,
                    RecordStore, classify)
from test_llm import free_port, start_app

G, A = 1, 100
T0 = datetime(2026, 10, 14, 12, tzinfo=JST)


# --- AskQueue: idle work ------------------------------------------------------

def test_idle_runs_when_queue_empty():
    async def run():
        calls = []

        async def idle():
            calls.append(1)
            await asyncio.sleep(0)

        q = AskQueue(idle=idle)
        task = asyncio.create_task(q.run_forever())
        await asyncio.sleep(0.05)
        task.cancel()
        assert len(calls) >= 2

    asyncio.run(run())


def test_submit_cancels_running_idle():
    async def run():
        idle_started, idle_cancelled, job_ran = asyncio.Event(), asyncio.Event(), asyncio.Event()

        async def idle():
            idle_started.set()
            try:
                await asyncio.Event().wait()
            except asyncio.CancelledError:
                idle_cancelled.set()
                raise

        async def job():
            assert idle_cancelled.is_set()
            job_ran.set()

        async def on_position(n):
            pass

        q = AskQueue(idle=idle)
        task = asyncio.create_task(q.run_forever())
        await asyncio.wait_for(idle_started.wait(), 1)
        q.submit(7, job, on_position)
        await asyncio.wait_for(job_ran.wait(), 1)
        task.cancel()

    asyncio.run(run())


def test_failing_idle_backs_off_but_questions_still_run():
    async def run():
        calls, job_ran = [], asyncio.Event()

        async def broken():
            calls.append(1)
            raise RuntimeError("unexpected")

        async def job():
            job_ran.set()

        async def on_position(n):
            pass

        q = AskQueue(idle=broken, idle_backoff=60)
        task = asyncio.create_task(q.run_forever())
        await asyncio.sleep(0.2)
        assert len(calls) == 1  # no tight loop
        q.submit(7, job, on_position)
        await asyncio.wait_for(job_ran.wait(), 1)
        task.cancel()

    asyncio.run(run())


# --- PhotoClassifier ----------------------------------------------------------

def setup(tmp_path):
    s = RecordStore(str(tmp_path / "tsumugi.db"))
    s.register(G, A)
    s.upsert(Msg(1, G, A, A, False, "百鬼", "", "", T0), [Photo(11, 1, G, A, A, False, "百鬼", T0)])
    return s


class Sleeps:
    def __init__(self):
        self.calls = []

    async def __call__(self, seconds):
        self.calls.append(seconds)


def classifier(store, fetch=None, cls=None, sleep=None):
    async def fetch_image(photo):
        return b"jpeg", "image/jpeg"

    async def classify_ok(data, content_type):
        return "料理", "ラーメン"

    return PhotoClassifier(store, fetch or fetch_image, cls or classify_ok, sleep=sleep or Sleeps())


def test_classifies_and_saves(tmp_path):
    s = setup(tmp_path)
    asyncio.run(classifier(s).run_once())
    assert s.next_unclassified() is None
    assert s.search_photos(A, A, ["ラーメン"], None, None, None, 5)[0].kind == "料理"


def test_nothing_to_do_sleeps_60(tmp_path):
    s = RecordStore(str(tmp_path / "tsumugi.db"))
    sleeps = Sleeps()
    asyncio.run(classifier(s, sleep=sleeps).run_once())
    assert sleeps.calls == [60]


def test_cancelled_photo_stays_unclassified(tmp_path):
    s = setup(tmp_path)

    async def slow(data, content_type):
        await asyncio.Event().wait()

    async def run():
        task = asyncio.create_task(classifier(s, cls=slow).run_once())
        await asyncio.sleep(0.05)
        task.cancel()
        try:
            await task
        except asyncio.CancelledError:
            pass

    asyncio.run(run())
    assert s.next_unclassified().attachment_id == 11


def test_llm_error_keeps_photo_pending_and_sleeps_60(tmp_path):
    s = setup(tmp_path)
    sleeps = Sleeps()

    async def failing(data, content_type):
        raise LlmUnreachable("AIモデルにつながりません")

    asyncio.run(classifier(s, cls=failing, sleep=sleeps).run_once())
    assert s.next_unclassified().attachment_id == 11
    assert sleeps.calls == [60]


def test_llm_down_never_gives_up_on_a_photo(tmp_path):
    s = setup(tmp_path)

    async def down(data, content_type):
        raise LlmUnreachable("AIモデルにつながりません")

    c = classifier(s, cls=down)
    for _ in range(5):
        asyncio.run(c.run_once())
    assert s.next_unclassified().attachment_id == 11


def test_model_rejecting_photo_gives_up_after_3(tmp_path):
    s = setup(tmp_path)

    async def rejected(data, content_type):
        raise LlmError("AIモデルがエラーを返しました（400）")

    c = classifier(s, cls=rejected)
    for _ in range(2):
        asyncio.run(c.run_once())
    assert s.next_unclassified().attachment_id == 11
    asyncio.run(c.run_once())
    assert s.next_unclassified() is None


def test_unsupported_format_marked_without_model(tmp_path):
    s = setup(tmp_path)
    used = []

    async def webp(photo):
        return b"riff", "image/webp"

    async def cls(data, content_type):
        used.append(1)
        return "料理", "x"

    asyncio.run(classifier(s, fetch=webp, cls=cls).run_once())
    assert used == [] and s.next_unclassified() is None


def test_discord_trouble_keeps_photo_pending(tmp_path):
    s = setup(tmp_path)
    sleeps = Sleeps()

    async def flaky(photo):
        raise discord.HTTPException(SimpleNamespace(status=500, reason="err"), "server error")

    asyncio.run(classifier(s, fetch=flaky, sleep=sleeps).run_once())
    assert s.next_unclassified().attachment_id == 11
    assert sleeps.calls == [60]


def test_unreadable_photo_is_marked_not_retried(tmp_path):
    s = setup(tmp_path)

    async def forbidden(photo):
        raise discord.Forbidden(SimpleNamespace(status=403, reason="Forbidden"), "no access")

    asyncio.run(classifier(s, fetch=forbidden).run_once())
    assert s.next_unclassified() is None


def test_deleted_photo_is_removed(tmp_path):
    s = setup(tmp_path)

    async def gone(photo):
        return None

    asyncio.run(classifier(s, fetch=gone).run_once())
    assert s.next_unclassified() is None and s.stats(A).photos == 0


def test_too_big_photo_marked_without_model(tmp_path):
    s = setup(tmp_path)
    used = []

    async def big(photo):
        return b"x" * (8 * 1024 * 1024 + 1), "image/jpeg"

    async def cls(data, content_type):
        used.append(1)
        return "料理", "x"

    asyncio.run(classifier(s, fetch=big, cls=cls).run_once())
    assert used == []
    st = s.stats(A)
    assert st.classified == 1


def test_too_big_reported_without_downloading(tmp_path):
    s = setup(tmp_path)

    async def big(photo):
        return None, "image/jpeg"  # the size alone told fetch_image it is too big

    asyncio.run(classifier(s, fetch=big).run_once())
    assert s.stats(A).classified == 1


def test_classify_sends_image_and_schema():
    seen = []

    async def handler(request):
        seen.append(await request.json())
        resp = web.StreamResponse()
        await resp.prepare(request)
        text = json.dumps({"kind": "料理", "description": "ラーメン。チャーシュー"}, ensure_ascii=False)
        await resp.write(("data: " + json.dumps({"choices": [{"delta": {"content": text}}]}) + "\n\n").encode())
        await resp.write(b"data: [DONE]\n\n")
        return resp

    async def run():
        port = free_port()
        runner = await start_app(port, handler)
        try:
            async with ClientSession() as s:
                return await classify(s, f"http://127.0.0.1:{port}", b"abc", "image/png")
        finally:
            await runner.cleanup()

    assert asyncio.run(run()) == ("料理", "ラーメン。チャーシュー")
    content = seen[0]["messages"][-1]["content"]
    assert content[0] == {"type": "image_url", "image_url": {"url": "data:image/png;base64,YWJj"}}
    assert seen[0]["response_format"]["json_schema"]["schema"] == CLASS_SCHEMA


def test_classify_unknown_kind_becomes_other():
    async def handler(request):
        resp = web.StreamResponse()
        await resp.prepare(request)
        text = json.dumps({"kind": "ごはん", "description": "x" * 200})
        await resp.write(("data: " + json.dumps({"choices": [{"delta": {"content": text}}]}) + "\n\n").encode())
        await resp.write(b"data: [DONE]\n\n")
        return resp

    async def run():
        port = free_port()
        runner = await start_app(port, handler)
        try:
            async with ClientSession() as s:
                return await classify(s, f"http://127.0.0.1:{port}", b"abc", "image/png")
        finally:
            await runner.cleanup()

    kind, description = asyncio.run(run())
    assert kind == "その他" and len(description) == 80
