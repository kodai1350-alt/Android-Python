"""Throwaway spike: how fast can LFM3-VL-2B answer on this phone?

Downloads the model once, starts llama-server and measures the time until the
first characters of an answer appear (the "書き始め" goal is under 1 minute).
"""
import base64
import json
import os
import shutil
import subprocess
import threading
import time
import urllib.parse
import urllib.request

UA = "PythonRunner-LLM-spike/0.1 (https://github.com/kodai1350-alt/Android-Python)"
REPO = "https://huggingface.co/LiquidAI/LFM3-VL-2B-GGUF/resolve/main/"
MODEL = ("LFM3-VL-2B-QAD-Q4_0.gguf", 1049615648)
MMPROJ = ("mmproj-LFM3-VL-2B-Q8_0.gguf", 203031872)
PHOTO = "https://commons.wikimedia.org/wiki/Special:FilePath/Shoyu_Ramen.jpg?width=640"
PORT = 18089
GOAL_SECONDS = 60

BASE = os.path.dirname(os.environ["HOME"])
MODELS = os.path.join(BASE, "llm-models")
PID_FILE = os.path.join(MODELS, "server.pid")
SERVER_LOG = os.path.join(MODELS, "server.log")


def say(*args):
    print(*args, flush=True)


def find_bin(name):
    for d in os.environ.get("LD_LIBRARY_PATH", "").split(":"):
        p = os.path.join(d, name)
        if os.path.exists(p):
            return p
    raise SystemExit(f"{name} が見つかりません")


def device_info():
    hw = "?"
    with open("/proc/cpuinfo") as f:
        for line in f:
            if line.startswith("Hardware"):
                hw = line.split(":", 1)[1].strip()
    mem = {}
    with open("/proc/meminfo") as f:
        for line in f:
            k, v = line.split(":", 1)
            mem[k] = int(v.split()[0]) // 1024
    free = shutil.disk_usage(BASE).free // (1024 * 1024)
    say(f"■ 端末: CPU {hw}・{os.cpu_count()}コア / メモリ {mem.get('MemTotal')}MB（空き {mem.get('MemAvailable')}MB）/ 保存領域の空き {free}MB")


def download(name, size):
    path = os.path.join(MODELS, name)
    if os.path.exists(path) and os.path.getsize(path) == size:
        say(f"■ {name}: ダウンロード済み")
        return path
    part = path + ".part"
    have = os.path.getsize(part) if os.path.exists(part) else 0
    if shutil.disk_usage(MODELS).free < size - have + 200 * 1024 * 1024:
        raise SystemExit("保存領域が足りません（1.5GBほど空けてください）")
    req = urllib.request.Request(REPO + name, headers={"User-Agent": UA, "Range": f"bytes={have}-"})
    with urllib.request.urlopen(req, timeout=60) as r:
        if r.status != 206:
            have = 0
        say(f"■ {name} をダウンロード中（{size // (1024 * 1024)}MB）")
        with open(part, "ab" if have else "wb") as f:
            last = -1
            while chunk := r.read(1024 * 1024):
                f.write(chunk)
                have += len(chunk)
                pct = have * 100 // size
                if pct // 5 != last:
                    last = pct // 5
                    say(f"  {pct}%")
    if os.path.getsize(part) != size:
        raise SystemExit("ダウンロードが途中で切れました。もう一度押すと続きから再開します")
    os.replace(part, path)
    return path


def kill_old_server():
    try:
        with open(PID_FILE) as f:
            os.kill(int(f.read()), 9)
    except (OSError, ValueError):
        pass


def bench(model, threads):
    """Raw text speed with llama-bench: reading a prompt and writing tokens."""
    say(f"■ 文字の速さを測定中（スレッド {threads}）…")
    out = subprocess.run(
        [find_bin("libllama_bench.so"), "-m", model, "-p", "256", "-n", "32", "-r", "1", "-t", str(threads), "-o", "json"],
        capture_output=True, text=True, timeout=900,
    )
    if out.returncode != 0:
        say(out.stderr[-1500:])
        raise SystemExit("llama-bench が失敗しました")
    rows = json.loads(out.stdout)
    pp = next(r["avg_ts"] for r in rows if r["n_prompt"] > 0)
    tg = next(r["avg_ts"] for r in rows if r["n_gen"] > 0)
    say(f"  読む速さ {pp:.1f} トークン/秒・書く速さ {tg:.1f} トークン/秒")
    return pp, tg


def start_server(model, mmproj, threads):
    say("■ LLMサーバーを起動中…")
    log = open(SERVER_LOG, "w")
    proc = subprocess.Popen(
        [find_bin("libllama_server.so"), "-m", model, "--mmproj", mmproj,
         "--host", "127.0.0.1", "--port", str(PORT), "-c", "8192", "-np", "2",
         "-t", str(threads), "--reasoning", "off", "--no-webui"],
        stdout=log, stderr=subprocess.STDOUT,
    )
    with open(PID_FILE, "w") as f:
        f.write(str(proc.pid))
    t0 = time.time()
    while time.time() - t0 < 600:
        if proc.poll() is not None:
            log.close()
            with open(SERVER_LOG) as f:
                say(f.read()[-2000:])
            raise SystemExit("LLMサーバーが起動できませんでした")
        try:
            with urllib.request.urlopen(f"http://127.0.0.1:{PORT}/health", timeout=5) as r:
                if r.status == 200:
                    say(f"  起動しました（モデルの読み込み {time.time() - t0:.0f}秒）")
                    return proc
        except OSError:
            pass
        time.sleep(1)
    proc.kill()
    raise SystemExit("LLMサーバーの起動が10分たっても終わりません")


