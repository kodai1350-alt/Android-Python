"""askbot: a Discord bot that answers @mentions with the LLM running on this phone.

Run it in Python Runner. It needs:
  - TOKEN        the Discord bot token (アプリの「秘密の値」)
  - LLM_URL      set by the app while the AI model is downloaded
  - LLM_API_KEY  set by the app together with LLM_URL
"""
import asyncio
import base64
import json
import logging
import os
import re
import sqlite3
import sys
import time
import unicodedata
import urllib.parse
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone

import aiohttp
import discord
from discord import app_commands

log = logging.getLogger("askbot")

# --- Replies: splitting and throttled edits ---------------------------------

DISCORD_LIMIT = 2000
EDIT_INTERVAL = 1.5

_PROGRESS = {
    "wiki": "📖 Wikipediaを読んでいます… {p}%",
    # Photos sit at a low percent while the image is encoded, so say that a wait is normal.
    "photo": "🖼 写真を見ています… {p}%（数分かかります）",
    "history": "🔎 前の会話を読んでいます… {p}%",
    "question": "💭 質問を読んでいます… {p}%",
}


def split_message(text, limit=DISCORD_LIMIT):
    """Splits text into Discord-sized parts, at the last newline that fits when there is one."""
    parts = []
    while len(text) > limit:
        cut = text.rfind("\n", 0, limit + 1)
        if cut <= 0:
            parts.append(text[:limit])
            text = text[limit:]
        else:
            parts.append(text[:cut])
            text = text[cut + 1:]
    if text or not parts:
        parts.append(text)
    return parts


def progress_text(kind, percent):
    return _PROGRESS[kind].format(p=max(0, min(100, int(percent))))


class ReplyWriter:
    """Shows one answer by editing messages: a status line until text arrives, then the text.

    Edits are at least `interval` seconds apart (Discord rate limits); `finish` always flushes.
    Text past 2000 characters continues in new messages from `send_more`.
    """

    def __init__(self, first, send_more, *, interval=EDIT_INTERVAL, clock=time.monotonic):
        self._messages = [first]
        self._shown = [getattr(first, "content", None)]
        self._send_more = send_more
        self._interval = interval
        self._clock = clock
        self._last_edit = None
        self._status = ""
        self._text = ""

    async def status(self, text):
        self._status = text
        await self._flush(force=False)

    async def append(self, piece):
        self._text += piece
        await self._flush(force=False)

    async def finish(self, footer=""):
        await self._flush(force=True, footer=footer)

    async def _flush(self, force, footer=""):
        now = self._clock()
        if not force and self._last_edit is not None and now - self._last_edit < self._interval:
            return
        body = self._text.strip() or self._status or "…"
        if footer:
            body += "\n\n" + footer
        for i, part in enumerate(split_message(body)):
            if i < len(self._messages):
                if self._shown[i] != part:
                    await self._messages[i].edit(content=part)
                    self._shown[i] = part
            else:
                self._messages.append(await self._send_more(part))
                self._shown.append(part)
        self._last_edit = now


# --- Queue: one question at a time -----------------------------------------

class AskQueue:
    """Runs one question at a time, in order; each person may have only one question queued.

    The phone reads only one prompt at a time (reading stalls everything else), so answers are
    never interleaved and never cut short for the next person.
    """

    def __init__(self):
        self._waiting = []
        self._running_user = None
        self._wake = asyncio.Event()

    def busy_users(self):
        users = {user for user, _, _ in self._waiting}
        if self._running_user is not None:
            users.add(self._running_user)
        return users

    def submit(self, user_id, job, on_position):
        """Returns the place in line (0 = starts now), or None if this person already has a question."""
        if user_id in self.busy_users():
            return None
        self._waiting.append((user_id, job, on_position))
        self._wake.set()
        return len(self._waiting) - (0 if self._running_user is not None else 1)

    async def run_forever(self):
        while True:
            if not self._waiting:
                self._wake.clear()
                await self._wake.wait()
                continue
            user, job, _ = self._waiting.pop(0)
            self._running_user = user
            for i, (_, _, on_position) in enumerate(self._waiting):
                try:
                    await on_position(i + 1)
                except Exception:
                    log.exception("could not update a waiting message")
            try:
                await job()
            except Exception:
                log.exception("question failed")
            finally:
                self._running_user = None


# --- Questions: which kind, which words, which dates ------------------------

JST = timezone(timedelta(hours=9))

_HISTORY_WORDS = ("話した", "話してた", "言ってた", "会話", "だっけ", "前に", "この前", "さっきの")
_STOP_WORDS = {"今日", "昨日", "先週", "今週", "先月", "今月", "去年", "何日", "何月", "写真", "画像", "名前", "何", "前"}
# Runs of katakana, kanji (U+4E00–U+9FFF) or letters/digits: the nouns of a Japanese question, roughly.
_KEYWORD = re.compile(r"[ァ-ヶー]{2,}|[一-鿿々]{2,}|[A-Za-z0-9]{2,}")
_MONTH_DAY = re.compile(r"(\d{1,2})月(\d{1,2})日")
_MONTH = re.compile(r"(\d{1,2})月")


@dataclass
class Plan:
    mode: str  # "photo" | "history" | "wiki" | "chat"
    keywords: list = field(default_factory=list)
    since: datetime = None
    until: datetime = None


def _day(now, days_ago=0):
    d = now.astimezone(JST) - timedelta(days=days_ago)
    return datetime(d.year, d.month, d.day, tzinfo=JST)


def _month_start(year, month):
    while month < 1:
        month, year = month + 12, year - 1
    while month > 12:
        month, year = month - 12, year + 1
    return datetime(year, month, 1, tzinfo=JST)


def _find_dates(text, now):
    """Returns ((since, until), matched span) for the first date expression, or (None, None)."""
    today = _day(now)
    monday = today - timedelta(days=today.weekday())
    fixed = [
        ("一昨日", (_day(now, 2), _day(now, 1))),
        ("昨日", (_day(now, 1), today)),
        ("今日", (today, today + timedelta(days=1))),
        ("先週", (monday - timedelta(days=7), monday)),
        ("今週", (monday, monday + timedelta(days=7))),
        ("先月", (_month_start(today.year, today.month - 1), _month_start(today.year, today.month))),
        ("今月", (_month_start(today.year, today.month), _month_start(today.year, today.month + 1))),
    ]
    for word, span in fixed:
        i = text.find(word)
        if i >= 0:
            return span, (i, i + len(word))
    m = _MONTH_DAY.search(text)
    if m:
        month, day = int(m.group(1)), int(m.group(2))
        try:
            start = datetime(today.year, month, day, tzinfo=JST)
        except ValueError:
            start = None
        if start is not None:
            if start > today:
                start = start.replace(year=today.year - 1)
            return (start, start + timedelta(days=1)), m.span()
    m = _MONTH.search(text)
    if m and 1 <= int(m.group(1)) <= 12:
        month = int(m.group(1))
        year = today.year if month <= today.month else today.year - 1
        return (_month_start(year, month), _month_start(year, month + 1)), m.span()
    return None, None


