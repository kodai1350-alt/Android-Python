# Bot づくりのルール（Python Runner 用）

> このファイルを、Bot を作り始める前に AI（Claude など）へそのまま送ってください。
> 「〈 〉」の部分だけ、作る Bot に合わせて書き換えます。

---

これから作る Discord Bot は、最後に **Android スマホのアプリ「Python Runner」** で 24 時間動かします。
PC で作って試し、最後に ZIP にしてスマホへ移します。次のルールを最初から守って作ってください。

- Bot の名前：〈例：入退室音〉
- ZIP の名前：〈Bot の名前〉-Android.zip

## 0. 作業のしかた

- **Git は使わない**。`git init`・`git add`・`git commit`・`git push`、GitHub へのアップロードはしない。
  作業はこの PC のフォルダの中のファイルを、直接作ったり直したりするだけにする。
- 大きく書き換える前に元に戻したくなりそうなときは、Git の代わりにファイルをコピーして残す（例：`main.py.bak`）。
  ただし ZIP には入れないので、最後に消してよいか聞く。
- このフォルダの外のファイルは、頼まれない限り作らない・変えない。

## 1. フォルダの形

```
〈Bot の名前〉/
├─ main.py              ← 最初に実行するファイル（必ずこの名前で、いちばん上に置く）
├─ requirements.txt     ← 使うライブラリの一覧（いちばん上に置く）
├─ .env                 ← トークンなどの値（自分のスマホ用。人には渡さない）
├─ .env.example         ← .env の見本（値は空にする）
├─ README.md            ← 使い方（日本語）
├─ tools/make_android_zip.py  ← 最後に ZIP を作るスクリプト（6 章）
├─ data/                ← Bot が書くデータ（JSON・音声など）
└─ （そのほかのコード：cogs/ など）
```

- **最初に実行するファイルの名前は、どの Bot でも必ず `main.py`**（全部の Bot でそろえるため）。
  `bot.py`・`start.py`・`run.py`・`app.py` など、ほかの名前にはしない。いちばん上に置く `.py` は `main.py` だけにする
  （ほかのコードは `cogs/` などのフォルダに入れる）。アプリは `main.py` を自動で「最初に実行」に選ぶ。
- `python main.py` だけで全部が起動するようにする。手で別のプログラムを立ち上げる手順は作らない。
- ファイル名・フォルダ名の**大文字・小文字を、import の書き方と必ずそろえる**（Android は区別する。Windows は区別しない）。
- `__pycache__`・`.git`・`venv`・`.venv`・`node_modules` は、アプリが取り込まないので、なくても動くこと。

## 2. 動く環境（スマホ側）

- Python は **3.14**。仮想環境（venv）は使わず、アプリがライブラリを Bot ごとに入れる。
- 動かすときの「今いるフォルダ」は Bot のフォルダ（`main.py` のある場所）。それでもファイルの場所は
  `Path(__file__).resolve().parent` からの相対で書く。`C:\...` のような決め打ちの場所は書かない。
- **キーボード入力はない**。`input()` を使わない。起動したら人の操作なしで動き続けること。
- 画面もない。GUI（tkinter など）、音を鳴らす Windows の機能、`os.startfile` は使わない。
- **Windows 専用のものは使わない**：`ctypes.windll`・`winreg`・`msvcrt`・`winsound`・DPAPI、`.bat`・PowerShell、`cmd /c`。
  外部コマンド（`ffmpeg` など）を呼ぶときは、ない場合でも Bot が落ちないようにし、ログで知らせる。
- スマホは PC より遅い（古い機種は Snapdragon 660・メモリ 4GB）。重い計算や大きなデータを全部メモリに読むことは避ける。
- 文字コードは、ファイルを開くたびに `encoding="utf-8"` を書く。
- 時刻を扱うときは `zoneinfo.ZoneInfo("Asia/Tokyo")` を使い、`requirements.txt` に `tzdata` を入れる。

## 3. ライブラリ

- 使うものは全部 `requirements.txt` に書く（版は `>=` で下限だけ。例：`discord.py>=2.6`）。
- **なるべく Python だけで書かれたライブラリを選ぶ**。スマホにはコンパイラがないので、C などで書かれた部品が
  必要なもの（numpy、Pillow、cryptography の古い版、PyNaCl など）は入らないことがある。使う前に「Android で入るか」を
  理由付きで教えて、代わりがあればそちらを使う。
- **discord.py の通話（音声）は使えない**（スマホの Python に音声の部品がない）。音声が必要なら、先に相談する。
- aiohttp などは、アプリが自動で「Python だけで動く方式」に切り替えるので、そのまま使ってよい。

## 4. トークンなどの値

- トークン・API キー・パスワードは、**コードにも、ほかのファイルにも書かない**。`.env` にだけ書く。
  ```
  DISCORD_TOKEN=ここにトークン
  ```