def ask(messages, max_tokens=200):
    """Streams one answer; returns (seconds to first text, seconds total, tokens/s while writing, text)."""
    body = json.dumps({
        "messages": messages, "stream": True, "max_tokens": max_tokens,
        "temperature": 0.1, "top_k": 50, "top_p": 1.0, "min_p": 0.0, "repeat_penalty": 1.1,
        "timings_per_token": False,
    }).encode()
    req = urllib.request.Request(
        f"http://127.0.0.1:{PORT}/v1/chat/completions", data=body,
        headers={"Content-Type": "application/json"},
    )
    t0 = time.time()
    first = None
    text = []
    timings = {}
    with urllib.request.urlopen(req, timeout=1800) as r:
        for raw in r:
            line = raw.decode("utf-8", "replace").strip()
            if not line.startswith("data: ") or line == "data: [DONE]":
                continue
            data = json.loads(line[6:])
            timings = data.get("timings", timings)
            for choice in data.get("choices", []):
                piece = choice.get("delta", {}).get("content") or ""
                if piece and first is None and piece.strip():
                    first = time.time() - t0
                text.append(piece)
    total = time.time() - t0
    return first or total, total, timings.get("predicted_per_second", 0.0), "".join(text).strip()


def report(title, result):
    first, total, tps, text = result
    mark = "OK" if first <= GOAL_SECONDS else "NG"
    say(f"  [{mark}] 書き始め {first:.1f}秒 / 書き終わり {total:.1f}秒 / 書く速さ {tps:.1f} トークン/秒")
    say("  答え: " + text.replace("\n", " ")[:300])
    return title, first


def wikipedia(title):
    q = urllib.parse.urlencode({
        "action": "query", "prop": "extracts", "explaintext": 1,
        "titles": title, "format": "json", "formatversion": 2,
    })
    req = urllib.request.Request("https://ja.wikipedia.org/w/api.php?" + q, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.load(r)["query"]["pages"][0]["extract"][:1500]


def photo_url():
    req = urllib.request.Request(PHOTO, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=60) as r:
        return "data:image/jpeg;base64," + base64.b64encode(r.read()).decode()


def main():
    os.makedirs(MODELS, exist_ok=True)
    kill_old_server()
    device_info()
    model = download(*MODEL)
    mmproj = download(*MMPROJ)

    cores = os.cpu_count() or 4
    candidates = sorted({4, cores})
    speeds = {t: bench(model, t) for t in candidates}
    threads = max(candidates, key=lambda t: speeds[t][1])
    say(f"  → スレッド {threads} を使います")

    server = start_server(model, mmproj, threads)
    results = []
    try:
        short = [{"role": "user", "content": "ラーメンはどこの国から来た料理？3文で教えて。"}]
        say("■ テスト1: 短い質問（文字だけ）")
        results.append(report("短い質問", ask(short)))

        say("■ テスト2: Wikipediaを読んでから答える")
        doc = wikipedia("ラーメン")
        say(f"  Wikipediaから{len(doc)}文字を取得")
        grounded = [
            {"role": "system", "content": "次の資料だけを使って、日本語で分かりやすく答えてください。\n\n資料:\n" + doc},
            {"role": "user", "content": "ラーメンはいつ、どこから日本に伝わった？"},
        ]
        results.append(report("Wikipedia", ask(grounded)))

        say("■ テスト3: 写真について質問")
        image = photo_url()
        photo = [{"role": "user", "content": [
            {"type": "image_url", "image_url": {"url": image}},
            {"type": "text", "text": "この写真には何が写っていますか？日本語で説明して。"},
        ]}]
        results.append(report("写真", ask(photo)))

        say("■ テスト4: 2人が同時に質問（文字＋写真）")
        both = {}
        threads_ = [
            threading.Thread(target=lambda: both.__setitem__("文字", ask(short))),
            threading.Thread(target=lambda: both.__setitem__("写真", ask(photo))),
        ]
        for th in threads_:
            th.start()
            time.sleep(2)
        for th in threads_:
            th.join()
        for key in ("文字", "写真"):
            say(f"  ({key})")
            results.append(report(f"同時・{key}", both[key]))
    finally:
        server.kill()
        server.wait()
        try:
            os.remove(PID_FILE)
        except OSError:
            pass

    say("")
    say("===== 結果まとめ（書き始めまでの秒数・目標1分以内）=====")
    for title, first in results:
        say(f"  {'OK' if first <= GOAL_SECONDS else 'NG'}  {title}: {first:.1f}秒")
    say("この「作業の出力」を全部コピーして送ってください。")


main()