_AGO = re.compile(r"(\d+|半)(日|週間|週|か月|ヶ月|カ月|ケ月|月|年)前")
_SEASONS = {"春": (3, 6), "夏": (6, 9), "秋": (9, 12), "冬": (12, 15)}


def _add_months(dt, months):
    """`dt` moved by whole months; the day is clamped to the end of a shorter month."""
    start = _month_start(dt.year, dt.month + months)
    following = _month_start(start.year, start.month + 1)
    return start.replace(day=min(dt.day, (following - start).days))


def _ago(amount, unit, today):
    """(since, until) around "N units ago": since = center - width, until = center + width + 1 day."""
    day = timedelta(days=1)
    if amount == "半":
        if unit == "月":
            center, width = today - timedelta(days=15), timedelta(days=5)
        elif unit == "年":
            center = _add_months(today, -6)
            return _add_months(center, -1), _add_months(center, 1) + day
        else:
            return None
        return center - width, center + width + day
    n = int(amount)
    if unit == "日":
        center, width = today - timedelta(days=n), day
    elif unit in ("週間", "週"):
        center, width = today - timedelta(days=7 * n), timedelta(days=3)
    elif unit == "年":
        center = _add_months(today, -12 * n)
        return _add_months(center, -3), _add_months(center, 3) + day
    elif unit == "月":
        return None  # "3月前" is not a time word
    else:
        center = _add_months(today, -n)
        return _add_months(center, -1), _add_months(center, 1) + day
    return center - width, center + width + day


def parse_when(text, now):
    """The JST (since, until) a vague time word means; until is exclusive. (None, None) if none.

    Vague words get a margin on both sides: people misremember dates, and a missed message is
    worse than one more candidate to check.
    """
    text = unicodedata.normalize("NFKC", text)
    today = _day(now)
    m = re.search(r"去年の(春|夏|秋|冬)", text)
    if m:
        first, last = _SEASONS[m.group(1)]
        return _month_start(today.year - 1, first), _month_start(today.year - 1, last)
    if "去年" in text:
        return _month_start(today.year - 1, 1), _month_start(today.year, 1)
    for m in _AGO.finditer(text):
        span = _ago(m.group(1), m.group(2), today)
        if span:
            return span
    if "最近" in text:
        return today - timedelta(days=14), today + timedelta(days=1)
    if "この前" in text:
        return today - timedelta(days=30), today + timedelta(days=1)
    span, _ = _find_dates(text, now)
    return span or (None, None)


def extract_keywords(text):
    words = []
    for m in _KEYWORD.finditer(unicodedata.normalize("NFKC", text)):
        w = m.group(0)
        if w not in _STOP_WORDS and w not in words:
            words.append(w)
    return words


def plan_question(text, has_image, now):
    text = unicodedata.normalize("NFKC", text)
    span, where = _find_dates(text, now)
    rest = text[:where[0]] + " " + text[where[1]:] if where else text
    keywords = extract_keywords(rest)
    since, until = span or (None, None)
    if has_image:
        mode = "photo"
    elif span or any(w in text for w in _HISTORY_WORDS):
        mode = "history"
    elif keywords:
        mode = "wiki"
    else:
        mode = "chat"
    return Plan(mode, keywords, since, until)


# --- History: messages of registered channels, searchable -------------------

@dataclass
class Stored:
    message_id: int
    guild_id: int
    channel_id: int
    author: str
    content: str
    created_at: datetime


def jump_url(s):
    return f"https://discord.com/channels/{s.guild_id}/{s.channel_id}/{s.message_id}"


_SCHEMA = """
CREATE TABLE IF NOT EXISTS channels(channel_id INTEGER PRIMARY KEY, guild_id INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS messages(
    message_id INTEGER PRIMARY KEY, guild_id INTEGER NOT NULL, channel_id INTEGER NOT NULL,
    author TEXT NOT NULL, content TEXT NOT NULL, created_at REAL NOT NULL);
CREATE INDEX IF NOT EXISTS messages_by_channel ON messages(channel_id, created_at);
CREATE VIRTUAL TABLE IF NOT EXISTS messages_fts USING fts5(
    content, tokenize='trigram', content='messages', content_rowid='message_id');
CREATE TRIGGER IF NOT EXISTS messages_ai AFTER INSERT ON messages BEGIN
    INSERT INTO messages_fts(rowid, content) VALUES (new.message_id, new.content);
END;
CREATE TRIGGER IF NOT EXISTS messages_ad AFTER DELETE ON messages BEGIN
    INSERT INTO messages_fts(messages_fts, rowid, content) VALUES ('delete', old.message_id, old.content);
END;
CREATE TRIGGER IF NOT EXISTS messages_au AFTER UPDATE ON messages BEGIN
    INSERT INTO messages_fts(messages_fts, rowid, content) VALUES ('delete', old.message_id, old.content);
    INSERT INTO messages_fts(rowid, content) VALUES (new.message_id, new.content);
END;
"""


def _like(word):
    """A LIKE pattern matching `word` anywhere; '!' is the escape character (ESCAPE '!')."""
    return "%" + word.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%"


