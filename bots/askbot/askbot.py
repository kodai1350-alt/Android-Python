"""askbot: a Discord bot that answers @mentions with the LLM running on this phone.

Run it in Python Runner. It needs:
  - TOKEN        the Discord bot token (アプリの「秘密の値」)
  - LLM_URL      set by the app while the AI model is downloaded
  - LLM_API_KEY  set by the app together with LLM_URL
"""
import asyncio
import logging
import re
import sqlite3
import time
import unicodedata
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone

log = logging.getLogger("askbot")

# --- Replies: splitting and throttled edits ---------------------------------

DISCORD_LIMIT = 2000
EDIT_INTERVAL = 1.5

_PROGRESS = {
    "wiki": "📖 Wikipediaを読んでいます… {p}%",
    "photo": "🖼 写真を見ています… {p}%",
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


def parse_date_range(text, now):
    span, _ = _find_dates(unicodedata.normalize("NFKC", text), now)
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
        with self._db:
            self._db.execute(
                "INSERT INTO messages VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT(message_id) DO UPDATE SET "
                "author = excluded.author, content = excluded.content",
                (s.message_id, s.guild_id, s.channel_id, s.author, s.content, s.created_at.timestamp()),
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
