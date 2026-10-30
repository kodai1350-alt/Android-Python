import asyncio
from datetime import datetime, timedelta
from types import SimpleNamespace

import discord

import askbot
from askbot import JST, Msg, Photo, Query, RecordStore, SearchResult, run_search, verify_items

G, A, T = 1, 100, 300
T0 = datetime(2026, 10, 1, 12, tzinfo=JST)


def msg(i, content="", *, links="", place=A, private=False):
    return Msg(i, G, A, place, private, "百鬼", content, links, T0 + timedelta(days=i))


def store(tmp_path):
    s = RecordStore(str(tmp_path / "tsumugi.db"))
    s.register(G, A)
    return s


async def same(items):
    return items, {}


async def all_relevant(items):
    return items, True


def search(s, query, place=A, verify=same, check=all_relevant):
    return asyncio.run(run_search(query, s, A, place, verify, check))


def test_link_search_uses_links_only(tmp_path):
    s = store(tmp_path)
    s.upsert(msg(1, "Eラーニングの課題"), [])
    s.upsert(msg(2, "", links="https://schoo.jp/\nEラーニング"), [])
    result = search(s, Query("link", ["Eラーニング"], None, None))
    assert [m.message_id for m in result.items] == [2] and result.checked


def test_deleted_items_never_shown(tmp_path):
    s = store(tmp_path)
    s.upsert(msg(1, "旅行"), [])
    s.upsert(msg(2, "旅行の日程"), [])

    async def drop_first(items):
        return [i for i in items if i.message_id != 1], {}

    result = search(s, Query("message", ["旅行"], None, None), verify=drop_first)
    assert [m.message_id for m in result.items] == [2]


def test_private_thread_not_found_from_parent(tmp_path):
    s = store(tmp_path)
    s.upsert(msg(1, "ひみつの旅行", place=T, private=True), [])
    assert search(s, Query("message", ["旅行"], None, None), place=A).items == []
    assert [m.message_id for m in search(s, Query("message", ["旅行"], None, None), place=T).items] == [1]


def test_check_failure_marks_unchecked(tmp_path):
    s = store(tmp_path)
    s.upsert(msg(1, "旅行"), [])

    async def failed(items):
        return items, False

    assert search(s, Query("message", ["旅行"], None, None), check=failed).checked is False


def test_nothing_found_skips_the_check(tmp_path):
    s = store(tmp_path)
    asked = []

    async def check(items):
        asked.append(items)
        return items, True

    assert search(s, Query("message", ["旅行"], None, None), check=check).items == []
    assert asked == []


def test_photo_search_passes_thumbs(tmp_path):
    s = store(tmp_path)
    s.upsert(msg(1), [Photo(11, 1, G, A, A, False, "百鬼", T0)])
    s.save_class(11, "料理", "ラーメン")

    async def fresh(items):
        return items, {11: "https://cdn.example/11.png"}

    result = search(s, Query("photo", ["ラーメン"], None, None, "料理"), verify=fresh)
    assert [p.attachment_id for p in result.items] == [11]
    assert result.thumbs == {11: "https://cdn.example/11.png"}


# --- verify_items ---------------------------------------------------------------

def discord_message(i, content, attachments=()):
    return SimpleNamespace(
        id=i, guild=SimpleNamespace(id=G), channel=SimpleNamespace(id=A), content=content, clean_content=content,
        author=SimpleNamespace(bot=False, display_name="百鬼"), webhook_id=None, embeds=[],
        attachments=list(attachments), created_at=T0)


def test_verify_items_drops_deleted_updates_edited_and_collects_thumbs(tmp_path):
    s = store(tmp_path)
    s.upsert(msg(1, "旅行"), [])
    s.upsert(msg(2, "旅行の日程"), [Photo(21, 2, G, A, A, False, "百鬼", T0)])
    s.save_class(21, "風景", "海")
    photo = s.search_photos(A, A, ["海"], None, None, None, 5)[0]
    image = SimpleNamespace(id=21, content_type="image/png", url="https://cdn.example/fresh.png")

    async def fetch(item):
        return None if item.message_id == 1 else discord_message(2, "旅行の日程（変更）", [image])

    items = [msg(1, "旅行"), msg(2, "旅行の日程"), photo]
    kept, thumbs = asyncio.run(verify_items(items, fetch, s))
    assert [(type(k).__name__, k.message_id) for k in kept] == [("Msg", 2), ("Photo", 2)]
    assert kept[0].content == "旅行の日程（変更）"
    assert thumbs == {21: "https://cdn.example/fresh.png"}
    assert s.stats(A).messages == 1