class HistoryStore:
    """Messages of registered channels, kept in step with Discord (edits and deletes)."""

    def __init__(self, path):
        # Python Runner sets HOME to a folder it does not create.
        os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
        self._db = sqlite3.connect(path)
        self._db.executescript(_SCHEMA)

    def register(self, guild_id, channel_id):
        with self._db:
            self._db.execute("INSERT OR REPLACE INTO channels VALUES (?, ?)", (channel_id, guild_id))

    def unregister(self, guild_id, channel_id):
        with self._db:
            self._db.execute("DELETE FROM channels WHERE channel_id = ? AND guild_id = ?", (channel_id, guild_id))
            self._db.execute("DELETE FROM messages WHERE channel_id = ?", (channel_id,))

    def registered(self, guild_id):
        rows = self._db.execute("SELECT channel_id FROM channels WHERE guild_id = ?", (guild_id,))
        return {r[0] for r in rows}

    def is_registered(self, channel_id):
        return self._db.execute("SELECT 1 FROM channels WHERE channel_id = ?", (channel_id,)).fetchone() is not None

    def upsert(self, s):
        """Stores or updates a message, but only while its channel is registered (checked in the same
        statement, so a message cannot slip in after /記録やめる)."""
        with self._db:
            self._db.execute(
                "INSERT INTO messages SELECT ?, ?, ?, ?, ?, ? "
                "WHERE EXISTS (SELECT 1 FROM channels WHERE channel_id = ?) "
                "ON CONFLICT(message_id) DO UPDATE SET author = excluded.author, content = excluded.content",
                (s.message_id, s.guild_id, s.channel_id, s.author, s.content, s.created_at.timestamp(), s.channel_id),
            )

    def delete(self, message_id):
        with self._db:
            self._db.execute("DELETE FROM messages WHERE message_id = ?", (message_id,))

    def latest(self, channel_id):
        row = self._db.execute("SELECT MAX(created_at) FROM messages WHERE channel_id = ?", (channel_id,)).fetchone()
        return datetime.fromtimestamp(row[0], timezone.utc) if row[0] is not None else None

    def search(self, guild_id, channel_ids, keywords, since, until, limit=20):
        """Messages in the given channels and dates, most keyword hits first, then newest first.

        Words of 3+ characters use the trigram index; shorter ones (名前, 店) fall back to LIKE.
        """
        if not channel_ids:
            return []
        where = [f"guild_id = ? AND channel_id IN ({','.join('?' * len(channel_ids))})"]
        params = [guild_id, *channel_ids]
        if since is not None:
            where.append("created_at >= ?")
            params.append(since.timestamp())
        if until is not None:
            where.append("created_at < ?")
            params.append(until.timestamp())
        score = "0"
        score_params = []
        if keywords:
            hits = []
            long_words = [k for k in keywords if len(k) >= 3]
            if long_words:
                hits.append("message_id IN (SELECT rowid FROM messages_fts WHERE messages_fts MATCH ?)")
                params.append(" OR ".join('"' + k.replace('"', '""') + '"' for k in long_words))
            for k in keywords:
                if len(k) < 3:
                    hits.append("content LIKE ? ESCAPE '!'")
                    params.append(_like(k))
            where.append("(" + " OR ".join(hits) + ")")
            score = " + ".join("(content LIKE ? ESCAPE '!')" for _ in keywords)
            score_params = [_like(k) for k in keywords]
        sql = (
            f"SELECT message_id, guild_id, channel_id, author, content, created_at, {score} AS score "
            f"FROM messages WHERE {' AND '.join(where)} ORDER BY score DESC, created_at DESC LIMIT ?"
        )
        rows = self._db.execute(sql, [*score_params, *params, limit])
        return [
            Stored(r[0], r[1], r[2], r[3], r[4], datetime.fromtimestamp(r[5], timezone.utc))
            for r in rows
        ]


# --- Records: per channel (threads included), with links and photos ----------

@dataclass
class Msg:
    message_id: int
    guild_id: int
    record_id: int  # the recorded channel (a thread's parent)
    place_id: int  # where it was written: the channel or a thread
    private: bool  # written in a private thread
    author: str
    content: str
    links: str  # URLs and link previews (title, description), one per line
    created_at: datetime


@dataclass
class Photo:
    attachment_id: int
    message_id: int
    guild_id: int
    record_id: int
    place_id: int
    private: bool
    author: str
    created_at: datetime
    kind: str = None
    description: str = None


@dataclass
class RecordStats:
    messages: int
    since: datetime
    photos: int
    classified: int


_RECORD_SCHEMA = """
CREATE TABLE IF NOT EXISTS records(record_id INTEGER PRIMARY KEY, guild_id INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS messages(
    message_id INTEGER PRIMARY KEY, guild_id INTEGER NOT NULL, record_id INTEGER NOT NULL,
    place_id INTEGER NOT NULL, private INTEGER NOT NULL, author TEXT NOT NULL, content TEXT NOT NULL,
    links TEXT NOT NULL, created_at REAL NOT NULL);
CREATE INDEX IF NOT EXISTS messages_by_record ON messages(record_id, created_at);
CREATE INDEX IF NOT EXISTS messages_by_place ON messages(place_id, created_at);
CREATE VIRTUAL TABLE IF NOT EXISTS messages_fts USING fts5(
    content, links, tokenize='trigram', content='messages', content_rowid='message_id');
CREATE TRIGGER IF NOT EXISTS messages_ai AFTER INSERT ON messages BEGIN
    INSERT INTO messages_fts(rowid, content, links) VALUES (new.message_id, new.content, new.links);
END;
CREATE TRIGGER IF NOT EXISTS messages_ad AFTER DELETE ON messages BEGIN
    INSERT INTO messages_fts(messages_fts, rowid, content, links)
        VALUES ('delete', old.message_id, old.content, old.links);
END;
CREATE TRIGGER IF NOT EXISTS messages_au AFTER UPDATE ON messages BEGIN
    INSERT INTO messages_fts(messages_fts, rowid, content, links)
        VALUES ('delete', old.message_id, old.content, old.links);
    INSERT INTO messages_fts(rowid, content, links) VALUES (new.message_id, new.content, new.links);
END;
CREATE TABLE IF NOT EXISTS photos(
    attachment_id INTEGER PRIMARY KEY, message_id INTEGER NOT NULL, guild_id INTEGER NOT NULL,
    record_id INTEGER NOT NULL, place_id INTEGER NOT NULL, private INTEGER NOT NULL, author TEXT NOT NULL,
    created_at REAL NOT NULL, kind TEXT, description TEXT);
CREATE INDEX IF NOT EXISTS photos_by_message ON photos(message_id);
CREATE INDEX IF NOT EXISTS photos_unclassified ON photos(kind, created_at);
CREATE VIRTUAL TABLE IF NOT EXISTS photos_fts USING fts5(
    kind, description, tokenize='trigram', content='photos', content_rowid='attachment_id');
CREATE TRIGGER IF NOT EXISTS photos_ai AFTER INSERT ON photos BEGIN
    INSERT INTO photos_fts(rowid, kind, description) VALUES (new.attachment_id, new.kind, new.description);
END;
CREATE TRIGGER IF NOT EXISTS photos_ad AFTER DELETE ON photos BEGIN
    INSERT INTO photos_fts(photos_fts, rowid, kind, description)
        VALUES ('delete', old.attachment_id, old.kind, old.description);
END;
CREATE TRIGGER IF NOT EXISTS photos_au AFTER UPDATE ON photos BEGIN
    INSERT INTO photos_fts(photos_fts, rowid, kind, description)
        VALUES ('delete', old.attachment_id, old.kind, old.description);
    INSERT INTO photos_fts(rowid, kind, description) VALUES (new.attachment_id, new.kind, new.description);
END;
"""

