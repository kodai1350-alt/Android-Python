"""askbot (紬): a Discord bot that finds past messages, links and photos when @mentioned.

The LLM on this phone reads the request and checks the candidates; photos are classified in idle time.

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
from dataclasses import dataclass, replace
from datetime import datetime, timedelta, timezone

import aiohttp
import discord
from discord import app_commands

log = logging.getLogger("askbot")

# --- Queue: one question at a time -----------------------------------------

class AskQueue:
    """Runs one question at a time, in order; each person may have only one question queued.

    The phone reads only one prompt at a time (reading stalls everything else), so answers are
    never interleaved and never cut short for the next person.
    """

    def __init__(self, idle=None, idle_backoff=60):
        self._waiting = []
        self._running_user = None
        self._wake = asyncio.Event()
        # Background work (photo classification) run while nobody is waiting; a question cancels it.
        self._idle = idle
        self._idle_backoff = idle_backoff

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
                if self._idle is None:
                    await self._wake.wait()
                else:
                    await self._run_idle()
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

    async def _run_idle(self):
        """One round of idle work, cut short the moment a question arrives."""
        idle = asyncio.create_task(self._idle())
        wake = asyncio.create_task(self._wake.wait())
        try:
            await asyncio.wait({idle, wake}, return_when=asyncio.FIRST_COMPLETED)
        finally:
            for task in (idle, wake):
                task.cancel()
            failed = False
            for task in (idle, wake):
                try:
                    await task
                except asyncio.CancelledError:
                    pass
                except Exception:
                    log.exception("idle work failed")
                    failed = True
        if failed:
            # Never spin on a failure that repeats; a question still ends the wait at once.
            try:
                await asyncio.wait_for(self._wake.wait(), self._idle_backoff)
            except asyncio.TimeoutError:
                pass


# --- Questions: which kind, which words, which dates ------------------------

JST = timezone(timedelta(hours=9))

_STOP_WORDS = {"今日", "昨日", "先週", "今週", "先月", "今月", "去年", "何日", "何月", "写真", "画像", "名前", "何", "前"}
# Runs of katakana, kanji (U+4E00–U+9FFF) or letters/digits: the nouns of a Japanese question, roughly.
_KEYWORD = re.compile(r"[ァ-ヶー]{2,}|[一-鿿々]{2,}|[A-Za-z0-9]{2,}")
_MONTH_DAY = re.compile(r"(\d{1,2})月(\d{1,2})日")
_MONTH = re.compile(r"(\d{1,2})月")


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
CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY, value TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS backfill(place_id INTEGER PRIMARY KEY, record_id INTEGER NOT NULL, read_to REAL NOT NULL);
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

def _like(word):
    """A LIKE pattern matching `word` anywhere; '!' is the escape character (ESCAPE '!')."""
    return "%" + word.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%"


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
            self._db.execute("DELETE FROM backfill WHERE record_id = ?", (record_id,))

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

    def read_to(self, place_id):
        """How far backfill has read this place (live messages do not count), or None."""
        row = self._db.execute("SELECT read_to FROM backfill WHERE place_id = ?", (place_id,)).fetchone()
        return _ts(row[0]) if row else None

    def mark_read(self, record_id, place_id, created_at):
        with self._db:
            self._db.execute(
                "INSERT INTO backfill SELECT ?, ?, ? WHERE EXISTS (SELECT 1 FROM records WHERE record_id = ?) "
                "ON CONFLICT(place_id) DO UPDATE SET read_to = MAX(read_to, excluded.read_to)",
                (place_id, record_id, created_at.timestamp(), record_id))

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
        again from Discord). Runs once: a channel unregistered later must not come back on restart.
        The old file is left as it is."""
        if self._db.execute("SELECT 1 FROM meta WHERE key = 'imported_old'").fetchone() or not os.path.exists(path):
            return
        with self._db:
            self._db.execute("INSERT INTO meta VALUES ('imported_old', '1')")
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
    "あなたは紬（つむぎ）です。Discordの過去の会話・URL・写真を探す手伝いだけをします。"
    "頼みを読んで、必ず search ツールを1回呼んでください。"
    "探しもの以外の頼み（あいさつ、ふつうの質問など）は kind を other にしてください。"
)

