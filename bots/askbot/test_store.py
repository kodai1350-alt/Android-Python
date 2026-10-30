import sqlite3
from datetime import datetime, timedelta

from askbot import JST, Msg, Photo, RecordStore

G, A, B, T = 1, 100, 200, 300  # guild, record A, record B, a thread inside A
T0 = datetime(2026, 10, 1, 12, tzinfo=JST)


def msg(message_id, content="", *, record=A, place=None, private=False, links="", days=0, author="百鬼"):
    return Msg(message_id, G, record, place or record, private, author, content, links, T0 + timedelta(days=days))


def photo(attachment_id, message_id, *, record=A, place=None, private=False, days=0, kind=None, description=None):
    return Photo(attachment_id, message_id, G, record, place or record, private, "百鬼",
                 T0 + timedelta(days=days), kind, description)


def store(tmp_path, *records):
    s = RecordStore(str(tmp_path / "tsumugi.db"))
    for r in records or (A,):
        s.register(G, r)
    return s


def ids(items):
    return [getattr(i, "attachment_id", None) or i.message_id for i in items]


def test_search_finds_words_in_links_too(tmp_path):
    s = store(tmp_path)
    s.upsert(msg(1, "これ見て", links="https://dotinstall.com/\nドットインストール Eラーニング"), [])
    s.upsert(msg(2, "晩ごはん"), [])
    assert ids(s.search_messages(A, A, ["Eラーニング"], None, None, False, 20)) == [1]


def test_links_only_skips_messages_without_links(tmp_path):
    s = store(tmp_path)
    s.upsert(msg(1, "Eラーニングの課題"), [])
    s.upsert(msg(2, "", links="https://schoo.jp/ Eラーニング"), [])
    assert ids(s.search_messages(A, A, ["Eラーニング"], None, None, True, 20)) == [2]


def test_search_stays_in_one_record(tmp_path):
    s = store(tmp_path, A, B)
    s.upsert(msg(1, "旅行の日程", record=A), [])
    s.upsert(msg(2, "旅行の日程", record=B), [])
    assert ids(s.search_messages(A, A, ["旅行"], None, None, False, 20)) == [1]


def test_search_by_dates_and_most_hits_first(tmp_path):
    s = store(tmp_path)
    s.upsert(msg(1, "旅行", days=0), [])
    s.upsert(msg(2, "旅行の日程", days=1), [])
    s.upsert(msg(3, "旅行", days=5), [])
    found = s.search_messages(A, A, ["旅行", "日程"], T0 - timedelta(days=1), T0 + timedelta(days=2), False, 20)
    assert ids(found) == [2, 1]


def test_private_thread_only_from_inside(tmp_path):
    s = store(tmp_path)
    s.upsert(msg(1, "ひみつの旅行", place=T, private=True), [photo(11, 1, place=T, private=True)])
    s.save_class(11, "風景", "旅行先の海")
    assert ids(s.search_messages(A, T, ["旅行"], None, None, False, 20)) == [1]
    assert ids(s.search_messages(A, A, ["旅行"], None, None, False, 20)) == []
    assert ids(s.search_messages(A, 999, ["旅行"], None, None, False, 20)) == []
    assert ids(s.search_photos(A, T, ["海"], None, None, None, 20)) == [11]
    assert ids(s.search_photos(A, A, ["海"], None, None, None, 20)) == []


def test_unregister_removes_messages_and_photos(tmp_path):
    s = store(tmp_path)
    s.upsert(msg(1, "旅行"), [photo(11, 1)])
    s.unregister(G, A)
    s.register(G, A)
    assert s.stats(A).messages == 0 and s.stats(A).photos == 0


def test_upsert_ignored_when_not_registered(tmp_path):
    s = store(tmp_path)
    s.upsert(msg(1, "旅行", record=B), [photo(11, 1, record=B)])
    assert s.stats(B).messages == 0 and s.stats(B).photos == 0


def test_reupsert_keeps_photo_class(tmp_path):
    s = store(tmp_path)
    s.upsert(msg(1), [photo(11, 1)])
    s.save_class(11, "料理", "ラーメン")
    s.upsert(msg(1, "編集した"), [photo(11, 1)])
    assert [(p.kind, p.description) for p in s.search_photos(A, A, ["ラーメン"], None, None, None, 20)] == [
        ("料理", "ラーメン")]


def test_edit_that_removes_an_image_removes_the_photo(tmp_path):
    s = store(tmp_path)
    s.upsert(msg(1), [photo(11, 1), photo(12, 1)])
    s.upsert(msg(1), [photo(11, 1)])
    assert s.stats(A).photos == 1


def test_delete_message_removes_its_photos(tmp_path):
    s = store(tmp_path)
    s.upsert(msg(1), [photo(11, 1)])
    s.delete_message(1)
    assert s.stats(A).messages == 0 and s.stats(A).photos == 0


def test_next_unclassified_is_newest_first(tmp_path):
    s = store(tmp_path)
    s.upsert(msg(1, days=0), [photo(11, 1, days=0)])
    s.upsert(msg(2, days=3), [photo(12, 2, days=3)])
    assert s.next_unclassified().attachment_id == 12
    s.save_class(12, "料理", "ラーメン")
    assert s.next_unclassified().attachment_id == 11
    s.save_class(11, "風景", "海")
    assert s.next_unclassified() is None


def test_search_photos_by_description_and_kind(tmp_path):
    s = store(tmp_path)
    s.upsert(msg(1), [photo(11, 1), photo(12, 1), photo(13, 1)])
    s.save_class(11, "料理", "ラーメン。チャーシュー")
    s.save_class(12, "風景", "ラーメン屋の看板")
    assert set(ids(s.search_photos(A, A, ["ラーメン"], None, None, None, 20))) == {11, 12}
    assert ids(s.search_photos(A, A, ["ラーメン"], "料理", None, None, 20)) == [11]
    assert ids(s.search_photos(A, A, [], "料理", None, None, 20)) == [11]  # 13 is not classified yet


def test_two_character_words_are_found(tmp_path):
    s = store(tmp_path)
    s.upsert(msg(1, "来月の旅行"), [])
    assert ids(s.search_messages(A, A, ["旅行"], None, None, False, 20)) == [1]


def test_stats_counts(tmp_path):
    s = store(tmp_path)
    s.upsert(msg(1, days=0), [photo(11, 1)])
    s.upsert(msg(2, days=2), [photo(12, 2), photo(13, 2)])
    s.save_class(12, "料理", "ラーメン")
    st = s.stats(A)
    assert (st.messages, st.since, st.photos, st.classified) == (2, T0, 3, 1)


def test_latest_is_per_place(tmp_path):
    s = store(tmp_path)
    s.upsert(msg(1, days=0), [])
    s.upsert(msg(2, days=4, place=T), [])
    assert s.latest(A) == T0
    assert s.latest(T) == T0 + timedelta(days=4)
    assert s.latest(999) is None


def test_import_old_copies_registrations(tmp_path):
    old = tmp_path / "askbot-history.db"
    db = sqlite3.connect(old)
    db.execute("CREATE TABLE channels(channel_id INTEGER PRIMARY KEY, guild_id INTEGER NOT NULL)")
    db.execute("INSERT INTO channels VALUES (?, ?)", (B, G))
    db.commit()
    db.close()
    s = RecordStore(str(tmp_path / "tsumugi.db"))
    s.import_old(str(old))
    assert s.registered(G) == {B}
    assert old.exists()
    s.import_old(str(tmp_path / "missing.db"))  # no old file: nothing happens