_MSG_COLUMNS = "message_id, guild_id, record_id, place_id, private, author, content, links, created_at"
_PHOTO_COLUMNS = ("attachment_id, message_id, guild_id, record_id, place_id, private, author, created_at, "
                  "kind, description")


def _ts(dt):
    return datetime.fromtimestamp(dt, timezone.utc)


def _word_filter(words, fts_table, rowid, text_sql):
    """(where, params, score, score_params) matching any of `words` in `text_sql`.

    Words of 3+ characters use the trigram index; shorter ones (旅行, 猫) fall back to LIKE.
    """
    hits, params = [], []
    long_words = [w for w in words if len(w) >= 3]
    if long_words:
        hits.append(f"{rowid} IN (SELECT rowid FROM {fts_table} WHERE {fts_table} MATCH ?)")
        params.append(" OR ".join('"' + w.replace('"', '""') + '"' for w in long_words))
    for w in words:
        if len(w) < 3:
            hits.append(f"{text_sql} LIKE ? ESCAPE '!'")
            params.append(_like(w))
    score = " + ".join(f"({text_sql} LIKE ? ESCAPE '!')" for _ in words)
    return "(" + " OR ".join(hits) + ")", params, score, [_like(w) for w in words]


class RecordStore:
    """What 紬 remembers, per recorded channel, kept in step with Discord (edits and deletes)."""

    def __init__(self, path):
        # Python Runner sets HOME to a folder it does not create.
        os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
        self._db = sqlite3.connect(path)
        self._db.executescript(_RECORD_SCHEMA)

    def register(self, guild_id, record_id):
        with self._db:
            self._db.execute("INSERT OR REPLACE INTO records VALUES (?, ?)", (record_id, guild_id))

    def unregister(self, guild_id, record_id):
        with self._db:
            self._db.execute("DELETE FROM records WHERE record_id = ? AND guild_id = ?", (record_id, guild_id))
            self._db.execute("DELETE FROM messages WHERE record_id = ?", (record_id,))
            self._db.execute("DELETE FROM photos WHERE record_id = ?", (record_id,))

    def registered(self, guild_id):
        return {r[0] for r in self._db.execute("SELECT record_id FROM records WHERE guild_id = ?", (guild_id,))}

    def is_registered(self, record_id):
        return self._db.execute("SELECT 1 FROM records WHERE record_id = ?", (record_id,)).fetchone() is not None

    def upsert(self, msg, photos):
        """Stores or updates a message and its photos, but only while its record is registered (checked
        in the same statements, so nothing slips in after /記録やめる). A photo's class is kept."""
        registered = "WHERE EXISTS (SELECT 1 FROM records WHERE record_id = ?)"
        with self._db:
            self._db.execute(
                f"INSERT INTO messages SELECT ?, ?, ?, ?, ?, ?, ?, ?, ? {registered} "
                "ON CONFLICT(message_id) DO UPDATE SET author = excluded.author, content = excluded.content, "
                "links = excluded.links",
                (msg.message_id, msg.guild_id, msg.record_id, msg.place_id, int(msg.private), msg.author,
                 msg.content, msg.links, msg.created_at.timestamp(), msg.record_id),
            )
            keep = [p.attachment_id for p in photos]
            self._db.execute(
                f"DELETE FROM photos WHERE message_id = ? AND attachment_id NOT IN ({','.join('?' * len(keep))})",
                (msg.message_id, *keep),
            )
            for p in photos:
                self._db.execute(
                    f"INSERT INTO photos SELECT ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL {registered} "
                    "ON CONFLICT(attachment_id) DO NOTHING",
                    (p.attachment_id, p.message_id, p.guild_id, p.record_id, p.place_id, int(p.private), p.author,
                     p.created_at.timestamp(), p.record_id),
                )

    def delete_message(self, message_id):
        with self._db:
            self._db.execute("DELETE FROM photos WHERE message_id = ?", (message_id,))
            self._db.execute("DELETE FROM messages WHERE message_id = ?", (message_id,))

    def latest(self, place_id):
        row = self._db.execute("SELECT MAX(created_at) FROM messages WHERE place_id = ?", (place_id,)).fetchone()
        return _ts(row[0]) if row[0] is not None else None

    @staticmethod
    def _scope(record_id, place_id, since, until):
        # A private thread's contents are shown only to a question asked inside that thread.
        where = ["record_id = ?", "(private = 0 OR place_id = ?)"]
        params = [record_id, place_id]
        if since is not None:
            where.append("created_at >= ?")
            params.append(since.timestamp())
        if until is not None:
            where.append("created_at < ?")
            params.append(until.timestamp())
        return where, params

    def search_messages(self, record_id, place_id, words, since, until, links_only, limit):
        """Messages of one record, most word hits first, then newest first."""
        where, params = self._scope(record_id, place_id, since, until)
        if links_only:
            where.append("links != ''")
        score, score_params = "0", []
        if words:
            hit, hit_params, score, score_params = _word_filter(
                words, "messages_fts", "message_id", "(content || ' ' || links)")
            where.append(hit)
            params += hit_params
        rows = self._db.execute(
            f"SELECT {_MSG_COLUMNS}, {score} AS score FROM messages WHERE {' AND '.join(where)} "
            "ORDER BY score DESC, created_at DESC LIMIT ?", [*score_params, *params, limit])
        return [Msg(r[0], r[1], r[2], r[3], bool(r[4]), r[5], r[6], r[7], _ts(r[8])) for r in rows]

    def search_photos(self, record_id, place_id, words, kind, since, until, limit):
        """Classified photos of one record, most word hits first, then newest first."""
        where, params = self._scope(record_id, place_id, since, until)
        where.append("kind IS NOT NULL")
        if kind:
            where.append("kind = ?")
            params.append(kind)
        score, score_params = "0", []
        if words:
            hit, hit_params, score, score_params = _word_filter(
                words, "photos_fts", "attachment_id", "(kind || ' ' || description)")
            where.append(hit)
            params += hit_params
        rows = self._db.execute(
            f"SELECT {_PHOTO_COLUMNS}, {score} AS score FROM photos WHERE {' AND '.join(where)} "
            "ORDER BY score DESC, created_at DESC LIMIT ?", [*score_params, *params, limit])
        return [self._photo(r) for r in rows]

    @staticmethod
    def _photo(r):
        return Photo(r[0], r[1], r[2], r[3], r[4], bool(r[5]), r[6], _ts(r[7]), r[8], r[9])

    def next_unclassified(self):
        """The newest photo not classified yet, or None."""
        r = self._db.execute(
            f"SELECT {_PHOTO_COLUMNS} FROM photos WHERE kind IS NULL ORDER BY created_at DESC LIMIT 1").fetchone()
        return self._photo(r) if r else None

    def save_class(self, attachment_id, kind, description):
        with self._db:
            self._db.execute("UPDATE photos SET kind = ?, description = ? WHERE attachment_id = ?",
                             (kind, description, attachment_id))

    def stats(self, record_id):
        n, first = self._db.execute(
            "SELECT COUNT(*), MIN(created_at) FROM messages WHERE record_id = ?", (record_id,)).fetchone()
        photos, classified = self._db.execute(
            "SELECT COUNT(*), COUNT(kind) FROM photos WHERE record_id = ?", (record_id,)).fetchone()
        return RecordStats(n, _ts(first) if first is not None else None, photos, classified)

    def import_old(self, path):
        """Carries over which channels were recorded by the old askbot database (the messages are read
        again from Discord). The old file is left as it is."""
        if not os.path.exists(path):
            return
        old = sqlite3.connect(path)
        try:
            rows = old.execute("SELECT channel_id, guild_id FROM channels").fetchall()
        except sqlite3.Error:
            rows = []
        finally:
            old.close()
        for channel_id, guild_id in rows:
            self.register(guild_id, channel_id)