# --- Understanding a request: what to look for, which words, when -----------

PHOTO_KINDS = ["料理", "風景", "スクショ", "人物", "ペット・動物", "乗り物", "買い物・物", "文書・メモ", "その他"]
KINDS = ("message", "link", "photo", "other")
# Whether the model's kind wins over rule_kind (decided by the phone spike, plan Task 1).
TRUST_MODEL_KIND = True

SEARCH_TOOL = {
    "type": "function",
    "function": {
        "name": "search",
        "description": "Discordの記録から探す条件を決める",
        "parameters": {
            "type": "object",
            "properties": {
                "kind": {
                    "type": "string", "enum": list(KINDS),
                    "description": "message=会話、link=URLやリンク、photo=写真や画像、other=探しもの以外",
                },
                "words": {
                    "type": "array", "items": {"type": "string"},
                    "description": "探す言葉。頼みの中の大事な言葉と、似た言葉や言いかえを3つまで。"
                                   "時期の言葉や「写真」「URL」は入れない",
                },
                "when": {
                    "type": "string",
                    "description": "時期の言葉を頼みのまま（例: 半年前ぐらい、先週、去年の夏）。なければ空",
                },
                "photo_kind": {
                    "type": "string", "enum": PHOTO_KINDS + [""],
                    "description": "写真の種類でしぼるとき。なければ空",
                },
            },
            "required": ["kind", "words"],
        },
    },
}

_PHOTO_WORDS = ("写真", "画像", "スクショ", "フォト", "pic")
_LINK_WORDS = ("URL", "url", "リンク", "サイト", "ページ", "http")
_NOT_WORDS = {"写真", "画像", "スクショ", "URL", "url", "リンク", "サイト", "ページ", "会話", "どれ", "どこ"}


@dataclass
class Query:
    kind: str  # "message" | "link" | "photo" | "other"
    words: list
    since: datetime
    until: datetime
    photo_kind: str = None
    when_text: str = None


def _plain_words(text, now):
    """The question's own nouns, without time words or words like 写真 and URL."""
    return [w for w in extract_keywords(text)
            if w not in _NOT_WORDS and parse_when(w, now) == (None, None)]


def rule_kind(text, now=None):
    """The kind by fixed words: the fallback when the model's answer cannot be used."""
    text = unicodedata.normalize("NFKC", text)
    if any(w in text for w in _PHOTO_WORDS):
        return "photo"
    if any(w in text for w in _LINK_WORDS):
        return "link"
    now = now or datetime.now(JST)
    if not _plain_words(text, now) and parse_when(text, now) == (None, None):
        return "other"
    return "message"


async def understand(session, base_url, question, now, *, api_key=None):
    """What the person is looking for. The model picks the kind and words; dates always come from
    parse_when (a small model is unreliable at date arithmetic)."""
    body = {
        "messages": [{"role": "system", "content": SYSTEM_PROMPT}, {"role": "user", "content": question}],
        "tools": [SEARCH_TOOL], "tool_choice": "required", "temperature": 0.1, "max_tokens": 200,
    }
    args = (await llm_call(session, base_url, body, api_key=api_key)).tool_args or {}
    kind = args.get("kind")
    if not TRUST_MODEL_KIND or kind not in KINDS:
        kind = rule_kind(question, now)
    words = []
    for w in args.get("words") or []:
        if isinstance(w, str) and w.strip() and w.strip() not in words:
            words.append(w.strip())
    words = words or _plain_words(question, now)
    when = args.get("when") if isinstance(args.get("when"), str) and args.get("when").strip() else None
    since, until = parse_when(when, now) if when else (None, None)
    if since is None and until is None:
        since, until = parse_when(question, now)
    photo_kind = args.get("photo_kind") if args.get("photo_kind") in PHOTO_KINDS else None
    return Query(kind, words, since, until, photo_kind, when)


# --- Checking candidates: precision first -------------------------------------

# Candidates gathered, and how many the model reads per request (decided by the phone spike, plan Task 1).
MAX_CANDIDATES = 20
CHECK_BATCH = 10
RELEVANT_SCHEMA = {
    "type": "object",
    "properties": {"relevant": {"type": "array", "items": {"type": "integer"}}},
    "required": ["relevant"],
}


