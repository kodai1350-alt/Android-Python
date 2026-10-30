# 紬を探しもの専門BOTにする Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** askbot（紬）から「何でも答える」機能を外し、過去の会話・URL・写真を、ざっくりした言葉と時期で探して、Components V2 のカードで見せるBOTにする。

**Architecture:**
- アプリ側（Kotlin）は変えない。llama-server b11972 はjinjaが標準で有効で、LFM3のツール呼び出しの書式と `response_format`（json_schema）に対応ずみ（確認ずみ）。
- Bot は今と同じ1ファイル `bots/askbot/askbot.py`。中身を次の部品に組み替える：
  - 記録（SQLite、新しいファイル `tsumugi.db`）
  - 時期の言葉の読み取り（プログラム）
  - 頼みの読み取り（AIモデルのツール呼び出し）
  - 候補の確認（AIモデル、json_schema）
  - 写真の分類（空き時間に動く。質問が来たら打ち切る）
  - カード（`discord.ui.LayoutView`）
- 時期の計算はAIモデルにさせない。AIモデルは「半年前ぐらい」という**言葉**を取り出すだけで、日付の幅はプログラムが決める（精度最優先のため）。

**Tech Stack:** Python 3.14（スマホ）/ 3.12以上（CI）、discord.py 2.7.1（`discord.ui.LayoutView`, `Container`, `Section`, `TextDisplay`, `Thumbnail`, `Separator`, `ActionRow`, `Button`, `Select`）、aiohttp、sqlite3（FTS5 trigram）、pytest

**Spec:** `docs/superpowers/specs/2026-10-30-tsumugi-search-bot-design.md`

## Global Constraints

- すべて無料。外部の有料サービスは使わない。Wikipediaへの通信もなくす。
- Botは1ファイル `bots/askbot/askbot.py`（ユーザーは1ファイルを取り込むだけ）。テストは `bots/askbot/test_*.py` に分けてよい。
- `discord.py==2.7.1`。`allowed_mentions=discord.AllowedMentions.none()` を続ける。
- 記録はチャンネルごと。探すのは、メンションされたチャンネル（スレッドなら親チャンネル）の記録だけ。
- スレッドとフォーラムの投稿は、親チャンネルの記録に含める。
- ほかのBOT・Webフック・紬自身の投稿は記録しない（`message.author.bot` または `message.webhook_id`）。
- 写真は `--image-max-tokens 512` のまま（アプリ側、変えない）。
- 枠の色：会話・URL＝`0x5865F2`、写真＝`0xF0B232`、記録の様子＝`0x23A55A`。
- 1つの返事の部品は40個まで。結果は1ページ5件。
- 表示の言葉は日本語。仕様で決まった文言はそのまま使う：
  - 「紬は探しもの専門です」
  - 「🔎 探しています…」
  - 「元の発言へ」
  - 「もっと見る」
  - 「種類でしぼる」
  - 「見つかりませんでした」
- `/記録する`・`/記録やめる`・`/記録の様子` は `default_permissions(manage_channels=True)`。

## Review Focus

1. **非公開スレッド**：非公開スレッドの中身が、親チャンネルや別のスレッドでの検索に出てこない。そのスレッドの中で聞いたときだけ出る（Task 3, Task 10）。
2. **消された・直されたメッセージ**：記録したあとで消されたメッセージや写真は結果に出ず、記録からも消える。直されたものは直した後の文で出る（Task 3, Task 10）。
3. **写真の分類中に質問が来る**：分類はすぐ打ち切られ、その写真は「分類待ち」に戻ってあとでやり直される。分類ずみとして保存されない（Task 8）。
4. **AIモデルが起動中・落ちている**：分類はその写真を失敗扱いにせず、少し待ってやり直す。質問には「AIモデルにつながりません…」と出る（Task 5, Task 8）。
5. **記録の途中でBotが止まる**：再起動すると、チャンネルとスレッドごとに続きから読み込む。読み込み済みの分を二重に入れない（Task 4）。

---

### Task 1: スパイク — AIモデルが頼みを正しく読めるか、確認にどれくらいかかるか

捨てるコード。テストなし。結果をメモに残し、Task 6・7 の決め事に使う。

**Files:**
- Create: `bots/askbot/spike_search.py`（Task 11 で消す）
- Create: `docs/superpowers/notes/2026-10-30-tsumugi-spike.md`