# --- LLM: llama-server on this phone -----------------------------------------

# First in every request, unchanged, so llama-server can reuse its cached reading of it.
SYSTEM_PROMPT = (
    "あなたはDiscordサーバーの質問に答えるアシスタントです。日本語で、分かりやすく、親切に答えてください。"
    "資料が渡されたときは、資料に書かれていることだけを使って答えてください。"
    "資料に書かれていないことは「資料には書かれていません」と言ってください。"
    "分からないことを作り話で答えてはいけません。分からないときは、分からないと言ってください。"
)

MAX_ANSWER_TOKENS = 1500
_CONTEXT_MESSAGE = "写真や資料が大きすぎて読み切れませんでした。写真を減らすか、質問を短くしてください。"
_UNREACHABLE_MESSAGE = (
    "AIモデルにつながりません。アプリの「5. AIモデル」が準備完了になっているか確かめて、Botを起動し直してください。"
)


class LlmError(Exception):
    """An error whose message is shown to the person who asked, as is."""


class _NotReady(Exception):
    pass


def parse_sse(line):
    line = line.strip()
    if not line.startswith("data: ") or line == "data: [DONE]":
        return None
    return json.loads(line[6:])


def progress_percent(p):
    done = p.get("processed", 0) - p.get("cache", 0)
    total = p.get("total", 0) - p.get("cache", 0)
    return round(100 * done / max(1, total))


def image_part(data, content_type):
    encoded = base64.b64encode(data).decode("ascii")
    return {"type": "image_url", "image_url": {"url": f"data:{content_type};base64,{encoded}"}}


def _history_lines(rows):
    lines = []
    for r in sorted(rows, key=lambda r: r.created_at):
        when = r.created_at.astimezone(JST).strftime("%Y-%m-%d %H:%M")
        lines.append(f"[{when} {r.author}] {r.content}")
    return "\n".join(lines)


def build_messages(question, *, reply_text=None, images=(), wiki=None, history=None):
    parts = []
    if wiki:
        title, _, text = wiki
        parts.append(f"資料（Wikipedia「{title}」）:\n{text}")
    if history is not None:
        parts.append("資料（このサーバーの前の会話）:\n" + (_history_lines(history) or "（見つかりませんでした）"))
    if reply_text:
        parts.append(f"返信先のメッセージ:\n{reply_text}")
    parts.append(f"質問: {question}")
    text = "\n\n".join(parts)
    content = [*images, {"type": "text", "text": text}] if images else text
    return [{"role": "system", "content": SYSTEM_PROMPT}, {"role": "user", "content": content}]


async def stream_answer(session, base_url, messages, on_progress, on_text, *, api_key=None, wait_seconds=120):
    """Streams one answer. While llama-server is (re)starting it retries every 2 seconds,
    calling on_progress(-1), for up to wait_seconds."""
    body = {
        "messages": messages, "stream": True, "return_progress": True, "max_tokens": MAX_ANSWER_TOKENS,
        "temperature": 0.1, "top_k": 50, "top_p": 1.0, "min_p": 0.0, "repeat_penalty": 1.1,
    }
    headers = {"Authorization": f"Bearer {api_key}"} if api_key else {}
    timeout = aiohttp.ClientTimeout(total=None, sock_connect=10, sock_read=900)
    deadline = time.monotonic() + wait_seconds
    while True:
        started = False
        wrote = False
        try:
            async with session.post(base_url + "/v1/chat/completions", json=body, headers=headers,
                                    timeout=timeout) as r:
                if r.status == 503:
                    raise _NotReady()
                if r.status >= 400:
                    raise LlmError(_server_error(await r.text(), r.status))
                async for raw in r.content:
                    line = raw.decode("utf-8", "replace").strip()
                    if line.startswith("error:"):
                        raise LlmError(_server_error(line, None))
                    event = parse_sse(line)
                    if not event:
                        continue
                    started = True
                    if "error" in event:
                        raise LlmError(_server_error(json.dumps(event["error"], ensure_ascii=False), None))
                    if "prompt_progress" in event:
                        await on_progress(progress_percent(event["prompt_progress"]))
                    for choice in event.get("choices") or []:
                        piece = (choice.get("delta") or {}).get("content")
                        if piece:
                            wrote = True
                            await on_text(piece)
            if not wrote:
                raise LlmError("AIモデルから答えが返ってきませんでした。もう一度聞いてください。")
            return
        except (aiohttp.ClientError, asyncio.TimeoutError, _NotReady) as e:
            # A drop after reading began is reported, never retried: retrying would repeat the answer.
            if started:
                raise LlmError("答えている途中でAIモデルが止まりました。もう一度聞いてください。") from e
            if not isinstance(e, (aiohttp.ClientConnectionError, _NotReady)):
                raise LlmError("AIモデルとのやり取りに失敗しました。もう一度聞いてください。") from e
            if time.monotonic() >= deadline:
                raise LlmError(_UNREACHABLE_MESSAGE) from e
            await on_progress(-1)
            await asyncio.sleep(2)


@dataclass
class LlmReply:
    text: str
    tool_args: dict = None