def _short_date(dt):
    dt = dt.astimezone(JST)
    return f"{dt.month}月{dt.day}日"


def candidate_line(i, item):
    if isinstance(item, Photo):
        text = f"{item.kind} {item.description}"
    else:
        text = f"{item.content} {item.links}".replace("\n", " ").strip()
    return f"{i}. {_short_date(item.created_at)} {item.author}: {text[:80]}"


async def check_candidates(session, base_url, question, items, *, api_key=None, on_progress=None):
    """The candidates the model judges to be what was asked for, in their original order, and whether
    the check ran. When the model cannot be asked, every candidate comes back unchecked."""
    kept = []
    try:
        for start in range(0, len(items), CHECK_BATCH):
            batch = items[start:start + CHECK_BATCH]
            if on_progress is not None:
                await on_progress(start, len(items))
            lines = "\n".join(candidate_line(i, item) for i, item in enumerate(batch, 1))
            body = {
                "messages": [
                    {"role": "system", "content": SYSTEM_PROMPT},
                    {"role": "user", "content": f"頼み: {question}\n\n候補:\n{lines}\n\n"
                                                "頼みに当てはまる候補の番号だけを relevant に入れてください。"},
                ],
                "response_format": {"type": "json_schema", "json_schema": {"schema": RELEVANT_SCHEMA}},
                "temperature": 0.1, "max_tokens": 100,
            }
            reply = await llm_call(session, base_url, body, api_key=api_key)
            relevant = json.loads(reply.text).get("relevant")
            if not isinstance(relevant, list):
                raise ValueError("no relevant list")
            numbers = {n for n in relevant if isinstance(n, int)}
            kept += [item for i, item in enumerate(batch, 1) if i in numbers]
    except (LlmError, ValueError, AttributeError):
        log.exception("could not check candidates")
        return items, False
    return kept, True


# --- Classifying photos in idle time -------------------------------------------

CLASS_SCHEMA = {
    "type": "object",
    "properties": {"kind": {"type": "string", "enum": PHOTO_KINDS}, "description": {"type": "string"}},
    "required": ["kind", "description"],
}
CLASS_PROMPT = (
    "この写真の種類を選び、写っているものを日本語80文字までで説明してください。"
    "写真に文字が写っていれば、その文字も説明に入れてください。"
)
IDLE_SLEEP = 60
# What llama.cpp can decode on the phone (stb_image); WebP, HEIC and AVIF are not.
READABLE_IMAGES = ("image/jpeg", "image/png", "image/gif", "image/bmp")
MAX_TRIES = 3


async def classify(session, base_url, data, content_type, *, api_key=None):
    """(kind, description) of one photo. An answer that does not fit becomes その他."""
    body = {
        "messages": [
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": [image_part(data, content_type), {"type": "text", "text": CLASS_PROMPT}]},
        ],
        "response_format": {"type": "json_schema", "json_schema": {"schema": CLASS_SCHEMA}},
        "temperature": 0.1, "max_tokens": 200,
    }
    reply = await llm_call(session, base_url, body, api_key=api_key)
    try:
        answer = json.loads(reply.text)
        kind, description = answer.get("kind"), answer.get("description")
    except (ValueError, AttributeError):
        kind, description = None, None
    if kind not in PHOTO_KINDS:
        kind = "その他"
    return kind, (description if isinstance(description, str) else "").strip()[:80]


