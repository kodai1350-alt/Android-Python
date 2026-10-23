# Python Runner（お試し版）

Androidスマホで Python のスクリプト（Discord Bot など）を、**画面を消したまま動かし続ける**ためのアプリです。
公式の Python 3.14 をアプリに同梱しているので、別に Python を入れる必要はありません。

今はお試し版です。Bot を1つだけ動かせます。複数のBotや見やすいホーム画面は、次の版で作ります。

- 対応端末: Android 8.0 以上、64ビット（arm64）
- Google Play では配布していません。このページの Releases からダウンロードします。

## インストール手順

1. スマホのブラウザでこのリポジトリの **Releases** を開き、最新の `PythonRunner-vX.Y.Z.apk` をダウンロードします。
2. ダウンロードした apk を開きます。
   「提供元不明のアプリ」の確認が出たら、そのブラウザ（または「ファイル」アプリ）からのインストールを**許可**します。
   Android 8 では、アプリごとに許可する方式です（設定 → アプリと通知 → 詳細設定 → 特別なアプリアクセス → 不明なアプリのインストール）。
3. 「インストール」を押します。

アップデートするときも、同じ手順で新しい apk を入れれば上書きされます（Bot のファイルや設定は残ります）。

## お試し版の使い方

画面は上から順番に操作します。

1. **Pythonの準備**: 「Pythonを準備」を押します。Python のバージョンと OpenSSL のバージョンが表示され、「✓ 準備完了」になれば成功です。
2. **Botのファイル**: 「.pyファイルを選ぶ」で、Bot の .py ファイルを選びます。
3. **ライブラリ**: 必要なパッケージ名（例: `discord.py`）を入れて「インストール」を押します。
4. **秘密の値**: 「＋追加」で、トークンなどを `TOKEN` のような名前で入れて「保存」します。
   Bot の中では `os.environ["TOKEN"]` で読めます。
5. **実行**: 「起動」を押します。止めるときは「停止」です。
6. **ログ**: Bot の `print` やエラーが表示されます。「コピー」で全文をコピーできます。

## 画面オフで動かし続けるための設定

Android は電池を節約するために、裏で動いているアプリを止めることがあります。次の2つを必ず設定してください。

1. アプリの「7. 端末の設定」にある **「電池の最適化を除外する」** を押して許可します。
2. ZenFone などの ASUS 端末では、**モバイルマネージャー → 自動起動マネージャー**（または「省電力」の設定）で、Python Runner を許可します。

Bot が動いている間は、通知欄に「Python Runner：Botが稼働中」と表示されます。これが出ている間は動いています。
Bot がエラーで止まったときは、別の通知でお知らせします。

## 開発者向け

### ビルド

```bash
./gradlew testDebugUnitTest assembleDebug
```

JDK 17、Android SDK（platform 37.0）、NDK 29.0.14206865 が必要です。ビルドのときに Python 本体を python.org から自動でダウンロードします。

### リリースの作り方

1. （最初の1回だけ）署名鍵を作ります。

   ```powershell
   powershell -ExecutionPolicy Bypass -File tools\make-keystore.ps1
   ```

   表示された4つの値を、GitHub の **Settings → Secrets and variables → Actions** に登録します（`KEYSTORE_BASE64` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`）。

2. タグを push すると、GitHub Actions が署名済みの apk を作って Releases に置きます。

   ```bash
   git tag v0.1.0
   git push origin v0.1.0
   ```

> **重要:** `pythonrunner.jks` とパスワードは必ずバックアップしてください。
> なくすと、同じアプリとしてアップデートできなくなります（一度アンインストールが必要になり、Bot のデータも消えます）。