async def llm_call(session, base_url, body, *, api_key=None, on_progress=None, wait_seconds=120):
    """One streamed request to llama-server: the answer text and, when the model called a tool, its
    arguments. While llama-server is (re)starting it retries every 2 seconds, calling on_progress(-1),
    for up to wait_seconds. Cancelling the caller closes the connection, which stops llama-server."""
    body = {**body, "stream": True, "return_progress": True}
    headers = {"Authorization": f"Bearer {api_key}"} if api_key else {}
    timeout = aiohttp.ClientTimeout(total=None, sock_connect=10, sock_read=900)
    deadline = time.monotonic() + wait_seconds

    async def progress(p):
        if on_progress is not None:
            await on_progress(p)

    while True:
        started = False
        text, arguments, called = [], [], False
        try:
            async with session.post(base_url + "/v1/chat/completions", json=body, headers=headers,
                                    timeout=timeout) as r:
                if r.status == 503:
                    raise _NotReady()
                if r.status >= 400:
                    raise LlmError(_server_error(await r.text(), r.status))
                async for raw in r.content:
                    line = raw.decode("utf-8", "replace").strip()
                    if line.startswith("error:"):
                        raise LlmError(_server_error(line, None))
                    event = parse_sse(line)
                    if not event:
                        continue
                    started = True
                    if "error" in event:
                        raise LlmError(_server_error(json.dumps(event["error"], ensure_ascii=False), None))
                    if "prompt_progress" in event:
                        await progress(progress_percent(event["prompt_progress"]))
                    for choice in event.get("choices") or []:
                        delta = choice.get("delta") or {}
                        if delta.get("content"):
                            text.append(delta["content"])
                        for call in delta.get("tool_calls") or []:
                            called = True
                            arguments.append((call.get("function") or {}).get("arguments") or "")
        except (aiohttp.ClientError, asyncio.TimeoutError, _NotReady) as e:
            # A drop after reading began is reported, never retried.
            if started:
                raise LlmError("答えている途中でAIモデルが止まりました。もう一度聞いてください。") from e
            if not isinstance(e, (aiohttp.ClientConnectionError, _NotReady)):
                raise LlmError("AIモデルとのやり取りに失敗しました。もう一度聞いてください。") from e
            if time.monotonic() >= deadline:
                raise LlmError(_UNREACHABLE_MESSAGE) from e
            await progress(-1)
            await asyncio.sleep(2)
            continue
        tool_args = None
        if called:
            try:
                tool_args = json.loads("".join(arguments) or "{}")
            except ValueError as e:
                raise LlmError("AIモデルの答えを読めませんでした。もう一度聞いてください。") from e
        if not text and tool_args is None:
            raise LlmError("AIモデルから答えが返ってきませんでした。もう一度聞いてください。")
        return LlmReply("".join(text), tool_args)


def _server_error(detail, status):
    if "context" in detail:
        return _CONTEXT_MESSAGE
    return f"AIモデルがエラーを返しました（{status}）" if status else "AIモデルがエラーを返しました"


# --- Wikipedia (free, no sign-up) --------------------------------------------

WIKI_API = "https://ja.wikipedia.org/w/api.php"
WIKI_MAX_CHARS = 3000
# A hung network must not hold the whole queue.
WIKI_TIMEOUT = 15
USER_AGENT = "PythonRunner-askbot/1.0 (https://github.com/kodai1350-alt/Android-Python)"


def pick_passages(text, keywords, max_chars=WIKI_MAX_CHARS):
    """The lead paragraph, then paragraphs mentioning a keyword, then the rest, in the article's
    order, up to max_chars. Detail first: the whole budget is used when the article has it."""
    paragraphs = [p.strip() for p in text.split("\n") if p.strip() and not p.strip().startswith("==")]
    if not paragraphs:
        return ""
    chosen = {0}
    used = len(paragraphs[0])
    hits = [i for i, p in enumerate(paragraphs) if i and any(k in p for k in keywords)]
    rest = [i for i in range(1, len(paragraphs)) if i not in hits]
    for i in hits + rest:
        if used + len(paragraphs[i]) > max_chars:
            continue
        chosen.add(i)
        used += len(paragraphs[i])
    return "\n".join(paragraphs[i] for i in sorted(chosen))


async def _wiki_query(session, params):
    """The API's "query" part; one retry when Wikipedia answers with an error (e.g. rate limits)."""
    params = {**params, "action": "query", "format": "json", "formatversion": 2}
    timeout = aiohttp.ClientTimeout(total=WIKI_TIMEOUT)
    for attempt in range(2):
        async with session.get(WIKI_API, params=params, headers={"User-Agent": USER_AGENT}, timeout=timeout) as r:
            data = await r.json(content_type=None)
        if "query" in data:
            return data["query"]
        log.warning("Wikipedia answered without results: %s", data.get("error", data))
        if attempt == 0:
            await asyncio.sleep(1)
    return None


async def wiki_lookup(session, keywords):
    """(title, url, passages) of the best-matching Japanese Wikipedia article, or None."""
    try:
        found = await _wiki_query(session, {"list": "search", "srsearch": " ".join(keywords), "srlimit": 1})
        if not found or not found.get("search"):
            return None
        title = found["search"][0]["title"]
        pages = await _wiki_query(session, {"prop": "extracts", "explaintext": 1, "redirects": 1, "titles": title})
        text = pages["pages"][0].get("extract", "") if pages else ""
    except (aiohttp.ClientError, asyncio.TimeoutError, KeyError, IndexError, ValueError):
        log.exception("Wikipedia lookup failed")
        return None
    url = "https://ja.wikipedia.org/wiki/" + urllib.parse.quote(title.replace(" ", "_"))
    return title, url, pick_passages(text, keywords)


# --- Discord ------------------------------------------------------------------

MAX_IMAGES = 2
MAX_IMAGE_BYTES = 8 * 1024 * 1024
DB_PATH = os.path.join(os.environ.get("HOME") or ".", "askbot-history.db")
RECORDS_PATH = os.path.join(os.environ.get("HOME") or ".", "tsumugi.db")

HELP_TEXT = (
    "メンションして質問してね。たとえば:\n"
    "・写真に返信して「@ぼく これ何？」\n"
    "・「@ぼく 量子コンピュータって何？」（Wikipediaで調べて答えます）\n"
    "・「@ぼく 先週ラーメン屋の話したけど、どこだっけ？」（前の会話を探します）\n"
    "前の会話を探せるようにするには、そのチャンネルで /記録する を実行してください。"
)
NO_MODEL_TEXT = "AIモデルが準備できていません（アプリの「5. AIモデル」でダウンロードしてから、Botを起動し直してください）"


def readable_channels(guild, member, registered):
    """Registered channels the asker may read: answers never quote a channel they cannot see."""
    readable = set()
    for channel_id in registered:
        channel = guild.get_channel(channel_id)
        if channel is None:
            continue
        perms = channel.permissions_for(member)
        if perms.view_channel and perms.read_message_history:
            readable.add(channel_id)
    return readable