class PhotoClassifier:
    """Classifies the newest unclassified photo, one per run_once. Meant as AskQueue's idle work: a
    question cancels it mid-photo, and that photo simply stays unclassified until the next round."""

    def __init__(self, store, fetch_image, classify, sleep=asyncio.sleep):
        self._store = store
        # photo -> (bytes, content_type); bytes None when too big to download; None when deleted.
        self._fetch = fetch_image
        self._classify = classify  # (bytes, content_type) -> (kind, description)
        self._sleep = sleep
        self._failures = {}  # attachment_id -> times the model failed on it

    async def run_once(self):
        photo = self._store.next_unclassified()
        if photo is None:
            await self._sleep(IDLE_SLEEP)
            return
        try:
            image = await self._fetch(photo)
            if image is None:
                self._store.delete_message(photo.message_id)
                return
            data, content_type = image
            if data is None or len(data) > MAX_IMAGE_BYTES:
                self._store.save_class(photo.attachment_id, "その他", "（大きすぎて見られませんでした）")
                return
            if content_type not in READABLE_IMAGES:
                self._store.save_class(photo.attachment_id, "その他", "（この形式の写真は見られませんでした）")
                return
            kind, description = await self._classify(data, content_type)
        except discord.Forbidden:
            # Never readable again: do not let it block every photo behind it.
            self._store.save_class(photo.attachment_id, "その他", "（見られませんでした）")
            return
        except LlmUnreachable:
            await self._sleep(IDLE_SLEEP)  # the model is down, not this photo's fault
            return
        except LlmError:
            # The model failed on this photo: after a few tries, give up so the photos behind it move on.
            tries = self._failures.get(photo.attachment_id, 0) + 1
            log.exception("could not classify photo %s (try %d)", photo.attachment_id, tries)
            if tries >= MAX_TRIES:
                self._failures.pop(photo.attachment_id, None)
                self._store.save_class(photo.attachment_id, "その他", "（見られませんでした）")
                return
            self._failures[photo.attachment_id] = tries
            await self._sleep(IDLE_SLEEP)
            return
        except (discord.HTTPException, aiohttp.ClientError, asyncio.TimeoutError):
            log.exception("could not fetch photo %s; trying again later", photo.attachment_id)
            await self._sleep(IDLE_SLEEP)
            return
        self._store.save_class(photo.attachment_id, kind, description)

_CONTEXT_MESSAGE = "写真や資料が大きすぎて読み切れませんでした。写真を減らすか、質問を短くしてください。"
_UNREACHABLE_MESSAGE = (
    "AIモデルにつながりません。アプリの「5. AIモデル」が準備完了になっているか確かめて、Botを起動し直してください。"
)


class LlmError(Exception):
    """An error whose message is shown to the person who asked, as is."""


class LlmUnreachable(LlmError):
    """llama-server did not answer at all (starting up or stopped): worth retrying later."""


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
                raise LlmUnreachable(_UNREACHABLE_MESSAGE) from e
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


# --- Cards (Components V2) ------------------------------------------------------

BLUE, YELLOW, GREEN = 0x5865F2, 0xF0B232, 0x23A55A
PAGE_SIZE = 5
UNCHECKED_NOTE = "-# ※ AIモデルで確かめられなかったので、言葉が当たった順に並べています"


@dataclass
class SearchResult:
    items: list  # Msg or Photo, best first
    checked: bool  # the model confirmed them
    thumbs: dict  # attachment_id -> a fresh image URL


def jump_link(guild_id, place_id, message_id):
    return f"https://discord.com/channels/{guild_id}/{place_id}/{message_id}"


def _date(dt, now):
    dt = dt.astimezone(JST)
    return (f"{dt.month}月{dt.day}日" if dt.year == now.astimezone(JST).year
            else f"{dt.year}年{dt.month}月{dt.day}日")


def period_text(since, until, now):
    if since is None and until is None:
        return "全期間"
    start = _date(since, now) if since else ""
    end = _date(until - timedelta(days=1), now) if until else ""
    return f"{start}〜{end}"


def _card(color, *items):
    view = discord.ui.LayoutView(timeout=None)
    view.add_item(discord.ui.Container(*items, accent_color=color))
    return view


def status_card(text):
    view = discord.ui.LayoutView(timeout=None)
    view.add_item(discord.ui.Container(discord.ui.TextDisplay(text)))
    return view


def _item_text(n, item, now):
    head = f"**{n}**　{_date(item.created_at, now)}　{item.author}"
    if isinstance(item, Photo):
        return f"{head}\n{item.kind} ・ {item.description}"
    lines = [head]
    if item.links:
        links = item.links.split("\n")
        urls = [x for x in links if x.startswith("http")]
        titles = [x for x in links if not x.startswith("http")]
        lines += urls[:1] + [f"-# {t[:100]}" for t in titles[:1]]
    if item.content and not (item.links and item.content.strip() in item.links):
        lines.append(item.content.replace("\n", " ")[:100])
    return "\n".join(lines)