- [ ] **Step 1: `spike_search.py` を書く**
  - 環境変数 `LLM_URL`, `LLM_API_KEY` を使い、`/v1/chat/completions` に `stream: false` で送る。
  - 送る内容：Task 6 の `SYSTEM_PROMPT` と `SEARCH_TOOL`（同じ文面をこのファイルに写す）、`tool_choice: "required"`、`temperature: 0.1`。
  - 次の12個の頼みについて、返ってきた `kind`・`words`・`when`・`photo_kind` と、かかった秒数を `print` する。
    1. 「Eラーニングの話で半年前ぐらいに貼ってたURLどれ？」
    2. 「半月前のラーメンの写真」
    3. 「先週、旅行の日程の話してたのどこ？」
    4. 「去年の夏に誰かが送ってた海の写真ある？」
    5. 「この前教えてもらった英語の勉強サイト」
    6. 「3日前ぐらいにバイトのシフトの話したよね」
    7. 「猫の写真見せて」
    8. 「最近貼られたYouTubeのリンク」
    9. 「おはよう」
    10. 「量子コンピュータって何？」
    11. 「10月14日の会話」
    12. 「スクショで送ったエラーの画面どれだっけ」
  - 続けて、候補の確認（Task 7 と同じ形）を1回試す。
    - 頼みは1番、候補は作り物10件（Eラーニング関係3件、無関係7件。各80文字以内）。
    - `response_format` は `{"type":"json_schema","json_schema":{"schema":RELEVANT_SCHEMA}}` で送る。
    - 返事と秒数を `print` する。
  - 最後に「SPIKE DONE」と出す。
- [ ] **Step 2: ユーザーにAQUOSで動かしてもらう**
  - 一時的にこのファイルをBotとして選んでもらい、「7. ログ」をコピーして送ってもらう。
- [ ] **Step 3: 結果をメモに書く**（`docs/superpowers/notes/2026-10-30-tsumugi-spike.md`）
  - 項目ごとに、12問中何問正しかったかと、秒数の平均・最大を書く。
  - 決めること：
    - `kind` の正解が12問中10問未満なら、kind はルール（Task 6 の `rule_kind`）を優先し、AIモデルの kind は使わない。
    - `when` の正解が10問未満なら、時期はいつも質問文全体から `parse_when` で読む。
    - 確認10件の秒数を `CHECK_BATCH` の決定に使う。
      - 60秒以下なら `CHECK_BATCH = 10`、`MAX_CANDIDATES = 20`。
      - 60秒を超えたら `CHECK_BATCH = 5`、`MAX_CANDIDATES = 10`。
- [ ] **Step 4: Commit**

```bash
git add bots/askbot/spike_search.py docs/superpowers/notes/2026-10-30-tsumugi-spike.md
git commit -m "spike: can the model read search requests on the phone"
```

---

### Task 2: 時期の言葉を日付の幅にする

**Files:**
- Modify: `bots/askbot/askbot.py`（`_find_dates` / `parse_date_range` を置き換える）
- Test: `bots/askbot/test_when.py`

**Interfaces:**
- Produces: `parse_when(text: str, now: datetime) -> tuple[datetime | None, datetime | None]`（JSTの `since`（含む）, `until`（含まない））。時期の言葉がなければ `(None, None)`。

- [ ] **Step 1: 失敗するテストを書く**（`now = datetime(2026, 10, 30, 12, tzinfo=JST)`）

```python
def test_exact_words_keep_their_old_ranges():
    assert parse_when("昨日", NOW) == (d(10, 29), d(10, 30))
    assert parse_when("先週", NOW) == (d(10, 19), d(10, 26))
    assert parse_when("10月14日", NOW) == (d(10, 14), d(10, 15))

def test_about_n_ago_is_widened():
    # since = 中心 − 幅、until = 中心 + 幅 + 1日（最後の日を含める）
    # 「半年前ぐらい」= 4月30日を中心に前後1か月
    assert parse_when("半年前ぐらい", NOW) == (d(3, 30), d(5, 31))
    # 「半月前」= 10月15日を中心に前後5日
    assert parse_when("半月前", NOW) == (d(10, 10), d(10, 21))
    # 「3日前ぐらい」= 10月27日を中心に前後1日
    assert parse_when("3日前ぐらい", NOW) == (d(10, 26), d(10, 29))
    # 「2週間前」= 10月16日を中心に前後3日
    assert parse_when("2週間前", NOW) == (d(10, 13), d(10, 20))

def test_seasons_and_years():
    assert parse_when("去年の夏", NOW) == (d(6, 1, 2025), d(9, 1, 2025))
    assert parse_when("去年", NOW) == (d(1, 1, 2025), d(1, 1, 2026))

def test_recent_and_none():
    assert parse_when("最近", NOW) == (d(10, 16), d(10, 31))
    assert parse_when("この前", NOW) == (d(9, 30), d(10, 31))
    assert parse_when("猫の写真", NOW) == (None, None)

def test_full_width_digits():
    assert parse_when("３日前", NOW) == parse_when("3日前", NOW)
```

