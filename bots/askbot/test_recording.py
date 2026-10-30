import asyncio
from datetime import datetime, timedelta, timezone
from types import SimpleNamespace

import discord

from askbot import RecordStore, backfill, record_of, should_record, to_record

G, A, T = 1, 100, 300
T0 = datetime(2026, 10, 1, 3, tzinfo=timezone.utc)


class Place:
    """A text channel, or a thread when parent_id is given."""

    def __init__(self, id, messages=(), parent_id=None, private=False, forbidden=False):
        self.id = id
        self.messages = list(messages)
        self.forbidden = forbidden
        self.calls = []
        if parent_id is not None:
            self.parent_id = parent_id
            self.is_private = lambda: private

    async def history(self, limit=None, after=None, oldest_first=None):
        self.calls.append(after)
        if self.forbidden:
            raise discord.Forbidden(SimpleNamespace(status=403, reason="Forbidden"), "no access")
        for m in self.messages:
            if after is None or m.created_at > after:
                yield m


class Channel(Place):
    def __init__(self, id, messages=(), threads=(), archived=(), archived_private=()):
        super().__init__(id, messages)
        self.threads = list(threads)
        self._archived = {False: list(archived), True: list(archived_private)}

    async def archived_threads(self, limit=None, private=False):
        for t in self._archived[private]:
            yield t


def message(id, place, content="", *, minutes=0, bot=False, webhook_id=None, embeds=(), attachments=()):
    return SimpleNamespace(
        id=id, guild=SimpleNamespace(id=G), channel=place, content=content, clean_content=content,
        author=SimpleNamespace(bot=bot, display_name="百鬼"), webhook_id=webhook_id,
        embeds=list(embeds), attachments=list(attachments), created_at=T0 + timedelta(minutes=minutes))


def image(id, content_type="image/png"):
    return SimpleNamespace(id=id, content_type=content_type, size=10)


def store(tmp_path):
    s = RecordStore(str(tmp_path / "tsumugi.db"))
    s.register(G, A)
    return s


def test_thread_message_goes_to_parent_record():
    assert record_of(Place(T, parent_id=A)) == (A, T, False)
    assert record_of(Place(A)) == (A, A, False)


def test_private_thread_is_marked_private():
    assert record_of(Place(T, parent_id=A, private=True)) == (A, T, True)


def test_bot_and_webhook_messages_are_not_recorded():
    place = Place(A)
    assert should_record(message(1, place))
    assert not should_record(message(2, place, bot=True))
    assert not should_record(message(3, place, webhook_id=99))


def test_links_include_embed_title_and_description():
    embed = SimpleNamespace(url="https://example.com/x", title="ドットインストール", description="Eラーニング")
    msg, _ = to_record(message(1, Place(A), "見て https://example.com/x", embeds=[embed]))
    assert msg.links.split("\n") == ["https://example.com/x", "ドットインストール", "Eラーニング"]
    assert msg.content == "見て https://example.com/x"


def test_only_image_attachments_become_photos():
    m = message(1, Place(T, parent_id=A, private=True),
                attachments=[image(11), image(12, "application/pdf"), image(13, None)])
    msg, photos = to_record(m)
    assert [p.attachment_id for p in photos] == [11]
    assert (photos[0].record_id, photos[0].place_id, photos[0].private) == (A, T, True)
    assert (msg.record_id, msg.place_id, msg.private) == (A, T, True)


def test_backfill_reads_threads_and_archived_threads(tmp_path):
    s = store(tmp_path)
    active = Place(301, parent_id=A)
    active.messages = [message(2, active, "スレッド")]
    archived = Place(302, parent_id=A)
    archived.messages = [message(3, archived, "古いスレッド")]
    hidden = Place(303, parent_id=A, private=True)
    hidden.messages = [message(4, hidden, "ひみつ")]
    channel = Channel(A, threads=[active], archived=[archived], archived_private=[hidden])
    channel.messages = [message(1, channel, "本文"), message(5, channel, "bot", bot=True)]
    assert asyncio.run(backfill(s, channel)) == 4
    assert s.stats(A).messages == 4
    assert [m.message_id for m in s.search_messages(A, 303, ["ひみつ"], None, None, False, 20)] == [4]


def test_backfill_resumes_after_latest_per_place(tmp_path):
    s = store(tmp_path)
    thread = Place(T, parent_id=A)
    thread.messages = [message(10, thread, "a", minutes=1), message(11, thread, "b", minutes=5)]
    channel = Channel(A, threads=[thread])
    channel.messages = [message(1, channel, "x", minutes=0), message(2, channel, "y", minutes=9)]
    assert asyncio.run(backfill(s, channel)) == 4
    channel.messages.append(message(3, channel, "z", minutes=20))
    assert asyncio.run(backfill(s, channel)) == 1
    assert channel.calls == [None, T0 + timedelta(minutes=9)]
    assert thread.calls == [None, T0 + timedelta(minutes=5)]


def test_backfill_stops_when_unregistered(tmp_path):
    s = store(tmp_path)
    channel = Channel(A)
    channel.messages = [message(1, channel, "x")]
    s.unregister(G, A)
    assert asyncio.run(backfill(s, channel)) == 0
    assert s.stats(A).messages == 0


def test_backfill_skips_threads_it_cannot_read(tmp_path):
    s = store(tmp_path)
    locked = Place(T, parent_id=A, private=True, forbidden=True)
    channel = Channel(A, threads=[locked])
    channel.messages = [message(1, channel, "x")]
    assert asyncio.run(backfill(s, channel)) == 1


def test_forum_without_history_reads_its_posts(tmp_path):
    s = store(tmp_path)
    post = Place(T, parent_id=A)
    post.messages = [message(1, post, "フォーラムの投稿")]
    forum = SimpleNamespace(id=A, threads=[post], archived_threads=Channel(A).archived_threads)
    assert asyncio.run(backfill(s, forum)) == 1


def test_backfill_not_fooled_by_live_messages(tmp_path):
    # A message posted (or edited) in a thread while backfill is still busy elsewhere must not
    # make backfill skip the thread's older history.
    s = store(tmp_path)
    thread = Place(T, parent_id=A)
    thread.messages = [message(10, thread, "古い", minutes=1), message(11, thread, "古い2", minutes=2)]
    live = message(12, thread, "いま", minutes=60)
    s.upsert(*to_record(live))
    thread.messages.append(live)
    channel = Channel(A, threads=[thread])
    asyncio.run(backfill(s, channel))
    assert s.stats(A).messages == 3


def test_unregister_forgets_backfill_progress(tmp_path):
    s = store(tmp_path)
    channel = Channel(A)
    channel.messages = [message(1, channel, "x")]
    asyncio.run(backfill(s, channel))
    s.unregister(G, A)
    s.register(G, A)
    assert asyncio.run(backfill(s, channel)) == 1
