"""askbot: a Discord bot that answers @mentions with the LLM running on this phone.

Run it in Python Runner. It needs:
  - TOKEN        the Discord bot token (アプリの「秘密の値」)
  - LLM_URL      set by the app while the AI model is downloaded
  - LLM_API_KEY  set by the app together with LLM_URL
"""
import asyncio
import logging
import time

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