（`d(m, day, y=2026)` は JST の0時を返すテスト用の関数。）

- [ ] **Step 2: 失敗を確かめる** — Run: `python -m pytest bots/askbot/test_when.py -q` / Expected: FAIL（`parse_when` がない）
- [ ] **Step 3: `parse_when` を実装する**
  - NFKCで正規化してから読む。今の `_find_dates` の言葉（今日・昨日・一昨日・今週・先週・今月・先月・M月D日・M月）はそのまま使う。
  - 足す言葉と幅：
    - 「N日前」：中心±1日
    - 「N週間前」：中心±3日
    - 「半月前」：中心±5日
    - 「Nか月前／Nヶ月前／Nカ月前」：中心±1か月
    - 「半年前」：中心±1か月
    - 「N年前」：中心±3か月
    - 「去年」：その年の1月1日〜翌年1月1日
    - 「去年の春・夏・秋・冬」：春3〜5月、夏6〜8月、秋9〜11月、冬12〜2月
    - 「最近」：14日前〜明日
    - 「この前」：30日前〜明日
  - 「N…前」の形は、`since = 中心 − 幅`、`until = 中心 + 幅 + 1日`。中心は今日の0時から数える。
  - 「ぐらい」「ごろ」「くらい」があってもなくても、幅は同じ。
  - 日付の範囲は日の境目（0時）にそろえる。
- [ ] **Step 4: 通ることを確かめる** — Run: `python -m pytest bots/askbot -q` / Expected: PASS（古い `parse_date_range` のテストは `parse_when` に書き換える）
- [ ] **Step 5: Commit** — `git commit -m "feat(askbot): read vague time words like 半年前ぐらい"`

---

### Task 3: 記録（tsumugi.db）— チャンネルごと、スレッド込み、URLと写真

**Files:**
- Modify: `bots/askbot/askbot.py`（`HistoryStore` を `RecordStore` に置き換える）
- Test: `bots/askbot/test_store.py`

**Interfaces:**
- Produces:
  - `@dataclass Msg(message_id: int, guild_id: int, record_id: int, place_id: int, private: bool, author: str, content: str, links: str, created_at: datetime)`
    - `record_id`：記録の単位（親チャンネルのID）。
    - `place_id`：実際に書かれた場所（チャンネルかスレッドのID）。
    - `private`：非公開スレッドなら True。
    - `links`：URLとリンクのプレビュー（タイトル・説明）を改行でつないだ文。
  - `@dataclass Photo(attachment_id: int, message_id: int, guild_id: int, record_id: int, place_id: int, private: bool, author: str, created_at: datetime, kind: str | None, description: str | None)`
  - `class RecordStore(path: str)`、メソッド：
    - `register(guild_id, record_id)` / `unregister(guild_id, record_id)`（記録をやめたら、そのチャンネルの会話・写真をすべて消す）
    - `is_registered(record_id) -> bool` / `registered(guild_id) -> set[int]`
    - `upsert(msg: Msg, photos: list[Photo])`：登録中の記録だけ保存する（今の upsert と同じく、1つの文で確かめる）。写真はすでにあれば kind・description を消さない。
    - `delete_message(message_id)`：写真も一緒に消す。
    - `latest(place_id) -> datetime | None`
    - `search_messages(record_id, place_id, words, since, until, links_only: bool, limit) -> list[Msg]`
    - `search_photos(record_id, place_id, words, kind: str | None, since, until, limit) -> list[Photo]`
    - `next_unclassified() -> Photo | None`（新しい順）/ `save_class(attachment_id, kind, description)`
    - `stats(record_id) -> RecordStats(messages: int, since: datetime | None, photos: int, classified: int)`
    - `import_old(path)`：古い `askbot-history.db` の `channels` だけを登録に写す。古いファイルは消さない。

- [ ] **Step 1: 失敗するテストを書く**
  - `test_search_finds_words_in_links_too`：content「これ見て」、links「ドットインストール Eラーニング」→ words `["Eラーニング"]` で見つかる。
  - `test_links_only_skips_messages_without_links`
  - `test_search_stays_in_one_record`：記録Aで聞いたら、記録Bの会話は出ない。
  - `test_private_thread_only_from_inside`：
    - `private=True, place_id=T` の会話は、`place_id=T` で聞いたときだけ出る。
    - 親チャンネル（`place_id=record_id`）や別のスレッドで聞いたときは出ない。
    - 写真も同じ。
  - `test_unregister_removes_messages_and_photos`
  - `test_upsert_ignored_when_not_registered`
  - `test_reupsert_keeps_photo_class`：分類ずみの写真をもう一度 upsert しても、kind・description が残る。
  - `test_delete_message_removes_its_photos`
  - `test_next_unclassified_is_newest_first`
  - `test_search_photos_by_description_and_kind`：
    - description「ラーメン。チャーシュー」、kind「料理」→ words `["ラーメン"]` で見つかる。
    - kind `"料理"` でしぼれる。
    - 未分類の写真は出ない。
  - `test_stats_counts`
  - `test_import_old_copies_registrations`
  - 2文字の言葉（「旅行」）でも見つかる。今の `_like` と同じく、3文字未満は LIKE で探す。
