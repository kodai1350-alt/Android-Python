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
        try:
            async with session.post(base_url + "/v1/chat/completions", json=body, headers=headers,
                                    timeout=timeout) as r:
                if r.status == 503:
                    raise _NotReady()
                if r.status >= 400:
                    detail = await r.text()
                    if "context" in detail:
                        raise LlmError(_CONTEXT_MESSAGE)
                    raise LlmError(f"AIモデルがエラーを返しました（{r.status}）")
                async for raw in r.content:
                    event = parse_sse(raw.decode("utf-8", "replace"))
                    if not event:
                        continue
                    started = True
                    if "prompt_progress" in event:
                        await on_progress(progress_percent(event["prompt_progress"]))
                    for choice in event.get("choices") or []:
                        piece = (choice.get("delta") or {}).get("content")
                        if piece:
                            await on_text(piece)
                return
        except (aiohttp.ClientConnectionError, _NotReady):
            if started:
                raise LlmError("答えている途中でAIモデルが止まりました。もう一度聞いてください。")
            if time.monotonic() >= deadline:
                raise LlmError(_UNREACHABLE_MESSAGE)
            await on_progress(-1)
            await asyncio.sleep(2)


# --- Wikipedia (free, no sign-up) --------------------------------------------

WIKI_API = "https://ja.wikipedia.org/w/api.php"
WIKI_MAX_CHARS = 3000
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
    for attempt in range(2):
        async with session.get(WIKI_API, params=params, headers={"User-Agent": USER_AGENT}) as r:
            data = await r.json(content_type=None)
        if "query" in data:
            return data["query"]
        log.warning("Wikipedia answered without results: %s", data.get("error", data))
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
BACKFILL_DAYS = 365
DB_PATH = os.path.join(os.environ.get("HOME") or ".", "askbot-history.db")

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


def _to_stored(message):
    return Stored(message.id, message.guild.id, message.channel.id, message.author.display_name,
                  message.clean_content, message.created_at)


class AskBot(discord.Client):
    def __init__(self):
        intents = discord.Intents.default()
        intents.message_content = True
        super().__init__(intents=intents)
        self.tree = app_commands.CommandTree(self)
        self.queue = AskQueue()
        self.store = HistoryStore(DB_PATH)
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
            for channel_id in self.store.registered(guild.id):
                channel = guild.get_channel(channel_id)
                if channel is not None:
                    self._spawn(self.backfill(channel, announce=False))

    # --- recording ---

    def _add_commands(self):
        @app_commands.command(name="記録する", description="このチャンネルの会話を記録して、あとで探せるようにします")
        @app_commands.guild_only()
        @app_commands.default_permissions(manage_channels=True)
        async def record(interaction: discord.Interaction):
            self.store.register(interaction.guild_id, interaction.channel_id)
            await interaction.response.send_message(
                f"記録を始めました。過去{BACKFILL_DAYS}日分を読み込みます（少し時間がかかります）。")
            self._spawn(self.backfill(interaction.channel, announce=True))

        @app_commands.command(name="記録やめる", description="このチャンネルの記録をやめて、保存した分を消します")
        @app_commands.guild_only()
        @app_commands.default_permissions(manage_channels=True)
        async def stop_recording(interaction: discord.Interaction):
            self.store.unregister(interaction.guild_id, interaction.channel_id)
            await interaction.response.send_message("記録をやめて、保存していた分を消しました。")

        self.tree.add_command(record)
        self.tree.add_command(stop_recording)

    async def backfill(self, channel, announce):
        since = self.store.latest(channel.id) or datetime.now(timezone.utc) - timedelta(days=BACKFILL_DAYS)
        count = 0
        try:
            async for m in channel.history(limit=None, after=since, oldest_first=True):
                if not self.store.is_registered(channel.id):
                    return
                if not m.author.bot:
                    self.store.upsert(_to_stored(m))
                    count += 1
        except discord.Forbidden:
            if announce:
                await channel.send("このチャンネルの履歴を読む権限がありません（「メッセージ履歴を読む」を許可してください）。")
            return
        if announce:
            await channel.send(f"📚 過去の会話を{count}件読み込みました。")

    async def on_message(self, message):
        if message.author.bot:
            return
        if message.guild and self.store.is_registered(message.channel.id):
            self.store.upsert(_to_stored(message))
        if self.user in message.mentions:
            await self.handle_question(message)

    async def on_raw_message_edit(self, payload):
        m = getattr(payload, "message", None)
        if m is not None and m.guild and not m.author.bot and self.store.is_registered(m.channel.id):
            self.store.upsert(_to_stored(m))

    async def on_raw_message_delete(self, payload):
        self.store.delete(payload.message_id)

    async def on_raw_bulk_message_delete(self, payload):
        for message_id in payload.message_ids:
            self.store.delete(message_id)

    # --- questions ---

    async def handle_question(self, message):
        text = message.content
        for mention in (f"<@{self.user.id}>", f"<@!{self.user.id}>"):
            text = text.replace(mention, "")
        text = text.strip()
        reply_to = None
        if message.reference and message.reference.message_id:
            reply_to = message.reference.resolved
            if not isinstance(reply_to, discord.Message):
                try:
                    reply_to = await message.channel.fetch_message(message.reference.message_id)
                except discord.HTTPException:
                    reply_to = None
        images, image_note = await collect_images([message] + ([reply_to] if reply_to else []))
        if not text and not images and reply_to is None:
            await message.reply(HELP_TEXT, mention_author=False)
            return
        question = text or "これについて教えて"
        plan = plan_question(question, bool(images), datetime.now(JST))

        first = await message.reply("受け付けました", mention_author=False)
        if not self.llm_url:
            await first.edit(content=NO_MODEL_TEXT)
            return
        writer = ReplyWriter(first, message.channel.send)

        async def job():
            await self.answer(message, question, reply_to, images, image_note, plan, writer)

        async def on_position(n):
            await first.edit(content=f"順番待ち {n}番目です")

        position = self.queue.submit(message.author.id, job, on_position)
        if position is None:
            await first.edit(content="前の質問が終わるまで待ってね")
        elif position > 0:
            await on_position(position)

    async def answer(self, message, question, reply_to, images, image_note, plan, writer):
        notes = [image_note] if image_note else []
        wiki = rows = None
        if plan.mode == "history":
            await writer.status("🔎 前の会話を探しています…")
            channels = set()
            if message.guild:
                registered = self.store.registered(message.guild.id)
                channels = readable_channels(message.guild, message.author, registered)
            if not channels:
                await writer.append("このサーバーには、探せる記録がありません。探したいチャンネルで /記録する をしてください。")
                await writer.finish()
                return
            rows = self.store.search(message.guild.id, channels, plan.keywords, plan.since, plan.until)
            if not rows:
                await writer.append("前の会話から見つかりませんでした。言葉や日付を変えて聞いてみてください。")
                await writer.finish()
                return
        elif plan.mode == "wiki":
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