- 読み方：`os.environ["DISCORD_TOKEN"]`（アプリが .env の値を環境変数として渡す）。PC で試すときのために
  `python-dotenv` で `.env` も読むようにしてよい（`load_dotenv()` は、すでにある値を上書きしない）。
- 値がないときは、何が足りないかを日本語でログに出して、終了コード 1 で止まる。
- `.env.example` に、同じ名前で値が空の見本を置く。
- `.env` に使ってはいけない名前：`PATH`、`LD_` で始まるもの、`PYTHON` で始まるもの、`NODE_` で始まるもの、`〜_PROXY` など
  （アプリが安全のため無視する）。Bot 用の名前は `BOT_〜` や `DISCORD_〜` にする。

## 5. ログ・止まり方・データ

- 様子は `print()` か `logging` でログに出す（アプリの「ログ」タブに出る）。トークンの値は絶対に出さない。
- 普通に止めるときは終了コード 0、エラーで止まるときは 0 以外にする（0 以外だと、アプリが「エラーで停止」と通知する）。
- 予想外のエラーで Bot 全体が落ちないようにする（コマンド 1 つのエラーは、そのコマンドの中で受け止めてログに出す）。
  接続が切れたときの再接続は discord.py に任せてよい。
- Bot が書くデータは `data/` に置く。書くときは「一時ファイルに書いてから名前を付け替える」で、途中で止まっても壊れないようにする。
- 名前が `.db`・`.sqlite`・`.key`・`.session` で終わるファイルや、名前に `token`・`secret` を含むファイルは、
  アプリが共有フォルダにコピーしない（スマホの中だけに残る）。PC からも見たいデータは JSON などにする。

## 6. 最後に作るもの：〈Bot の名前〉-Android.zip

完成したら、`tools/make_android_zip.py` を次の内容で作り、`python tools/make_android_zip.py` で ZIP を作ってください（ZIP は Bot のフォルダのいちばん上にできます）。

```python
"""〈Bot の名前〉-Android.zip を作る。Python Runner の「＋ Botを追加 → ファイルを選ぶ」で取り込む。"""
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent  # tools/ の 1 つ上 = Bot のフォルダ
NAME = ROOT.name
OUT = ROOT / f"{NAME}-Android.zip"
SKIP_DIRS = {"__pycache__", ".git", "venv", ".venv", "node_modules", "__MACOSX", ".idea", ".vscode", ".pytest_cache"}
SKIP_FILES = {OUT.name, ".DS_Store", "Thumbs.db", ".gitignore"}

def files():
    for path in sorted(ROOT.rglob("*")):
        rel = path.relative_to(ROOT)
        if path.is_file() and not set(rel.parts[:-1]) & SKIP_DIRS and rel.name not in SKIP_FILES \
                and not rel.name.endswith(("-Android.zip", ".bak")):
            yield path, rel

with zipfile.ZipFile(OUT, "w", zipfile.ZIP_DEFLATED) as z:
    count = 0
    for path, rel in files():
        # 中は「〈Bot の名前〉/…」の形にする（アプリが上のフォルダを 1 段外す）。区切りは / にする。
        z.write(path, f"{NAME}/{rel.as_posix()}")
        count += 1
print(f"{OUT.name} を作りました（{count} 個のファイル）")
print("※ .env（トークン）も入っています。この ZIP は人に渡さないでください。")
```

- ZIP の中に `.env` が入るので、スマホで取り込めばトークンもそのまま使える。**この ZIP は人に渡さない**。
- ZIP を作ったら、入ったファイルの一覧と、`main.py` が一番上にあることを確かめて教えてください。

## 7. README.md に書くこと

- この Bot が何をするか、使えるコマンドの一覧。
- `.env` に書く名前と、それぞれの意味。
- 「スマホで動かす手順」：
  1. `〈Bot の名前〉-Android.zip` をスマホに送る（Google ドライブ・USB など）
  2. Python Runner の「＋ Botを追加」→「ファイルを選ぶ」で ZIP を選ぶ（名前は「-Android」を外した形で自動で入る）
  3. 「ライブラリ」タブで「部品を入れる」（または requirements.txt の中身を「インストール」）
  4. 「起動」
- 新しい版に入れ替えるとき：ファイルタブの「Botをまるごと入れ替える」で新しい ZIP を選ぶ（スマホで増えたデータは「残す」）。

## 8. Node.js も使うとき（試験中の機能）

- Python だけで作れるなら、Node.js は使わない。
- どうしても必要なとき（通話など）は、Node.js の部分を `player/` のような**別フォルダ**に入れ、
  `package.json` と `package-lock.json` を必ず置く。Node.js は **24**。
- アプリは `npm ci --ignore-scripts` で入れる。**インストール時にスクリプトが動く部品（postinstall でファイルを
  ダウンロードするもの、C++ をビルドするもの）は動かない**。使う前に確認する。
- `main.py` から `shutil.which("node")` で node を見つけて、子プロセスとして起動する。片方が止まったら両方止める。