- [ ] **Step 2: 失敗を確かめる** — Run: `python -m pytest bots/askbot/test_store.py -q` / Expected: FAIL
- [ ] **Step 3: `RecordStore` を実装する**
  - テーブル：`records`、`messages`、`photos`。
  - FTS5 trigram は2つ：
    - `messages_fts`：content と links の両方を探す
    - `photos_fts`：description と kind を探す
  - 並び順：言葉に当たった数が多い順、同じなら新しい順（今の `search` と同じ）。
  - 非公開の条件：`(private = 0 OR place_id = ?)`
  - ファイルは `os.path.join(HOME, "tsumugi.db")`。
- [ ] **Step 4: 通ることを確かめる** — Run: `python -m pytest bots/askbot -q` / Expected: PASS（`HistoryStore` のテストは消すか書き換える）
- [ ] **Step 5: Commit** — `git commit -m "feat(askbot): per-channel records with threads, links and photos"`

---

### Task 4: Discordのメッセージを記録にする・さかのぼって読む

**Files:**
- Modify: `bots/askbot/askbot.py`（`_to_stored`, `backfill`, `on_message`, `on_raw_message_edit` など）
- Test: `bots/askbot/test_recording.py`

**Interfaces:**
- Consumes: `RecordStore`, `Msg`, `Photo`（Task 3）
- Produces:
  - `record_of(channel) -> tuple[int, int, bool]`：`(record_id, place_id, private)`。スレッドなら `(parent_id, thread.id, thread.is_private())`、それ以外は `(channel.id, channel.id, False)`。
  - `should_record(message) -> bool`：人が書いたメッセージだけ True（`author.bot` も `webhook_id` もない）。
  - `to_record(message) -> tuple[Msg, list[Photo]]`：
    - links は、本文のURL、`embeds` の `url`・`title`・`description` を改行でつなぐ。
    - 写真は `content_type` が `image/` の添付ファイル。
  - `async def backfill(store, channel) -> int`：
    - `channel` 自身（フォーラムなら履歴なし）と、`channel.threads`、`archived_threads()`（公開）、読めるなら `archived_threads(private=True)` を読む。
    - 場所ごとに `store.latest(place_id)` より後から読む。初めてなら全部。
    - 1件ごとに `store.is_registered(record_id)` を確かめ、記録をやめていたらそこで止める（今の backfill と同じ）。
    - 読み込んだ件数を返す。

- [ ] **Step 1: 失敗するテストを書く**（`test_askbot.py` の `FakeMessage` などと同じ作り方の偽物を使う）
  - `test_thread_message_goes_to_parent_record`
  - `test_private_thread_is_marked_private`
  - `test_bot_and_webhook_messages_are_not_recorded`
  - `test_links_include_embed_title_and_description`：本文「https://example.com/x」、embed（title「ドットインストール」、description「Eラーニング」）→ links に3つとも入る。
  - `test_only_image_attachments_become_photos`
  - `test_backfill_reads_threads_and_archived_threads`
  - `test_backfill_resumes_after_latest_per_place`：場所ごとに `after=latest` で呼ばれ、重複して数えない。
  - `test_backfill_stops_when_unregistered`
- [ ] **Step 2: 失敗を確かめる** — Run: `python -m pytest bots/askbot/test_recording.py -q` / Expected: FAIL
- [ ] **Step 3: 実装する**
  - `on_message` / `on_raw_message_edit`：`record_of(message.channel)` の record_id が登録中で、`should_record` なら upsert する。
  - リンクのプレビューは、あとから編集イベントで届くことが多い。編集イベントでも links を更新する。
  - `on_raw_message_delete` / bulk：`delete_message`。
  - `on_ready` の追いつき読み込みは、記録ごとに `backfill` を呼ぶ。
  - `BACKFILL_DAYS` は消す（上限なし）。
- [ ] **Step 4: 通ることを確かめる** — Run: `python -m pytest bots/askbot -q` / Expected: PASS
- [ ] **Step 5: Commit** — `git commit -m "feat(askbot): record threads, links and photos; backfill all history"`

---

### Task 5: AIモデルとのやり取り（ツール呼び出し・json・打ち切り）