def test_verify_items_keeps_items_when_discord_fails(tmp_path):
    s = store(tmp_path)
    s.upsert(msg(1, "旅行"), [])

    async def fetch(item):
        raise discord.HTTPException(SimpleNamespace(status=500, reason="err"), "server error")

    kept, _ = asyncio.run(verify_items([msg(1, "旅行")], fetch, s))
    assert [k.message_id for k in kept] == [1]


# --- The bot: from a mention to a card ------------------------------------------

class Sent:
    def __init__(self, view=None):
        self.views = [view]

    async def edit(self, *, view=None, **kwargs):
        self.views.append(view)

    def texts(self):
        return [c.content for c in self.views[-1].walk_children() if isinstance(c, discord.ui.TextDisplay)]


def readable(**kw):
    """A fake channel the asker may read (history included unless history=False)."""
    history = kw.pop("history", True)
    perms = SimpleNamespace(view_channel=True, read_message_history=history)
    return SimpleNamespace(permissions_for=lambda member: perms, **kw)


def mention(text, channel):
    sent = []

    async def reply(*, view=None, **kwargs):
        sent.append(Sent(view))
        return sent[-1]

    m = SimpleNamespace(content=f"<@999> {text}", channel=channel, guild=SimpleNamespace(id=G),
                        author=SimpleNamespace(id=7, bot=False), reply=reply)
    return m, sent


def make_bot(tmp_path, monkeypatch):
    monkeypatch.setattr(askbot, "RECORDS_PATH", str(tmp_path / "tsumugi.db"))
    monkeypatch.setattr(askbot, "DB_PATH", str(tmp_path / "old.db"))
    monkeypatch.setenv("LLM_URL", "http://127.0.0.1:1")
    bot = askbot.AskBot()
    bot._connection.user = SimpleNamespace(id=999)
    return bot


def ask(bot, message, sent):
    async def run():
        worker = asyncio.create_task(bot.queue.run_forever())
        await bot.handle_question(message)
        for _ in range(100):
            await asyncio.sleep(0.01)
            if sent and not any("探しています" in t for t in sent[-1].texts()):
                break
        worker.cancel()
        await bot.close()

    asyncio.run(run())
    return sent[-1].texts()


def test_unregistered_channel_says_how_to_start(tmp_path, monkeypatch):
    bot = make_bot(tmp_path, monkeypatch)
    message, sent = mention("旅行の話", readable(id=A, name="雑談"))
    assert ask(bot, message, sent) == ["このチャンネルは記録していません。/記録する で始められます"]


def test_other_kind_shows_help(tmp_path, monkeypatch):
    bot = make_bot(tmp_path, monkeypatch)
    bot.records.register(G, A)

    async def understand(*args, **kwargs):
        return Query("other", [], None, None)

    monkeypatch.setattr(askbot, "understand", understand)
    message, sent = mention("おはよう", readable(id=A, name="雑談"))
    assert any("紬は探しもの専門です" in t for t in ask(bot, message, sent))


def test_question_in_thread_searches_parent_record(tmp_path, monkeypatch):
    bot = make_bot(tmp_path, monkeypatch)
    bot.records.register(G, A)
    bot.records.upsert(msg(1, "旅行の日程"), [])

    async def understand(*args, **kwargs):
        return Query("message", ["旅行"], None, None)

    async def check(session, url, question, items, **kwargs):
        return items, True

    async def verify(items):
        return items, {}

    monkeypatch.setattr(askbot, "understand", understand)
    monkeypatch.setattr(askbot, "check_candidates", check)
    bot._verify = verify
    thread = readable(id=T, name="旅行スレ", parent_id=A, parent=SimpleNamespace(id=A, name="雑談"),
                      is_private=lambda: False)
    message, sent = mention("旅行の話", thread)
    texts = ask(bot, message, sent)
    assert texts[1].startswith("-# #雑談 の記録から")
    assert any("旅行の日程" in t for t in texts)


def test_asker_without_history_permission_is_refused(tmp_path, monkeypatch):
    bot = make_bot(tmp_path, monkeypatch)
    bot.records.register(G, A)
    message, sent = mention("旅行の話", readable(id=A, name="雑談", history=False))
    assert ask(bot, message, sent) == ["このチャンネルの履歴を読む権限がないので、探せません"]
    assert bot.queue.busy_users() == set()
