"""Throwaway spike: can LFM3-VL-2B on the phone read search requests, and how long does a check take?

Run it in Python Runner like askbot (it needs the AI model, not a Discord token).
Copy the log after "SPIKE DONE". Delete this file after the spike (plan Task 11).
"""
import json
import os
import time
import urllib.error
import urllib.request

URL = os.environ.get("LLM_URL")
KEY = os.environ.get("LLM_API_KEY")

PHOTO_KINDS = ["料理", "風景", "スクショ", "人物", "ペット・動物", "乗り物", "買い物・物", "文書・メモ", "その他"]

SYSTEM_PROMPT = (
    "あなたは紬（つむぎ）です。Discordの過去の会話・URL・写真を探す手伝いだけをします。"
    "頼みを読んで、必ず search ツールを1回呼んでください。"
    "探しもの以外の頼み（あいさつ、ふつうの質問など）は kind を other にしてください。"
)

SEARCH_TOOL = {
    "type": "function",
    "function": {
        "name": "search",
        "description": "Discordの記録から探す条件を決める",
        "parameters": {
            "type": "object",
            "properties": {
                "kind": {
                    "type": "string", "enum": ["message", "link", "photo", "other"],
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

RELEVANT_SCHEMA = {
    "type": "object",
    "properties": {"relevant": {"type": "array", "items": {"type": "integer"}}},
    "required": ["relevant"],
}

REQUESTS = [
    ("Eラーニングの話で半年前ぐらいに貼ってたURLどれ？", "link / Eラーニング / 半年前ぐらい"),
    ("半月前のラーメンの写真", "photo / ラーメン / 半月前 / 料理"),
    ("先週、旅行の日程の話してたのどこ？", "message / 旅行,日程 / 先週"),
    ("去年の夏に誰かが送ってた海の写真ある？", "photo / 海 / 去年の夏 / 風景"),
    ("この前教えてもらった英語の勉強サイト", "link / 英語,勉強 / この前"),
    ("3日前ぐらいにバイトのシフトの話したよね", "message / バイト,シフト / 3日前ぐらい"),
    ("猫の写真見せて", "photo / 猫 / - / ペット・動物"),
    ("最近貼られたYouTubeのリンク", "link / YouTube / 最近"),
    ("おはよう", "other"),
    ("量子コンピュータって何？", "other"),
    ("10月14日の会話", "message / - / 10月14日"),
    ("スクショで送ったエラーの画面どれだっけ", "photo / エラー / - / スクショ"),
]

CANDIDATES = [
    "4月18日 百鬼: https://dotinstall.com/ ドットインストール プログラミング学習サービス",
    "4月20日 kodai: 今日の晩ごはん何にする？",
    "4月25日 百鬼: 明日は雨らしいよ",
    "5月2日 kodai: https://schoo.jp/ Schoo 社会人向けオンライン学習",
    "5月3日 百鬼: ゲームのイベント始まったね",
    "5月5日 kodai: 電車遅れてる",
    "5月9日 百鬼: Eラーニングの課題、締め切り延びたって",
    "5月12日 kodai: https://www.youtube.com/watch?v=abc 猫の動画",
    "5月15日 百鬼: 新しいスマホ買った",
    "5月20日 kodai: バイトのシフト出た",
]


def post(body):
    req = urllib.request.Request(
        URL + "/v1/chat/completions", data=json.dumps(body).encode(),
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {KEY}"},
    )
    for _ in range(60):
        try:
            with urllib.request.urlopen(req, timeout=900) as r:
                return json.loads(r.read())
        except urllib.error.HTTPError as e:
            if e.code != 503:
                raise
        except urllib.error.URLError:
            pass
        time.sleep(2)
    raise RuntimeError("llama-server did not start")


def main():
    if not URL:
        print("LLM_URL がありません。アプリの「5. AIモデル」を準備完了にしてから動かしてください。", flush=True)
        return
    print("SPIKE START", flush=True)
    for i, (text, expected) in enumerate(REQUESTS, 1):
        start = time.monotonic()
        try:
            r = post({
                "messages": [{"role": "system", "content": SYSTEM_PROMPT}, {"role": "user", "content": text}],
                "tools": [SEARCH_TOOL], "tool_choice": "required", "temperature": 0.1, "max_tokens": 200,
            })
            msg = r["choices"][0]["message"]
            calls = msg.get("tool_calls") or []
            got = calls[0]["function"]["arguments"] if calls else "NO TOOL CALL: " + (msg.get("content") or "")
        except Exception as e:
            got = f"ERROR {e!r}"
        print(f"[{i}] {time.monotonic() - start:.1f}s 「{text}」\n    expected: {expected}\n    got: {got}", flush=True)

    lines = "\n".join(f"{i}. {c}" for i, c in enumerate(CANDIDATES, 1))
    start = time.monotonic()
    try:
        r = post({
            "messages": [
                {"role": "system", "content": SYSTEM_PROMPT},
                {"role": "user", "content": f"頼み: {REQUESTS[0][0]}\n\n候補:\n{lines}\n\n"
                                            "頼みに当てはまる候補の番号だけを relevant に入れてください。"},
            ],
            "response_format": {"type": "json_schema", "json_schema": {"schema": RELEVANT_SCHEMA}},
            "temperature": 0.1, "max_tokens": 100,
        })
        got = r["choices"][0]["message"].get("content")
    except Exception as e:
        got = f"ERROR {e!r}"
    print(f"[check] {time.monotonic() - start:.1f}s expected: 1,4,7 got: {got}", flush=True)
    print("SPIKE DONE", flush=True)


if __name__ == "__main__":
    main()