**Files:**
- Modify: `bots/askbot/askbot.py`（`stream_answer` を `llm_call` に置き換える）
- Test: `bots/askbot/test_llm.py`（今の `start_app` / `free_port` を使う）

**Interfaces:**
- Produces:
  - `@dataclass LlmReply(text: str, tool_args: dict | None)`
  - `async def llm_call(session, base_url, body: dict, *, api_key=None, on_progress=None, wait_seconds=120) -> LlmReply`
    - 中身は今の `stream_answer` と同じ：
      - `stream: true` と `return_progress: true` で送る
      - 503 と接続できないときは、2秒ごとに `wait_seconds` まで待つ
      - 読み始めてから切れたら `LlmError`
    - 本文の `delta.content` をつなげて `text` にする。
    - `delta.tool_calls[0].function.arguments` をつなげ、最後に `json.loads` して `tool_args` にする。読めなければ `LlmError`。
    - 呼んでいる側の task が cancel されたら、接続を閉じて `asyncio.CancelledError` をそのまま上げる（llama-server は接続が閉じると処理をやめる）。
  - `LlmError` と、今の文言 `_UNREACHABLE_MESSAGE` などは残す。

- [ ] **Step 1: 失敗するテストを書く**
  - `test_llm_call_collects_text`
  - `test_llm_call_collects_tool_arguments_across_chunks`：arguments が `'{"kind":'` と `'"photo"}'` の2つに分かれて届いても `{"kind": "photo"}` になる。
  - `test_llm_call_bad_tool_json_is_llm_error`
  - `test_llm_call_cancel_closes_connection`：サーバー側で接続が閉じられたことを確かめる。
  - 今の `stream_answer` のテスト（待つ・途中で切れる・context エラー・error イベント・空の答え）は `llm_call` に書き換えて残す。
- [ ] **Step 2: 失敗を確かめる** — Run: `python -m pytest bots/askbot/test_llm.py -q` / Expected: FAIL
- [ ] **Step 3: 実装する**
- [ ] **Step 4: 通ることを確かめる** — Run: `python -m pytest bots/askbot -q` / Expected: PASS
- [ ] **Step 5: Commit** — `git commit -m "feat(askbot): llm_call with tool calls and cancellation"`

---

### Task 6: 頼みを読む（何を・どんな言葉で・いつ）

**Files:**
- Modify: `bots/askbot/askbot.py`（`plan_question` と `Plan` を置き換える）
- Test: `bots/askbot/test_understand.py`

**Interfaces:**
- Consumes: `llm_call`, `LlmReply`（Task 5）、`parse_when`（Task 2）
- Produces:
  - `@dataclass Query(kind: str, words: list[str], since: datetime | None, until: datetime | None, photo_kind: str | None, when_text: str | None)`
    - `kind` は `"message" | "link" | "photo" | "other"`。
  - `PHOTO_KINDS = ["料理", "風景", "スクショ", "人物", "ペット・動物", "乗り物", "買い物・物", "文書・メモ", "その他"]`
  - `SYSTEM_PROMPT`：探しもの専用の指示。毎回同じ文で、最初に置く。
  - `SEARCH_TOOL`：`search` という1つのツール。引数（`required` は kind と words）：
    - `kind`：enum 4つ
    - `words`：string の配列。言われた言葉に、似た言葉を3つまで足す
    - `when`：時期の言葉をそのまま。なければ空
    - `photo_kind`：enum `PHOTO_KINDS`、または空
  - `rule_kind(text) -> str`：決まった言葉で kind を決める。
    - 「写真・画像・スクショ・pic」→ photo
    - 「URL・リンク・サイト・http」→ link
    - それ以外 → message
    - 探す言葉も時期の言葉もなければ other
  - `async def understand(session, base_url, question, now, *, api_key=None) -> Query`
    - `tool_choice: "required"` で `SEARCH_TOOL` を呼ばせる。
    - `since`・`until` は `parse_when(when or question, now)`。
    - words が空なら `extract_keywords(question)`（今の関数）で補う。
    - Task 1 のメモで kind が不合格なら、kind は `rule_kind` を優先する。この切り替えは `TRUST_MODEL_KIND` という定数で持つ。

- [ ] **Step 1: 失敗するテストを書く**（偽の llama-server が決まった tool_args を返す）
  - `test_understand_uses_tool_args`：`{"kind":"link","words":["Eラーニング","オンライン学習"],"when":"半年前ぐらい"}` → Query の since・until が `parse_when("半年前ぐらい")` と同じになる。
  - `test_understand_fills_words_from_question_when_empty`
  - `test_understand_dates_from_question_when_when_missing`：when が空で、質問に「先週」がある。
  - `test_rule_kind`：写真・リンク・会話・other の4例。
  - `test_request_body_has_tool_and_fixed_prefix`：body の `messages[0]` が `SYSTEM_PROMPT`、`tools == [SEARCH_TOOL]`、`tool_choice == "required"`。
