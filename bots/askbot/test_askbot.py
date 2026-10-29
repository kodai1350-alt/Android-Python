import asyncio

import askbot
from askbot import ReplyWriter, progress_text, split_message


class FakeMessage:
    def __init__(self, content=""):
        self.content = content
        self.edits = []

    async def edit(self, content):
        self.content = content
        self.edits.append(content)


class FakeClock:
    def __init__(self):
        self.now = 0.0

    def __call__(self):
        return self.now


# --- Task 5: splitting and the reply writer ---------------------------------

def test_split_message_prefers_newlines():
    first, second = "あ" * 1990, "い" * 50
    assert split_message(first + "\n" + second) == [first, second]


def test_split_message_without_newlines():
    text = "x" * 4500
    parts = split_message(text)
    assert [len(p) for p in parts] == [2000, 2000, 500]
    assert "".join(parts) == text


def test_progress_text_clamps():
    assert progress_text("wiki", 130) == "📖 Wikipediaを読んでいます… 100%"
    assert progress_text("photo", -5) == "🖼 写真を見ています… 0%"


def test_reply_writer_throttles():
    clock = FakeClock()
    first = FakeMessage("受け付けました")

    async def send_more(text):
        raise AssertionError("should fit in one message")

    async def run():
        w = ReplyWriter(first, send_more, clock=clock)
        for i in range(10):
            clock.now = i * 0.1
            await w.append(str(i))
        assert len(first.edits) == 1
        await w.finish()
        assert first.content == "0123456789"

    asyncio.run(run())


def test_reply_writer_overflows_to_new_message():
    clock = FakeClock()
    first = FakeMessage()
    extra = []

    async def send_more(text):
        m = FakeMessage(text)
        extra.append(m)
        return m

    async def run():
        w = ReplyWriter(first, send_more, clock=clock)
        await w.append("y" * 2500)
        await w.finish("📚 参考")
        assert len(extra) == 1
        assert first.content + extra[0].content.removesuffix("\n\n📚 参考") == "y" * 2500
        assert extra[0].content.endswith("📚 参考")

    asyncio.run(run())


def test_reply_writer_status_until_text():
    clock = FakeClock()
    first = FakeMessage()

    async def run():
        w = ReplyWriter(first, None, clock=clock)
        await w.status(progress_text("wiki", 40))
        assert first.content == "📖 Wikipediaを読んでいます… 40%"
        clock.now = 5
        await w.append("答え")
        assert first.content == "答え"

    asyncio.run(run())


# --- Task 6: one-at-a-time queue --------------------------------------------

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


# --- Task 7: question routing, keywords and dates ---------------------------

from datetime import datetime

from askbot import JST, plan_question

NOW = datetime(2026, 10, 29, 12, 0, tzinfo=JST)


def jst(y, m, d):
    return datetime(y, m, d, tzinfo=JST)


def test_plan_history_with_date():
    p = plan_question("10月20日に話してたゲームの名前なんだっけ？", False, NOW)
    assert p.mode == "history"
    assert p.keywords == ["ゲーム"]
    assert (p.since, p.until) == (jst(2026, 10, 20), jst(2026, 10, 21))


def test_plan_last_week():
    p = plan_question("先週ラーメン屋の話したけど、どこだっけ？", False, NOW)
    assert p.mode == "history"
    assert p.keywords == ["ラーメン"]
    assert (p.since, p.until) == (jst(2026, 10, 19), jst(2026, 10, 26))


def test_plan_wiki():
    p = plan_question("量子コンピュータって何？", False, NOW)
    assert p.mode == "wiki"
    assert p.keywords == ["量子", "コンピュータ"]
    assert (p.since, p.until) == (None, None)


def test_plan_photo():
    assert plan_question("これ何？", True, NOW).mode == "photo"


def test_plan_chat():
    p = plan_question("こんにちは", False, NOW)
    assert p.mode == "chat"
    assert p.keywords == []


def test_future_date_means_last_year():
    p = plan_question("12月3日", False, NOW)
    assert p.since == jst(2025, 12, 3)