def results_card(query, title, place_name, result, page, *, now=None):
    """One page of results: a header, PAGE_SIZE items each with a way back to the original message,
    and buttons for more. Interactive parts carry custom_ids ("more", "kind") for the caller to wire."""
    now = now or datetime.now(JST)
    photos = query.kind == "photo"
    items = result.items[page * PAGE_SIZE:(page + 1) * PAGE_SIZE]
    parts = [
        discord.ui.TextDisplay(f"### {'🖼' if photos else '🔎'} {title}"),
        discord.ui.TextDisplay(f"-# #{place_name} の記録から ・ {period_text(query.since, query.until, now)}"
                               f" ・ {len(result.items)}件見つかりました"),
    ]
    opens = []
    for i, item in enumerate(items):
        n = page * PAGE_SIZE + i + 1
        link = jump_link(item.guild_id, item.place_id, item.message_id)
        thumb = result.thumbs.get(item.attachment_id) if photos else None
        accessory = (discord.ui.Thumbnail(thumb) if thumb
                     else discord.ui.Button(label="元の発言へ", url=link))
        if thumb:
            opens.append(discord.ui.Button(label=f"{n} を開く", url=link))
        parts += [discord.ui.Separator(), discord.ui.Section(_item_text(n, item, now), accessory=accessory)]
    if opens:
        parts.append(discord.ui.ActionRow(*opens))
    if (page + 1) * PAGE_SIZE < len(result.items):
        parts.append(discord.ui.ActionRow(discord.ui.Button(label="もっと見る", custom_id="more")))
    if photos:
        parts.append(discord.ui.ActionRow(discord.ui.Select(
            custom_id="kind", placeholder="種類でしぼる",
            options=[discord.SelectOption(label=k, value=k) for k in PHOTO_KINDS])))
    if not result.checked:
        parts.append(discord.ui.TextDisplay(UNCHECKED_NOTE))
    return _card(YELLOW if photos else BLUE, *parts)


def not_found_card(query, place_name, *, now=None):
    now = now or datetime.now(JST)
    words = "、".join(query.words) or "（なし）"
    parts = [
        discord.ui.TextDisplay("### 🔎 見つかりませんでした"),
        discord.ui.TextDisplay(f"-# #{place_name} の記録から ・ 言葉: {words} ・ "
                               f"期間: {period_text(query.since, query.until, now)}"),
    ]
    if query.since is not None or query.until is not None:
        parts.append(discord.ui.ActionRow(discord.ui.Button(label="期間を広げて探す", custom_id="widen")))
    return _card(YELLOW if query.kind == "photo" else BLUE, *parts)


HELP_LINES = (
    "### 紬は探しもの専門です\n"
    "メンションして、覚えていることをそのまま書いてね。たとえば:\n"
    "・@紬 Eラーニングの話で半年前ぐらいに貼ってたURLどれ？\n"
    "・@紬 半月前のラーメンの写真\n"
    "・@紬 先週、旅行の日程の話してたのどこ？"
)


def help_card():
    return _card(BLUE, discord.ui.TextDisplay(HELP_LINES), discord.ui.TextDisplay(
        "-# 探せるのは、/記録する をしたチャンネル（スレッドも含む）の会話と写真です。"))


def record_card(place_name, stats):
    since = f"（{stats.since.astimezone(JST).year}年{stats.since.astimezone(JST).month}月〜）" if stats.since else ""
    lines = [f"会話　**{stats.messages:,}件**{since}",
             f"写真　**{stats.classified:,} / {stats.photos:,}枚** 分類ずみ"]
    if stats.classified < stats.photos:
        lines[-1] += "　-# 残りは空き時間に進めます"
    return _card(GREEN, discord.ui.TextDisplay(f"### 📁 #{place_name} の記録"), discord.ui.Separator(),
                 discord.ui.TextDisplay("\n".join(lines)))


# --- Discord ------------------------------------------------------------------

MAX_IMAGE_BYTES = 8 * 1024 * 1024
DB_PATH = os.path.join(os.environ.get("HOME") or ".", "askbot-history.db")
RECORDS_PATH = os.path.join(os.environ.get("HOME") or ".", "tsumugi.db")

