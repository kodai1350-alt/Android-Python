import asyncio
import json
import socket

import pytest
from aiohttp import ClientSession, web

from askbot import LlmError, LlmReply, llm_call


def free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


async def start_app(port, handler):
    app = web.Application()
    app.router.add_post("/v1/chat/completions", handler)
    runner = web.AppRunner(app)
    await runner.setup()
    await web.TCPSite(runner, "127.0.0.1", port).start()
    return runner


def sse(events, seen=None):
    async def handler(request):
        if seen is not None:
            seen.append((request.headers.get("Authorization"), await request.json()))
        resp = web.StreamResponse()
        await resp.prepare(request)
        for e in events:
            await resp.write(("data: " + json.dumps(e) + "\n\n").encode())
        await resp.write(b"data: [DONE]\n\n")
        return resp

    return handler


def tool_delta(arguments):
    return {"choices": [{"delta": {"tool_calls": [{"index": 0, "function": {"arguments": arguments}}]}}]}


async def call(handler, body=None, **kwargs):
    port = free_port()
    runner = await start_app(port, handler)
    try:
        async with ClientSession() as s:
            return await llm_call(s, f"http://127.0.0.1:{port}", body or {"messages": []}, **kwargs)
    finally:
        await runner.cleanup()


def test_llm_call_collects_text():
    seen, progress = [], []

    async def on_progress(p):
        progress.append(p)

    events = [
        {"choices": [], "prompt_progress": {"total": 100, "cache": 0, "processed": 50}},
        {"choices": [{"delta": {"content": "こん"}}]},
        {"choices": [{"delta": {"content": "にちは"}}]},
    ]
    reply = asyncio.run(call(sse(events, seen), {"messages": [], "temperature": 0.1},
                             api_key="k", on_progress=on_progress))
    assert reply == LlmReply("こんにちは", None)
    assert progress == [50]
    auth, body = seen[0]
    assert auth == "Bearer k"
    assert body["stream"] is True and body["return_progress"] is True and body["temperature"] == 0.1


def test_llm_call_collects_tool_arguments_across_chunks():
    events = [
        {"choices": [{"delta": {"tool_calls": [{"index": 0, "id": "c1", "type": "function",
                                                "function": {"name": "search", "arguments": ""}}]}}]},
        tool_delta('{"kind":'),
        tool_delta('"photo"}'),
    ]
    assert asyncio.run(call(sse(events))).tool_args == {"kind": "photo"}


def test_llm_call_bad_tool_json_is_llm_error():
    with pytest.raises(LlmError):
        asyncio.run(call(sse([tool_delta('{"kind": ')])))


def test_llm_call_empty_answer_is_error():
    with pytest.raises(LlmError):
        asyncio.run(call(sse([])))


def test_llm_call_waits_for_server():
    async def run():
        port, progress = free_port(), []

        async def on_progress(p):
            progress.append(p)

        async def late_start():
            await asyncio.sleep(3)
            return await start_app(port, sse([{"choices": [{"delta": {"content": "あ"}}]}]))

        starter = asyncio.create_task(late_start())
        async with ClientSession() as s:
            reply = await llm_call(s, f"http://127.0.0.1:{port}", {"messages": []},
                                   on_progress=on_progress, wait_seconds=10)
        await (await starter).cleanup()
        assert -1 in progress
        assert reply.text == "あ"

    asyncio.run(run())


def test_llm_call_context_error():
    async def handler(request):
        body = {"error": {"message": "the request exceeds the available context size, try increasing it"}}
        return web.json_response(body, status=400)

    with pytest.raises(LlmError) as e:
        asyncio.run(call(handler))
    assert str(e.value).startswith("写真や資料が大きすぎて読み切れませんでした")


def test_llm_call_error_event_becomes_llm_error():
    async def handler(request):
        resp = web.StreamResponse()
        await resp.prepare(request)
        await resp.write(("error: " + json.dumps({"error": {"message": "boom"}}) + "\n\n").encode())
        return resp

    with pytest.raises(LlmError):
        asyncio.run(call(handler))


def test_llm_call_mid_stream_drop_becomes_llm_error():
    async def handler(request):
        resp = web.StreamResponse()
        resp.content_length = 100000
        await resp.prepare(request)
        await resp.write(("data: " + json.dumps({"choices": [{"delta": {"content": "あ"}}]}) + "\n\n").encode())
        request.transport.close()
        return resp

    with pytest.raises(LlmError) as e:
        asyncio.run(call(handler))
    assert "途中でAIモデルが止まりました" in str(e.value)


def test_llm_call_cancel_closes_connection():
    async def run():
        port = free_port()
        started, closed = asyncio.Event(), asyncio.Event()

        async def handler(request):
            resp = web.StreamResponse()
            await resp.prepare(request)
            try:
                for i in range(200):
                    event = {"choices": [], "prompt_progress": {"total": 200, "cache": 0, "processed": i}}
                    await resp.write(("data: " + json.dumps(event) + "\n\n").encode())
                    await asyncio.sleep(0.05)
            except (ConnectionResetError, asyncio.CancelledError):
                closed.set()
                raise
            return resp

        async def on_progress(p):
            started.set()

        runner = await start_app(port, handler)
        try:
            async with ClientSession() as s:
                task = asyncio.create_task(
                    llm_call(s, f"http://127.0.0.1:{port}", {"messages": []}, on_progress=on_progress))
                await asyncio.wait_for(started.wait(), 5)
                task.cancel()
                with pytest.raises(asyncio.CancelledError):
                    await task
            await asyncio.wait_for(closed.wait(), 5)
        finally:
            await runner.cleanup()

    asyncio.run(run())