def test_yesterday():
    p = plan_question("昨日", False, NOW)
    assert (p.since, p.until) == (jst(2026, 10, 28), jst(2026, 10, 29))


# --- Task 8: message history -------------------------------------------------

from datetime import timezone as _tz

from askbot import HistoryStore, Stored, jump_url

G, C1, C2 = 100, 201, 202


def msg(mid, content, day=20, channel=C1, guild=G):
    return Stored(mid, guild, channel, "taro", content, datetime(2026, 10, day, 3, 0, tzinfo=_tz.utc))


def store_with(tmp_path, *rows):
    s = HistoryStore(str(tmp_path / "h.db"))
    s.register(G, C1)
    s.register(G, C2)
    for r in rows:
        s.upsert(r)
    return s


def test_search_by_keyword(tmp_path):
    s = store_with(tmp_path, msg(1, "ラーメン屋に行った"), msg(2, "今日は雨"))
    assert [r.message_id for r in s.search(G, {C1}, ["ラーメン"], None, None)] == [1]


def test_search_short_keyword(tmp_path):
    s = store_with(tmp_path, msg(1, "名前は太郎"), msg(2, "雨"))
    assert [r.message_id for r in s.search(G, {C1}, ["名前"], None, None)] == [1]


def test_search_filters_channels_and_guild(tmp_path):
    s = store_with(tmp_path, msg(1, "ラーメン"), msg(2, "ラーメン", channel=C2), msg(3, "ラーメン", guild=999))
    assert [r.message_id for r in s.search(G, {C1}, ["ラーメン"], None, None)] == [1]
    assert s.search(G, set(), ["ラーメン"], None, None) == []


def test_search_date_range(tmp_path):
    s = store_with(tmp_path, msg(1, "ラーメン", day=19), msg(2, "ラーメン", day=20), msg(3, "ラーメン", day=21))
    since = datetime(2026, 10, 20, tzinfo=JST)
    until = datetime(2026, 10, 21, tzinfo=JST)
    assert [r.message_id for r in s.search(G, {C1}, ["ラーメン"], since, until)] == [2]


def test_search_no_keywords_returns_latest_in_range(tmp_path):
    s = store_with(tmp_path, msg(1, "a", day=19), msg(2, "b", day=20), msg(3, "c", day=21))
    assert [r.message_id for r in s.search(G, {C1}, [], None, None, limit=2)] == [3, 2]


def test_search_ranks_more_hits_first(tmp_path):
    s = store_with(tmp_path, msg(1, "ラーメン", day=21), msg(2, "ラーメンとギョーザ", day=19))
    assert [r.message_id for r in s.search(G, {C1}, ["ラーメン", "ギョーザ"], None, None)] == [2, 1]


def test_edit_updates_search(tmp_path):
    s = store_with(tmp_path, msg(1, "ラーメン"))
    s.upsert(msg(1, "カレーライス"))
    assert s.search(G, {C1}, ["ラーメン"], None, None) == []
    assert [r.content for r in s.search(G, {C1}, ["カレー"], None, None)] == ["カレーライス"]


def test_delete_and_unregister_remove_rows(tmp_path):
    s = store_with(tmp_path, msg(1, "ラーメン"), msg(2, "ラーメン"), msg(3, "ラーメン", channel=C2))
    s.delete(1)
    assert sorted(r.message_id for r in s.search(G, {C1, C2}, ["ラーメン"], None, None)) == [2, 3]
    s.unregister(G, C2)
    assert not s.is_registered(C2)
    assert s.registered(G) == {C1}
    assert [r.message_id for r in s.search(G, {C1, C2}, ["ラーメン"], None, None)] == [2]


def test_latest(tmp_path):
    s = store_with(tmp_path, msg(1, "a", day=19), msg(2, "b", day=22))
    assert s.latest(C1) == datetime(2026, 10, 22, 3, 0, tzinfo=_tz.utc)
    assert s.latest(C2) is None


def test_jump_url():
    assert jump_url(msg(5, "x")) == "https://discord.com/channels/100/201/5"