NO_MODEL_TEXT = "AIモデルが準備できていません（アプリの「5. AIモデル」でダウンロードしてから、Botを起動し直してください）"
NOT_RECORDED_TEXT = "このチャンネルは記録していません。/記録する で始められます"
SEARCHING_TEXT = "🔎 探しています…"
NO_HISTORY_TEXT = "このチャンネルの履歴を読む権限がないので、探せません"
FAILED_TEXT = "⚠ うまく探せませんでした。もう一度聞いてください。"
VIEW_TIMEOUT = 900


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
    # A text channel lists public and private archived threads separately; a forum (no history of its
    # own) has only public posts and its archived_threads() takes no `private`.
    kinds = ({}, {"private": True}) if hasattr(channel, "history") else ({},)
    for kind in kinds:
        private = bool(kind)
        try:
            async for t in channel.archived_threads(limit=None, **kind):
                if t.id not in seen:
                    seen.add(t.id)
                    yield t
        except discord.HTTPException:
            log.info("cannot list archived %s threads of %s", "private" if private else "public", channel.id)


async def backfill(store, channel):
    """Reads what the record is missing: each place (the channel, then its threads) from where backfill
    last stopped there, or from the start. Messages stored live meanwhile do not move that point. Returns how many messages were stored. Raises
    discord.Forbidden when the channel itself cannot be read; threads that cannot be read are skipped."""
    record_id = channel.id
    count = 0

    async def read(place):
        nonlocal count
        async for m in place.history(limit=None, after=store.read_to(place.id), oldest_first=True):
            if not store.is_registered(record_id):
                return False
            if should_record(m):
                store.upsert(*to_record(m))
                count += 1
            store.mark_read(record_id, place.id, m.created_at)
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


# --- Searching -------------------------------------------------------------------

def can_read_history(channel, member):
    """Whether `member` may read this channel's past messages: 紬 must not show them anything more."""
    perms = channel.permissions_for(member)
    return perms.view_channel and perms.read_message_history


async def verify_items(items, fetch_message, store):
    """Items as they are on Discord now: deleted ones dropped (and forgotten), edited ones updated, and
    for photos a fresh image URL (old attachment URLs expire). Catches changes made while the bot was
    offline. When Discord cannot be asked right now, the item is kept as stored."""
    kept, thumbs = [], {}
    for item in items:
        try:
            message = await fetch_message(item)
        except discord.HTTPException:
            kept.append(item)
            continue
        if message is None:
            store.delete_message(item.message_id)
            continue
        msg, photos = to_record(message)
        store.upsert(msg, photos)
        if isinstance(item, Photo):
            image = next((a for a in message.attachments if a.id == item.attachment_id), None)
            if image is not None:  # else removed by an edit (upsert forgot it)
                thumbs[item.attachment_id] = image.url
                kept.append(item)
        else:
            kept.append(replace(item, content=msg.content, links=msg.links))
    return kept, thumbs


async def run_search(query, store, record_id, place_id, verify, check):
    """Candidates from the record, as they are now on Discord, kept only if the model agrees."""
    if query.kind == "photo":
        items = store.search_photos(record_id, place_id, query.words, query.photo_kind, query.since, query.until,
                                    MAX_CANDIDATES)
    else:
        items = store.search_messages(record_id, place_id, query.words, query.since, query.until,
                                      query.kind == "link", MAX_CANDIDATES)
    items, thumbs = await verify(items)
    if not items:
        return SearchResult([], True, {})
    items, checked = await check(items)
    return SearchResult(items, checked, thumbs)


def search_title(query):
    """A heading such as "Eラーニングの話・半年前ぐらい"."""
    words = "、".join(query.words[:2])
    what = {"photo": "の写真", "link": "のURL"}.get(query.kind, "の話")
    title = (words + what) if words else what[1:]
    if query.photo_kind:
        title += f"（{query.photo_kind}）"
    return f"{title}・{query.when_text}" if query.when_text else title


def without_controls(view):
    """The card with its buttons and menus removed (link buttons stay): for when they stop working."""
    for container in view.children:
        for child in list(getattr(container, "children", [])):
            if isinstance(child, discord.ui.ActionRow) and any(
                    getattr(c, "custom_id", None) and not getattr(c, "url", None) for c in child.children):
                container.remove_item(child)
    return view