- [ ] **Step 2: 失敗を確かめる** — Run: `python -m pytest bots/askbot/test_understand.py -q` / Expected: FAIL
- [ ] **Step 3: 実装する**
- [ ] **Step 4: 通ることを確かめる** — Run: `python -m pytest bots/askbot -q` / Expected: PASS
- [ ] **Step 5: Commit** — `git commit -m "feat(askbot): understand search requests with a tool call"`

---

### Task 7: 候補をAIモデルに確かめさせる

**Files:**
- Modify: `bots/askbot/askbot.py`
- Test: `bots/askbot/test_check.py`

**Interfaces:**
- Consumes: `llm_call`（Task 5）
- Produces:
  - `CHECK_BATCH`、`MAX_CANDIDATES`（値は Task 1 のメモで決めたもの）
  - `RELEVANT_SCHEMA = {"type": "object", "properties": {"relevant": {"type": "array", "items": {"type": "integer"}}}, "required": ["relevant"]}`
  - `candidate_line(i: int, item: Msg | Photo) -> str`：`"{i}. {M月D日} {author}: {本文かlinksか説明の先頭80文字}"`
  - `async def check_candidates(session, base_url, question, items: list, *, api_key=None, on_progress=None) -> tuple[list, bool]`
    - 当たりだけを、元の順番のまま返す。
    - 2つ目の値は「確かめられたか」。AIモデルとのやり取りに失敗したら `(items, False)` を返す。

- [ ] **Step 1: 失敗するテストを書く**
  - `test_keeps_only_relevant_in_order`：返事 `{"relevant":[3,1]}` → items[0], items[2] の順。
  - `test_out_of_range_numbers_ignored`
  - `test_batches_of_CHECK_BATCH`：MAX_CANDIDATES 件を渡すと `ceil(MAX_CANDIDATES / CHECK_BATCH)` 回呼ぶ。
  - `test_llm_failure_returns_unchecked`
  - `test_candidate_line_trims_to_80_chars`
- [ ] **Step 2: 失敗を確かめる** — Run: `python -m pytest bots/askbot/test_check.py -q` / Expected: FAIL
- [ ] **Step 3: 実装する**
  - body：`response_format: {"type": "json_schema", "json_schema": {"schema": RELEVANT_SCHEMA}}`、`temperature: 0.1`。
  - 頼みと候補の一覧は user メッセージに入れる。system は `SYSTEM_PROMPT`。
- [ ] **Step 4: 通ることを確かめる** — Run: `python -m pytest bots/askbot -q` / Expected: PASS
- [ ] **Step 5: Commit** — `git commit -m "feat(askbot): let the model check candidates before showing them"`

---

### Task 8: 写真の分類（空き時間・質問が来たら打ち切り）

**Files:**
- Modify: `bots/askbot/askbot.py`（`AskQueue` に空き時間の仕事を足す、`PhotoClassifier` を足す）
- Test: `bots/askbot/test_classify.py`、`bots/askbot/test_askbot.py`（AskQueue のテスト）

**Interfaces:**
- Consumes: `RecordStore.next_unclassified` / `save_class` / `delete_message`（Task 3）、`llm_call`（Task 5）、`PHOTO_KINDS`（Task 6）
- Produces:
  - `AskQueue(idle: Callable[[], Awaitable[None]] | None = None)`：
    - 待ちがないあいだ `idle()` を何度も呼ぶ。
    - `submit` されたら、走っている `idle()` を cancel してから質問を始める。
  - `CLASS_SCHEMA`：`{"kind": enum PHOTO_KINDS, "description": string}`（description は写っている文字も入れる、日本語80文字まで）
  - `async def classify(session, base_url, data: bytes, content_type: str, *, api_key=None) -> tuple[str, str]`
  - `class PhotoClassifier(store, fetch_image, classify, sleep=asyncio.sleep)`、`async def run_once()`：
    - `fetch_image(photo) -> tuple[bytes, str] | None`：Discordから取り直す。None なら消されている。
    - 1枚を分類して保存する。
    - 分類待ちがないときは60秒寝る。
    - `LlmError` のときは60秒寝て、写真は分類待ちのまま残す。
    - 消されていたら `delete_message`。
    - 8MBを超える写真は `save_class(id, "その他", "（大きすぎて見られませんでした）")`。

