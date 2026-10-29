import asyncio

import askbot
from askbot import ReplyWriter, progress_text, split_message


class FakeMessage:
    def __init__(self, content=""):
        self.content = content
        self.edits = []

    async def edit(self, content):
        self.content = content
        self.edits.append(content)


class FakeClock:
    def __init__(self):
        self.now = 0.0

    def __call__(self):
        return self.now


# --- Task 5: splitting and the reply writer ---------------------------------

def test_split_message_prefers_newlines():
    first, second = "あ" * 1990, "い" * 50
    assert split_message(first + "\n" + second) == [first, second]


def test_split_message_without_newlines():
    text = "x" * 4500
    parts = split_message(text)
    assert [len(p) for p in parts] == [2000, 2000, 500]
    assert "".join(parts) == text


def test_progress_text_clamps():
    assert progress_text("wiki", 130) == "📖 Wikipediaを読んでいます… 100%"
    assert progress_text("photo", -5) == "🖼 写真を見ています… 0%"


def test_reply_writer_throttles():
    clock = FakeClock()
    first = FakeMessage("受け付けました")

    async def send_more(text):
        raise AssertionError("should fit in one message")

    async def run():
        w = ReplyWriter(first, send_more, clock=clock)
        for i in range(10):
            clock.now = i * 0.1
            await w.append(str(i))
        assert len(first.edits) == 1
        await w.finish()
        assert first.content == "0123456789"

    asyncio.run(run())


def test_reply_writer_overflows_to_new_message():
    clock = FakeClock()
    first = FakeMessage()
    extra = []

    async def send_more(text):
        m = FakeMessage(text)
        extra.append(m)
        return m

    async def run():
        w = ReplyWriter(first, send_more, clock=clock)
        await w.append("y" * 2500)
        await w.finish("📚 参考")
        assert len(extra) == 1
        assert first.content + extra[0].content.removesuffix("\n\n📚 参考") == "y" * 2500
        assert extra[0].content.endswith("📚 参考")

    asyncio.run(run())


def test_reply_writer_status_until_text():
    clock = FakeClock()
    first = FakeMessage()

    async def run():
        w = ReplyWriter(first, None, clock=clock)
        await w.status(progress_text("wiki", 40))
        assert first.content == "📖 Wikipediaを読んでいます… 40%"
        clock.now = 5
        await w.append("答え")
        assert first.content == "答え"

    asyncio.run(run())
