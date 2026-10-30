from datetime import datetime

from askbot import JST, parse_when

NOW = datetime(2026, 10, 30, 12, tzinfo=JST)


def d(month, day, year=2026):
    return datetime(year, month, day, tzinfo=JST)


def test_exact_words_keep_their_old_ranges():
    assert parse_when("昨日", NOW) == (d(10, 29), d(10, 30))
    assert parse_when("先週", NOW) == (d(10, 19), d(10, 26))
    assert parse_when("10月14日", NOW) == (d(10, 14), d(10, 15))


def test_about_n_ago_is_widened():
    # since = center - width, until = center + width + 1 day (the last day is included)
    assert parse_when("半年前ぐらい", NOW) == (d(3, 30), d(5, 31))
    assert parse_when("半月前", NOW) == (d(10, 10), d(10, 21))
    assert parse_when("3日前ぐらい", NOW) == (d(10, 26), d(10, 29))
    assert parse_when("2週間前", NOW) == (d(10, 13), d(10, 20))


def test_months_and_years_ago():
    assert parse_when("2か月前", NOW) == (d(7, 30), d(10, 1))
    assert parse_when("2ヶ月前", NOW) == parse_when("2カ月前", NOW) == (d(7, 30), d(10, 1))
    assert parse_when("1年前ごろ", NOW) == (d(7, 30, 2025), d(1, 31, 2026))


def test_seasons_and_years():
    assert parse_when("去年の夏", NOW) == (d(6, 1, 2025), d(9, 1, 2025))
    assert parse_when("去年の冬", NOW) == (d(12, 1, 2025), d(3, 1, 2026))
    assert parse_when("去年", NOW) == (d(1, 1, 2025), d(1, 1, 2026))


def test_recent_and_none():
    assert parse_when("最近", NOW) == (d(10, 16), d(10, 31))
    assert parse_when("この前", NOW) == (d(9, 30), d(10, 31))
    assert parse_when("猫の写真", NOW) == (None, None)


def test_full_width_digits():
    assert parse_when("３日前", NOW) == parse_when("3日前", NOW)