def shareable_channels(guild, member, registered, answer_channel_id):
    """Channels whose history may appear in an answer posted to `answer_channel_id`.

    The answer is read by everyone in that channel, not just the asker, so a source must be readable
    by the asker and be either the answering channel itself or a channel everyone can read.
    """
    shareable = set()
    for channel_id in readable_channels(guild, member, registered):
        if channel_id == answer_channel_id:
            shareable.add(channel_id)
            continue
        perms = guild.get_channel(channel_id).permissions_for(guild.default_role)
        if perms.view_channel and perms.read_message_history:
            shareable.add(channel_id)
    return shareable


def image_refusal(parts, reason):
    """The reason to refuse outright when photos were sent but none could be read."""
    return reason if reason and not parts else None


def fallback_plan(plan, question):
    """A history question found nothing: if only a date made it one ("1月1日は何の日？"), answer it
    as an ordinary question instead. None when the person clearly meant past conversations."""
    if plan.mode != "history" or any(w in unicodedata.normalize("NFKC", question) for w in _HISTORY_WORDS):
        return None
    return Plan("wiki" if plan.keywords else "chat", plan.keywords)


async def verify_rows(rows, fetch, store):
    """Rows as they are on Discord now: deleted ones are dropped (and removed from the store), edited
    ones updated. Catches changes made while the bot was offline. `fetch(row)` returns the current
    text, or None when the message is gone."""
    current = []
    for row in rows:
        text = await fetch(row)
        if text is None:
            store.delete(row.message_id)
        elif text != row.content:
            row = Stored(row.message_id, row.guild_id, row.channel_id, row.author, text, row.created_at)
            store.upsert(row)
            current.append(row)
        else:
            current.append(row)
    return current


async def collect_images(messages):
    """Image parts from the messages' attachments (at most MAX_IMAGES), and why any were skipped."""
    parts, reasons = [], []
    for m in messages:
        for a in m.attachments:
            content_type = (a.content_type or "").split(";")[0]
            if not content_type.startswith("image/"):
                continue
            if a.size > MAX_IMAGE_BYTES:
                reasons.append("写真が大きすぎます（8MBまで）")
            elif len(parts) >= MAX_IMAGES:
                reasons.append(f"写真は最初の{MAX_IMAGES}枚だけ見ます")
            else:
                parts.append(image_part(await a.read(), content_type))
    reasons = list(dict.fromkeys(reasons))
    return parts, ("・".join(reasons) if reasons else None)


def sources_footer(plan, wiki, rows):
    if plan.mode == "wiki" and wiki:
        return f"📚 参考: <{wiki[1]}>"
    if plan.mode == "history" and rows:
        return "\n".join(f"🔗 <{jump_url(r)}>" for r in rows[:5])
    return ""


_URL = re.compile(r"https?://\S+")


def record_of(channel):
    """(record_id, place_id, private): threads and forum posts belong to their parent's record."""
    parent_id = getattr(channel, "parent_id", None)
    if parent_id is not None:
        return parent_id, channel.id, bool(channel.is_private())
    return channel.id, channel.id, False


def should_record(message):
    """Only what people wrote: never bots (紬 included) or webhooks."""
    return not message.author.bot and message.webhook_id is None


def to_record(message):
    """The message as a Msg, and its images as unclassified Photos."""
    record_id, place_id, private = record_of(message.channel)
    links = []
    for item in [*_URL.findall(message.content)] + [
            part for e in message.embeds for part in (e.url, e.title, e.description)]:
        if item and item not in links:
            links.append(item)
    msg = Msg(message.id, message.guild.id, record_id, place_id, private, message.author.display_name,
              message.clean_content, "\n".join(links), message.created_at)
    photos = [
        Photo(a.id, message.id, message.guild.id, record_id, place_id, private, message.author.display_name,
              message.created_at)
        for a in message.attachments if (a.content_type or "").startswith("image/")
    ]
    return msg, photos


async def _threads(channel):
    """The channel's threads: active ones, then archived public and (when readable) private ones."""
    seen = set()
    for t in list(getattr(channel, "threads", [])):
        seen.add(t.id)
        yield t
    for private in (False, True):
        try:
            async for t in channel.archived_threads(limit=None, private=private):
                if t.id not in seen:
                    seen.add(t.id)
                    yield t
        except discord.HTTPException:
            log.info("cannot list archived %s threads of %s", "private" if private else "public", channel.id)


async def backfill(store, channel):
    """Reads what the record is missing: each place (the channel, then its threads) from just after its
    newest stored message, or from the start. Returns how many messages were stored. Raises
    discord.Forbidden when the channel itself cannot be read; threads that cannot be read are skipped."""
    record_id = channel.id
    count = 0

    async def read(place):
        nonlocal count
        async for m in place.history(limit=None, after=store.latest(place.id), oldest_first=True):
            if not store.is_registered(record_id):
                return False
            if should_record(m):
                store.upsert(*to_record(m))
                count += 1
        return True

    if hasattr(channel, "history") and not await read(channel):
        return count
    async for thread in _threads(channel):
        try:
            if not await read(thread):
                return count
        except discord.Forbidden:
            log.info("cannot read thread %s", thread.id)
    return count


def _to_stored(message):
    return Stored(message.id, message.guild.id, message.channel.id, message.author.display_name,
                  message.clean_content, message.created_at)