class Search:
    """One person's search, from understanding the request to the result card and its buttons."""

    def __init__(self, bot, message, question, record_id, place_id, place_name, channel):
        self.bot = bot
        self.channel = channel  # where it was asked: whoever presses a button must be able to read it
        self.message = message  # 紬's reply, edited as the search goes on
        self.question = question
        self.record_id = record_id
        self.place_id = place_id
        self.place_name = place_name
        self.query = None
        self.result = None
        self.page = 0
        self.view = None

    async def _show(self, view):
        if self.view is not None:
            self.view.stop()  # an old card must not time out over the new one
        self.view = view
        await self.message.edit(view=view)

    async def start(self):
        try:
            self.query = await understand(self.bot.session, self.bot.llm_url, self.question, datetime.now(JST),
                                          api_key=self.bot.llm_key)
        except LlmError as e:
            await self._show(status_card(f"⚠ {e}"))
            return
        if self.query.kind == "other":
            await self._show(help_card())
            return
        await self.run()

    async def run(self):
        try:
            await self._show(status_card(SEARCHING_TEXT))

            async def on_progress(done, total):
                await self.message.edit(view=status_card(f"🔎 {total}件の候補を確かめています…"))

            async def check(items):
                return await check_candidates(self.bot.session, self.bot.llm_url, self.question, items,
                                              api_key=self.bot.llm_key, on_progress=on_progress)

            self.result = await run_search(self.query, self.bot.records, self.record_id, self.place_id,
                                           self.bot._verify, check)
            self.page = 0
            await self._show(self._card())
        except Exception:
            log.exception("search failed")
            await self._show(status_card(FAILED_TEXT))

    def _build(self):
        if not self.result.items:
            return not_found_card(self.query, self.place_name)
        return results_card(self.query, search_title(self.query), self.place_name, self.result, self.page)

    def _card(self):
        """The card with its buttons wired; after VIEW_TIMEOUT they are taken off the message."""
        view = self._build()
        for item in view.walk_children():
            callback = {"more": self.on_more, "kind": self.on_kind, "widen": self.on_widen}.get(
                getattr(item, "custom_id", None))
            if callback is not None:
                item.callback = callback
        view.timeout = VIEW_TIMEOUT
        view.on_timeout = lambda: self.message.edit(view=without_controls(self._build()))
        return view

    async def _allowed(self, interaction):
        if can_read_history(self.channel, interaction.user):
            return True
        await interaction.response.send_message(NO_HISTORY_TEXT, ephemeral=True)
        return False

    async def on_more(self, interaction):
        if not await self._allowed(interaction):
            return
        self.page += 1
        view = self._card()
        if self.view is not None:
            self.view.stop()
        self.view = view
        await interaction.response.edit_message(view=view)

    async def on_kind(self, interaction):
        if not await self._allowed(interaction):
            return
        self.query = replace(self.query, photo_kind=interaction.data["values"][0])
        await self._again(interaction)

    async def on_widen(self, interaction):
        if not await self._allowed(interaction):
            return
        since, until = self.query.since, self.query.until
        if since is not None and until is not None:
            half = (until - since) / 2
            since, until = since - half, until + half
        self.query = replace(self.query, since=since, until=until)
        await self._again(interaction)

    async def _again(self, interaction):
        """Searches again through the queue: checking candidates takes the phone a while."""

        async def on_position(n):
            await self.message.edit(view=status_card(f"順番待ち {n}番目です"))

        position = self.bot.queue.submit(interaction.user.id, self.run, on_position)
        if position is None:
            await interaction.response.send_message("前の質問が終わるまで待ってね", ephemeral=True)
            return
        if self.view is not None:
            self.view.stop()
        self.view = None
        text = f"順番待ち {position}番目です" if position else SEARCHING_TEXT
        await interaction.response.edit_message(view=status_card(text))


