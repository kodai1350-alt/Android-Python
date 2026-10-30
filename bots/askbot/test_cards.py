from datetime import datetime, timedelta

import discord

from askbot import (JST, PHOTO_KINDS, Msg, Photo, Query, RecordStats, SearchResult, help_card, jump_link,
                    not_found_card, record_card, results_card, status_card)

G, A = 1, 100
T0 = datetime(2026, 4, 18, 12, tzinfo=JST)
NOW = datetime(2026, 10, 30, 12, tzinfo=JST)


def msg(i, content="", links="", author="百鬼"):
    return Msg(i, G, A, A, False, author, content, links, T0 + timedelta(days=i))


def photo(i):
    return Photo(1000 + i, i, G, A, A, False, "kodai", T0 + timedelta(days=i), "料理", f"ラーメン{i}")


def query(kind="link", since=None, until=None):
    return Query(kind, ["Eラーニング"], since, until)


def texts(view):
    return [c.content for c in view.walk_children() if isinstance(c, discord.ui.TextDisplay)]


def buttons(view):
    return [c for c in view.walk_children() if isinstance(c, discord.ui.Button)]


def container(view):
    return view.children[0]


def test_results_card_has_jump_buttons_and_count_line():
    items = [msg(1, "", "https://dotinstall.com/\nドットインストール"), msg(2, "Eラーニングの課題")]
    view = results_card(query(since=datetime(2026, 4, 1, tzinfo=JST), until=datetime(2026, 6, 1, tzinfo=JST)),
                        "Eラーニングの話・半年前ごろ", "雑談", SearchResult(items, True, {}), 0, now=NOW)
    t = texts(view)
    assert t[0] == "### 🔎 Eラーニングの話・半年前ごろ"
    assert t[1] == "-# #雑談 の記録から ・ 4月1日〜5月31日 ・ 2件見つかりました"
    assert "**1**　4月19日　百鬼" in t[2] and "https://dotinstall.com/" in t[2] and "ドットインストール" in t[2]
    assert "Eラーニングの課題" in t[3]
    jumps = [b for b in buttons(view) if b.label == "元の発言へ"]
    assert [b.url for b in jumps] == [jump_link(G, A, 1), jump_link(G, A, 2)]
    assert jump_link(G, A, 1) == "https://discord.com/channels/1/100/1"


def test_results_card_pages_of_five_with_more_button():
    items = [msg(i, f"話{i}") for i in range(1, 8)]
    first = results_card(query("message"), "話", "雑談", SearchResult(items, True, {}), 0, now=NOW)
    second = results_card(query("message"), "話", "雑談", SearchResult(items, True, {}), 1, now=NOW)
    assert sum(b.label == "元の発言へ" for b in buttons(first)) == 5
    assert any(b.label == "もっと見る" and b.custom_id == "more" for b in buttons(first))
    assert sum(b.label == "元の発言へ" for b in buttons(second)) == 2
    assert not any(b.label == "もっと見る" for b in buttons(second))
    assert any(t.startswith("**6**") for t in texts(second))
    assert "全期間" in texts(first)[1]


def test_photo_results_use_thumbnails_and_kind_select():
    items = [photo(1), photo(2)]
    thumbs = {1001: "https://cdn.example/1.png", 1002: "https://cdn.example/2.png"}
    view = results_card(query("photo"), "ラーメンの写真", "飯テロ", SearchResult(items, True, thumbs), 0, now=NOW)
    assert texts(view)[0] == "### 🖼 ラーメンの写真"
    thumbnails = [c for c in view.walk_children() if isinstance(c, discord.ui.Thumbnail)]
    assert [t.media.url for t in thumbnails] == ["https://cdn.example/1.png", "https://cdn.example/2.png"]
    assert [b.label for b in buttons(view) if b.url] == ["1 を開く", "2 を開く"]
    select = next(c for c in view.walk_children() if isinstance(c, discord.ui.Select))
    assert select.custom_id == "kind" and select.placeholder == "種類でしぼる"
    assert [o.label for o in select.options] == PHOTO_KINDS
    assert "料理 ・ ラーメン1" in texts(view)[2]
    assert container(view).accent_color == 0xF0B232


def test_unchecked_note_shown():
    view = results_card(query(), "x", "雑談", SearchResult([msg(1, "a")], False, {}), 0, now=NOW)
    assert texts(view)[-1] == "-# ※ AIモデルで確かめられなかったので、言葉が当たった順に並べています"


def test_cards_stay_within_40_components():
    items = [photo(i) for i in range(1, 12)]
    view = results_card(query("photo"), "x", "飯テロ", SearchResult(items, False, {}), 0, now=NOW)
    assert view.total_children_count <= 40


def test_not_found_card_offers_wider_dates_only_with_dates():
    q = query(since=datetime(2026, 4, 1, tzinfo=JST), until=datetime(2026, 6, 1, tzinfo=JST))
    view = not_found_card(q, "雑談", now=NOW)
    assert texts(view)[0] == "### 🔎 見つかりませんでした"
    assert "Eラーニング" in texts(view)[1] and "4月1日〜5月31日" in texts(view)[1]
    assert [b.custom_id for b in buttons(view)] == ["widen"]
    assert buttons(not_found_card(query(), "雑談", now=NOW)) == []


def test_help_card_text():
    t = "\n".join(texts(help_card()))
    assert "紬は探しもの専門です" in t
    assert "@紬 Eラーニングの話で半年前ぐらいに貼ってたURLどれ？" in t
    assert "/記録する" in t


def test_record_card_numbers():
    stats = RecordStats(12480, datetime(2025, 4, 3, tzinfo=JST), 360, 214)
    view = record_card("飯テロ", stats)
    t = "\n".join(texts(view))
    assert "### 📁 #飯テロ の記録" in t
    assert "会話　**12,480件**（2025年4月〜）" in t
    assert "写真　**214 / 360枚** 分類ずみ" in t
    assert container(view).accent_color == 0x23A55A


def test_accent_colors():
    view = results_card(query(), "x", "雑談", SearchResult([msg(1, "a")], True, {}), 0, now=NOW)
    assert container(view).accent_color == 0x5865F2


def test_status_card_is_one_text():
    assert texts(status_card("🔎 探しています…")) == ["🔎 探しています…"]


def test_record_card_note_is_its_own_small_line():
    # "-#" only makes small text at the start of a line; mid-line Discord shows it literally.
    t = "\n".join(texts(record_card("飯テロ", RecordStats(10, None, 5, 2))))
    assert "\n-# 残りは空き時間に進めます" in t
    assert "　-#" not in t
    assert "残りは" not in "\n".join(texts(record_card("飯テロ", RecordStats(10, None, 5, 5))))
