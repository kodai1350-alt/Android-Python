# フェーズ1（お試し版）からフェーズ2への引き継ぎ

- リリース: v0.1.0（https://github.com/kodai1350-alt/Android-Python/releases/tag/v0.1.0）
- 実機確認（計画 Task 8）の結果は、このファイルの下に追記する。

## フェーズ1で決めたこと（実装時の判断）
- compileSdk 37 / targetSdk 36（Compose BOM 2026.09.00 が compileSdk 37 以上を要求するため）。
- AGP 9.4.1 / Kotlin 2.4.21 / Gradle 9.8.2 / NDK 29.0.14206865。
- pip は CPython 同梱の ensurepip の wheel（26.2.2）を使う。
- discord.py 2.5 以降は Python 3.13 以上で audioop-lts を必須にしている。audioop-lts には Android 用 wheel が無いため、音声機能だけを NotImplementedError にするスタブ wheel を同梱し、`--find-links` と `--only-binary audioop-lts` で使わせている。
- Bot の起動・停止・終了は BotRunner（ロック1つ＋世代番号）に集約している。
- versionCode はリリースワークフローの run_number、versionName はタグから付ける。
- CI は、強制停止テスト（Linux のみ）が実行され、合格していなければ失敗する。

## 後回しにした指摘（フェーズ2で検討）
- Android 12 以降: START_STICKY でシステムが再起動したとき、ForegroundServiceStartNotAllowedException が出ることがある。
- Android 12 以降: 裏で動くプロセスを止める仕組み（phantom process killer）に注意する。
- Android 15 以降: edge-to-edge のインセット対応。
- ManagedProcess の1行の長さに上限が無い。
- 画面を回転すると状態が消え、pip を二重に実行できてしまう（ViewModel 化する）。
- 電池最適化の状態が、設定から戻っても更新されない。ActivityNotFoundException のガードも無い。
- pip の wheel が APK に2重に入っている（約1.8MB）。
- ランチャーに `$ORIGIN` の RUNPATH が無い。
- wasRunning を apply() で保存している（commit() の方が安全）。
- make-keystore.ps1 はパスワードを keytool のコマンドラインで渡している。

## 実機確認の結果（Task 8）
（ここに追記）