class AskBot(discord.Client):
    def __init__(self):
        intents = discord.Intents.default()
        intents.message_content = True
        # Answers quote the model and other people's messages: never let them ping anyone.
        super().__init__(intents=intents, allowed_mentions=discord.AllowedMentions.none())
        self.tree = app_commands.CommandTree(self)
        self.queue = AskQueue()
        self.store = HistoryStore(DB_PATH)
        self.records = RecordStore(RECORDS_PATH)
        self.records.import_old(DB_PATH)
        self.llm_url = os.environ.get("LLM_URL")
        self.llm_key = os.environ.get("LLM_API_KEY")
        self.session = None
        self._tasks = set()

    def _spawn(self, coro):
        task = asyncio.create_task(coro)
        self._tasks.add(task)
        task.add_done_callback(self._tasks.discard)

    async def setup_hook(self):
        self.session = aiohttp.ClientSession()
        self._spawn(self.queue.run_forever())
        self._add_commands()
        await self.tree.sync()

    async def close(self):
        if self.session:
            await self.session.close()
        await super().close()

    async def on_ready(self):
        log.info("logged in as %s (LLM: %s)", self.user, "ready" if self.llm_url else "missing")
        # Catch up on messages posted while the bot was offline.
        for guild in self.guilds:
            for record_id in self.records.registered(guild.id):
                channel = guild.get_channel(record_id)
                if channel is not None:
                    self._spawn(self.read_back(channel, None))

    # --- recording ---

    def _add_commands(self):
        @app_commands.command(name="記録する", description="このチャンネルの会話と写真を記録して、あとで探せるようにします")
        @app_commands.guild_only()
        @app_commands.default_permissions(manage_channels=True)
        async def record(interaction: discord.Interaction):
            channel = interaction.channel
            channel = getattr(channel, "parent", None) or channel  # in a thread: record its channel
            self.records.register(interaction.guild_id, channel.id)
            await interaction.response.send_message(
                "記録を始めました。過去の会話をさかのぼって読み込みます（多いと数十分かかります）。"
                "写真の分類は、そのあと空き時間に少しずつ進めます。")
            self._spawn(self.read_back(channel, interaction.channel))

        @app_commands.command(name="記録やめる", description="このチャンネルの記録をやめて、保存した分を消します")
        @app_commands.guild_only()
        @app_commands.default_permissions(manage_channels=True)
        async def stop_recording(interaction: discord.Interaction):
            record_id, _, _ = record_of(interaction.channel)
            self.records.unregister(interaction.guild_id, record_id)
            await interaction.response.send_message("記録をやめて、保存していた分を消しました。")

        self.tree.add_command(record)
        self.tree.add_command(stop_recording)

    async def read_back(self, channel, announce_to):
        """backfill, telling `announce_to` (when given) how it went."""
        try:
            count = await backfill(self.records, channel)
        except discord.Forbidden:
            if announce_to is not None:
                await announce_to.send("このチャンネルの履歴を読む権限がありません（「メッセージ履歴を読む」を許可してください）。")
            return
        except Exception:
            log.exception("reading back %s failed", channel.id)
            return
        if announce_to is not None:
            await announce_to.send(f"📚 過去の会話を{count}件読み込みました。")

    def _record(self, message):
        if message.guild and should_record(message) and self.records.is_registered(record_of(message.channel)[0]):
            self.records.upsert(*to_record(message))

    async def on_message(self, message):
        if message.author.bot:
            return
        self._record(message)
        if self.user in message.mentions:
            await self.handle_question(message)

    async def on_raw_message_edit(self, payload):
        # Link previews usually arrive as an edit, so links are refreshed here too.
        m = getattr(payload, "message", None)
        if m is not None:
            self._record(m)

    async def on_raw_message_delete(self, payload):
        self.records.delete_message(payload.message_id)

    async def on_raw_bulk_message_delete(self, payload):
        for message_id in payload.message_ids:
            self.records.delete_message(message_id)

    # --- questions ---

    async def handle_question(self, message):
        text = message.content
        for mention in (f"<@{self.user.id}>", f"<@!{self.user.id}>"):
            text = text.replace(mention, "")
        text = text.strip()
        replying = bool(message.reference and message.reference.message_id)
        if not text and not message.attachments and not replying:
            await message.reply(HELP_TEXT, mention_author=False)
            return
        # Acknowledge first: downloading photos can take a while on mobile data.
        first = await message.reply("受け付けました", mention_author=False)
        if not self.llm_url:
            await first.edit(content=NO_MODEL_TEXT)
            return
        reply_to = None
        if replying:
            reply_to = message.reference.resolved
            if not isinstance(reply_to, discord.Message):
                try:
                    reply_to = await message.channel.fetch_message(message.reference.message_id)
                except discord.HTTPException:
                    reply_to = None
        images, image_note = await collect_images([message] + ([reply_to] if reply_to else []))
        refusal = image_refusal(images, image_note)
        if refusal:
            await first.edit(content=f"{refusal}。写真を小さくしてから、もう一度聞いてください。")
            return
        question = text or "これについて教えて"
        plan = plan_question(question, bool(images), datetime.now(JST))
        writer = ReplyWriter(first, message.channel.send)

        async def job():
            try:
                await self.answer(message, question, reply_to, images, image_note, plan, writer)
            except Exception:
                log.exception("answer failed")
                await writer.append("\n\n⚠ うまく答えられませんでした。もう一度聞いてください。")
                await writer.finish()

        async def on_position(n):
            await first.edit(content=f"順番待ち {n}番目です")

        position = self.queue.submit(message.author.id, job, on_position)
        if position is None:
            await first.edit(content="前の質問が終わるまで待ってね")
        elif position > 0:
            await on_position(position)

    async def _current_text(self, row):
        channel = self.get_channel(row.channel_id)
        if channel is None:
            return None
        try:
            return (await channel.fetch_message(row.message_id)).clean_content
        except discord.NotFound:
            return None
        except discord.HTTPException:
            return row.content

    async def answer(self, message, question, reply_to, images, image_note, plan, writer):
        notes = [image_note] if image_note else []
        wiki = rows = None
        if plan.mode == "history":
            await writer.status("🔎 前の会話を探しています…")
            channels = set()
            if message.guild:
                registered = self.store.registered(message.guild.id)
                channels = shareable_channels(message.guild, message.author, registered, message.channel.id)
            if channels:
                rows = self.store.search(message.guild.id, channels, plan.keywords, plan.since, plan.until)
                rows = await verify_rows(rows, self._current_text, self.store)
            if not rows:
                fallback = fallback_plan(plan, question)
                if fallback is None:
                    await writer.append(
                        "前の会話から見つかりませんでした。言葉や日付を変えて聞いてみてください。" if channels else
                        "ここから探せる記録がありません。探したいチャンネルで /記録する をしてください。")
                    await writer.finish()
                    return
                plan, rows = fallback, None
        if plan.mode == "wiki":
            await writer.status("📖 Wikipediaで調べています…")
            wiki = await wiki_lookup(self.session, plan.keywords)
            if wiki is None:
                notes.append("Wikipediaに記事が見つかりませんでした")

        messages = build_messages(
            question, reply_text=reply_to.clean_content if reply_to else None,
            images=images, wiki=wiki, history=rows,
        )
        kind = plan.mode if plan.mode in ("photo", "history", "wiki") else "question"

        async def on_progress(percent):
            await writer.status("⏳ AIモデルを起動中…" if percent < 0 else progress_text(kind, percent))

        try:
            await stream_answer(self.session, self.llm_url, messages, on_progress, writer.append, api_key=self.llm_key)
        except LlmError as e:
            await writer.append(f"\n\n⚠ {e}")
        footer = "\n".join(filter(None, [sources_footer(plan, wiki, rows), *(f"※ {n}" for n in notes)]))
        await writer.finish(footer)


def main():
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    token = os.environ.get("TOKEN")
    if not token:
        print("TOKEN がありません。アプリの「4. 秘密の値」に TOKEN（Botのトークン）を入れて保存してください。", file=sys.stderr)
        sys.exit(1)
    AskBot().run(token, log_handler=None)


if __name__ == "__main__":
    main()
