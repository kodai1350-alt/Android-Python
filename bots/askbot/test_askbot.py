import asyncio
import json
from types import SimpleNamespace

import askbot
from askbot import RecordStore, image_part, parse_sse, progress_percent


# --- One-at-a-time queue ------------------------------------------------------

def _queue_harness():
    q = askbot.AskQueue()
    log = []
    positions = {}
    gates = {}

    def make(name):
        gates[name] = asyncio.Event()

        async def job():
            log.append(("start", name))
            await gates[name].wait()
            log.append(("end", name))

        async def on_position(n):
            positions.setdefault(name, []).append(n)

        return job, on_position

    return q, log, positions, gates, make


def test_jobs_run_one_at_a_time_in_order():
    async def run():
        q, log, _, gates, make = _queue_harness()
        worker = asyncio.create_task(q.run_forever())
        for user, name in [(1, "A"), (2, "B"), (3, "C")]:
            q.submit(user, *make(name))
        for name in "ABC":
            await asyncio.sleep(0.01)
            gates[name].set()
        await asyncio.sleep(0.05)
        worker.cancel()
        assert log == [("start", "A"), ("end", "A"), ("start", "B"), ("end", "B"), ("start", "C"), ("end", "C")]

    asyncio.run(run())


def test_positions_update():
    async def run():
        q, _, positions, gates, make = _queue_harness()
        worker = asyncio.create_task(q.run_forever())
        assert q.submit(1, *make("A")) == 0
        await asyncio.sleep(0.01)
        assert q.submit(2, *make("B")) == 1
        assert q.submit(3, *make("C")) == 2
        gates["A"].set()
        await asyncio.sleep(0.02)
        assert positions["C"] == [1]
        worker.cancel()

    asyncio.run(run())


def test_same_user_rejected_while_waiting():
    async def run():
        q, _, _, gates, make = _queue_harness()
        worker = asyncio.create_task(q.run_forever())
        assert q.submit(1, *make("A")) == 0
        await asyncio.sleep(0.01)
        assert q.submit(2, *make("B")) == 1
        assert q.submit(2, *make("B2")) is None
        assert q.busy_users() == {1, 2}
        gates["A"].set()
        gates["B"].set()
        await asyncio.sleep(0.02)
        assert q.submit(2, *make("B3")) == 0
        worker.cancel()

    asyncio.run(run())


def test_failed_job_does_not_stop_queue():
    async def run():
        q = askbot.AskQueue()
        done = []

        async def boom():
            raise RuntimeError("x")

        async def ok():
            done.append(True)

        async def pos(n):
            pass

        worker = asyncio.create_task(q.run_forever())
        q.submit(1, boom, pos)
        q.submit(2, ok, pos)
        await asyncio.sleep(0.05)
        worker.cancel()
        assert done == [True]

    asyncio.run(run())


# --- llama-server helpers ---------------------------------------------------------

def test_parse_sse():
    line = "data: " + json.dumps({"choices": [{"delta": {"content": "あ"}}]})
    assert parse_sse(line) == {"choices": [{"delta": {"content": "あ"}}]}
    assert parse_sse("data: [DONE]") is None
    assert parse_sse("") is None


def test_progress_percent():
    assert progress_percent({"total": 1000, "cache": 200, "processed": 600}) == 50


def test_image_part():
    assert image_part(b"abc", "image/png") == {"type": "image_url", "image_url": {"url": "data:image/png;base64,YWJj"}}


# --- The bot ---------------------------------------------------------------------

def make_bot(tmp_path, monkeypatch):
    monkeypatch.setattr(askbot, "RECORDS_PATH", str(tmp_path / "tsumugi.db"))
    monkeypatch.setattr(askbot, "DB_PATH", str(tmp_path / "old.db"))
    return askbot.AskBot()


def test_commands_registered(tmp_path, monkeypatch):
    async def run():
        bot = make_bot(tmp_path, monkeypatch)
        bot._add_commands()
        names = sorted(c.name for c in bot.tree.get_commands())
        await bot.close()
        return names

    assert asyncio.run(run()) == ["記録する", "記録の様子", "記録やめる"]


def test_bot_never_pings(tmp_path, monkeypatch):
    async def run():
        bot = make_bot(tmp_path, monkeypatch)
        mentions = bot.allowed_mentions
        await bot.close()
        return mentions

    m = asyncio.run(run())
    assert (m.everyone, m.users, m.roles, m.replied_user) == (False, False, False, False)


def test_record_store_creates_missing_folder(tmp_path):
    # The app sets HOME to a folder it does not create; the database must still open.
    s = RecordStore(str(tmp_path / "home" / "tsumugi.db"))
    s.register(1, 100)
    assert s.registered(1) == {100}