- [ ] **Step 1: 失敗するテストを書く**
  - `test_idle_runs_when_queue_empty`
  - `test_submit_cancels_running_idle`：idle の中で `await asyncio.Event().wait()` している → submit で cancel され、質問の job が動く。
  - `test_cancelled_photo_stays_unclassified`：classify の途中で cancel → `save_class` が呼ばれない。
  - `test_llm_error_keeps_photo_pending_and_sleeps_60`
  - `test_deleted_photo_is_removed`
  - `test_too_big_photo_marked_without_model`
  - `test_classify_sends_image_and_schema`：body に `image_url` の data URL と `response_format` が入る。
- [ ] **Step 2: 失敗を確かめる** — Run: `python -m pytest bots/askbot -q -k "idle or classify or photo"` / Expected: FAIL
- [ ] **Step 3: 実装する**
  - `AskBot.setup_hook` で `AskQueue(idle=classifier.run_once)` にする。
  - `fetch_image` は `channel.fetch_message` で取り直した添付ファイルを `read()` する。
- [ ] **Step 4: 通ることを確かめる** — Run: `python -m pytest bots/askbot -q` / Expected: PASS
- [ ] **Step 5: Commit** — `git commit -m "feat(askbot): classify photos in idle time, stop for questions"`

---

### Task 9: カード（Components V2）

**Files:**
- Modify: `bots/askbot/askbot.py`（`ReplyWriter`・`progress_text`・`split_message` を消し、カードの関数を足す）
- Test: `bots/askbot/test_cards.py`

**Interfaces:**
- Consumes: `Msg`, `Photo`, `RecordStats`（Task 3）、`Query`（Task 6）
- Produces（どれも `discord.ui.LayoutView` を返す。押せる部品のコールバックは Task 10 で付ける）：
  - `status_card(text: str) -> LayoutView`：TextDisplay が1つだけの枠。
  - `results_card(query, title: str, place_name: str, result: SearchResult, page: int) -> LayoutView`（`SearchResult` は Task 10 で定義する。このタスクで先に作ってよい）
    - 見出し：`### 🔎 {title}`（写真なら 🖼）
    - 条件の行：`-# #{place_name} の記録から ・ {期間} ・ {件数}件見つかりました`。期間は `M月D日〜M月D日`、期間がなければ「全期間」。
    - 1件ごとに Separator と Section を置く。
      - 文：`**{番号}**　{M月D日}　{author}` の下に、URL・プレビューのタイトル、または本文の抜粋（100文字）。
      - 右の部品：会話・URLなら「元の発言へ」リンクボタン、写真なら `Thumbnail(result.thumbs[attachment_id])`。
      - 写真の結果には、下に「{番号} を開く」リンクボタンの列を付ける。
    - 1ページ5件。残りがあれば「もっと見る」ボタン。
    - 写真なら「種類でしぼる」Select（`PHOTO_KINDS`）。
    - `result.checked` が False のときは、最後に `-# ※ AIモデルで確かめられなかったので、言葉が当たった順に並べています` を足す。
    - 枠の色は Global Constraints の値。
  - `not_found_card(query, place_name) -> LayoutView`：「見つかりませんでした」と、探した言葉・期間。「期間を広げて探す」ボタン。
  - `help_card() -> LayoutView`：「紬は探しもの専門です」と、探し方の例3つ（設計書4.3）。記録の始め方（`/記録する`）も書く。
  - `record_card(place_name, stats) -> LayoutView`：
    - 会話 `{n}件（{YYYY年M月}〜）`
    - 写真 `{classified} / {photos}枚 分類ずみ`
    - 枠は緑。
  - `jump_url(guild_id, place_id, message_id) -> str`

- [ ] **Step 1: 失敗するテストを書く**（view の `walk_children()` で部品を数え、文を確かめる）
  - `test_results_card_has_jump_buttons_and_count_line`
  - `test_results_card_pages_of_five_with_more_button`：7件 → 1ページ目に5件と「もっと見る」、2ページ目に2件で「もっと見る」なし。
  - `test_photo_results_use_thumbnails_and_kind_select`
  - `test_unchecked_note_shown`
  - `test_cards_stay_within_40_components`：写真5件、ボタン、Select のいちばん多い形で40個以下。
  - `test_help_card_text`
  - `test_record_card_numbers`
  - `test_accent_colors`
- [ ] **Step 2: 失敗を確かめる** — Run: `python -m pytest bots/askbot/test_cards.py -q` / Expected: FAIL
- [ ] **Step 3: 実装する**
- [ ] **Step 4: 通ることを確かめる** — Run: `python -m pytest bots/askbot -q` / Expected: PASS
- [ ] **Step 5: Commit** — `git commit -m "feat(askbot): Components V2 cards for results, help and record status"`

---

### Task 10: つなぐ — メンションから結果のカードまで