class AskBot(discord.Client):
    def __init__(self):
        intents = discord.Intents.default()
        intents.message_content = True
        # Answers quote the model and other people's messages: never let them ping anyone.
        super().__init__(intents=intents, allowed_mentions=discord.AllowedMentions.none())
        self.tree = app_commands.CommandTree(self)
        self.records = RecordStore(RECORDS_PATH)
        self.records.import_old(DB_PATH)
        self.llm_url = os.environ.get("LLM_URL")
        self.llm_key = os.environ.get("LLM_API_KEY")
        self.session = None
        self._tasks = set()
        self.classifier = PhotoClassifier(self.records, self._fetch_image, self._classify)
        self.queue = AskQueue(idle=self.classifier.run_once if self.llm_url else None)

    def _spawn(self, coro):
        task = asyncio.create_task(coro)
        self._tasks.add(task)
        task.add_done_callback(self._tasks.discard)

    async def setup_hook(self):
        self.session = aiohttp.ClientSession()
        self._spawn(self.queue.run_forever())
        self._add_commands()
        await self.tree.sync()

    async def _classify(self, data, content_type):
        return await classify(self.session, self.llm_url, data, content_type, api_key=self.llm_key)

    async def _fetch_image(self, photo):
        """The photo as it is on Discord now, or None when its message or the image is gone."""
        channel = self.get_channel(photo.place_id)
        try:
            if channel is None:
                channel = await self.fetch_channel(photo.place_id)
            message = await channel.fetch_message(photo.message_id)
        except discord.NotFound:
            return None
        for a in message.attachments:
            if a.id == photo.attachment_id:
                if a.size > MAX_IMAGE_BYTES:
                    return None, a.content_type  # too big: never downloaded
                return await a.read(), (a.content_type or "image/jpeg").split(";")[0]
        return None

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

        @app_commands.command(name="記録の様子", description="このチャンネルの記録と、写真の分類がどこまで進んだかを見ます")
        @app_commands.guild_only()
        @app_commands.default_permissions(manage_channels=True)
        async def record_status(interaction: discord.Interaction):
            channel = interaction.channel
            channel = getattr(channel, "parent", None) or channel
            if not self.records.is_registered(channel.id):
                await interaction.response.send_message(view=status_card(NOT_RECORDED_TEXT))
                return
            await interaction.response.send_message(view=record_card(channel.name, self.records.stats(channel.id)))

        self.tree.add_command(record)
        self.tree.add_command(stop_recording)
        self.tree.add_command(record_status)

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

    async def _fetch_message(self, item):
        """The item's message as it is on Discord now, or None when it was deleted."""
        channel = self.get_channel(item.place_id)
        try:
            if channel is None:
                channel = await self.fetch_channel(item.place_id)
            return await channel.fetch_message(item.message_id)
        except discord.NotFound:
            return None

    async def _verify(self, items):
        return await verify_items(items, self._fetch_message, self.records)

    async def handle_question(self, message):
        text = message.content
        for mention in (f"<@{self.user.id}>", f"<@!{self.user.id}>"):
            text = text.replace(mention, "")
        text = text.strip()
        if not message.guild or not text:
            await message.reply(view=help_card(), mention_author=False)
            return
        record_id, place_id, _ = record_of(message.channel)
        if not self.records.is_registered(record_id):
            await message.reply(view=status_card(NOT_RECORDED_TEXT), mention_author=False)
            return
        if not can_read_history(message.channel, message.author):
            await message.reply(view=status_card(NO_HISTORY_TEXT), mention_author=False)
            return
        first = await message.reply(view=status_card(SEARCHING_TEXT), mention_author=False)
        if not self.llm_url:
            await first.edit(view=status_card(NO_MODEL_TEXT))
            return
        parent = getattr(message.channel, "parent", None) or message.channel
        search = Search(self, first, text, record_id, place_id, parent.name, message.channel)

        async def on_position(n):
            await first.edit(view=status_card(f"順番待ち {n}番目です"))

        position = self.queue.submit(message.author.id, search.start, on_position)
        if position is None:
            await first.edit(view=status_card("前の質問が終わるまで待ってね"))
        elif position > 0:
            await on_position(position)


def main():
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    token = os.environ.get("TOKEN")
    if not token:
        print("TOKEN がありません。アプリの「4. 秘密の値」に TOKEN（Botのトークン）を入れて保存してください。", file=sys.stderr)
        sys.exit(1)
    AskBot().run(token, log_handler=None)


if __name__ == "__main__":
    main()