**Files:**
- Modify: `bots/askbot/askbot.py`（`handle_question`・`answer` を作り直す。Wikipedia・ふつうの答え・写真への答えの部分は消す）
- Test: `bots/askbot/test_search_flow.py`

**Interfaces:**
- Consumes: Task 3〜9 のすべて
- Produces:
  - `@dataclass SearchResult(items: list, checked: bool, thumbs: dict[int, str])`
  - `async def run_search(query, store, record_id, place_id, verify, check) -> SearchResult`：
    1. kind に合わせて、`search_messages`（link なら `links_only=True`）か `search_photos` で `MAX_CANDIDATES` 件を取る。
    2. `verify(items) -> tuple[list, dict[int, str]]` で取り直す。
       - 消えたものは記録から消し、直されたものは直す。
       - 2つ目は、写真の attachment_id から、取り直した添付ファイルの `url` への対応。
    3. `check(items) -> tuple[list, bool]` で確かめる。
    4. `SearchResult` を返す。
  - `verify` は今の `verify_rows` を Msg・Photo 用にしたもの。
  - `/記録の様子` コマンド（`record_card`）。
  - ボタンと Select のコールバック：
    - 「もっと見る」は次のページに書き換える。
    - 「種類でしぼる」は `photo_kind` を変えて `run_search` をやり直し、書き換える。
    - 「期間を広げて探す」は since・until を前後に倍の幅に広げて、やり直す。
    - どれも順番待ち（AskQueue）を通す。
    - view の `timeout` は900秒。切れたら押せる部品を消す。

- [ ] **Step 1: 失敗するテストを書く**（偽の store・verify・check を使う）
  - `test_link_search_uses_links_only`
  - `test_deleted_items_never_shown`：verify で消えたものは結果にも出ない。
  - `test_private_thread_not_found_from_parent`：store に非公開スレッドの会話がある。親チャンネルで聞くと出ない。
  - `test_check_failure_marks_unchecked`
  - `test_other_kind_shows_help`
  - `test_question_in_thread_searches_parent_record`
  - `test_unregistered_channel_says_how_to_start`：記録していないチャンネルで聞くと「このチャンネルは記録していません。/記録する で始められます」。
- [ ] **Step 2: 失敗を確かめる** — Run: `python -m pytest bots/askbot/test_search_flow.py -q` / Expected: FAIL
- [ ] **Step 3: 実装する**
  - 流れ：
    1. メンションされたら、すぐに `status_card("🔎 探しています…")` で返信する。
    2. 順番待ちのあいだは「順番待ち {n}番目です」。
    3. 頼みを読む（`understand`）。
    4. 探す（`run_search`）。確認中は「🔎 {n}件の候補を確かめています…」。
    5. 結果のカードに書き換える。
  - AIモデルがない（`LLM_URL` なし）ときは、今の `NO_MODEL_TEXT` を status_card で出す。
  - 1人1つまでの決まりは今と同じ。
  - Wikipedia の関数と定数、`build_messages`、`collect_images`、`sources_footer`、`fallback_plan`、`image_refusal`、`shareable_channels`、`readable_channels`、`HELP_TEXT` を消す。古いテストも消す。
- [ ] **Step 4: 通ることを確かめる** — Run: `python -m pytest bots/askbot -q` / Expected: PASS
- [ ] **Step 5: Commit** — `git commit -m "feat(askbot): search flow from mention to result card"`

---

### Task 11: 片付け・説明書き・試験版

**Files:**
- Delete: `bots/askbot/spike_search.py`
- Modify: `bots/askbot/askbot.py`（先頭の説明文）、`README.md`（「質問Bot（askbot）の使い方」の節）
- Create: `docs/superpowers/notes/2026-10-30-tsumugi-device-check.md`

- [ ] **Step 1:** 先頭の説明文と README の節を「探しもの専門の紬」に書き直す。
  - 書くこと：記録の始め方、探し方の例、写真の分類に時間がかかること。
  - Wikipedia とふつうの質問の説明は消す。
- [ ] **Step 2:** `python -m pytest bots/askbot -q` と `./gradlew testDebugUnitTest lintDebug` が通ることを確かめる。
- [ ] **Step 3:** Commit、push、CI が通ったら試験版のタグ（例 `v0.4.0-test1`）を付け、リリースに `askbot.py` が付いていることと、署名証明書の SHA-256 `a4559f5ba1885373dc77ea81037e152329bf0afac9a36c26e7d4ac5fe200ae9b` を確かめる。
- [ ] **Step 4:** ユーザーにAQUOSで試してもらう。
  - 試すこと：`/記録する`、Eラーニングの例、写真の検索、`/記録の様子`。
  - 書き始めまでの秒数と、当たったかどうかをメモ（device-check）に残す。
