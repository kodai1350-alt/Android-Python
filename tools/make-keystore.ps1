# Python Runner のリリース用署名鍵を作り、GitHub Secrets に登録する値を出力します。
# 使い方: プロジェクトのフォルダで  powershell -ExecutionPolicy Bypass -File tools\make-keystore.ps1

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$keystore = Join-Path $root 'pythonrunner.jks'
$b64File = Join-Path $root 'keystore.b64.txt'
$alias = 'pythonrunner'

if (Test-Path $keystore) {
    Write-Host "$keystore はすでにあります。上書きしないように終了します。" -ForegroundColor Yellow
    exit 1
}

$keytool = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\keytool.exe' } else { 'keytool' }

$secure = Read-Host '署名鍵のパスワードを決めて入力してください（6文字以上）' -AsSecureString
$password = [Runtime.InteropServices.Marshal]::PtrToStringAuto(
    [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure))
if ($password.Length -lt 6) { throw 'パスワードは6文字以上にしてください。' }

& $keytool -genkeypair -v -keystore $keystore -alias $alias -keyalg RSA -keysize 4096 -validity 36500 `
    -storepass $password -keypass $password -dname 'CN=Python Runner'
if ($LASTEXITCODE -ne 0) { throw 'keytool が失敗しました。' }

[Convert]::ToBase64String([IO.File]::ReadAllBytes($keystore)) | Set-Content -Path $b64File -Encoding ascii -NoNewline

Write-Host ''
Write-Host 'GitHub のリポジトリ → Settings → Secrets and variables → Actions → New repository secret で、次の4つを登録してください。' -ForegroundColor Cyan
Write-Host "  KEYSTORE_BASE64   : $b64File の中身をすべて"
Write-Host '  KEYSTORE_PASSWORD : いま入力したパスワード'
Write-Host "  KEY_ALIAS         : $alias"
Write-Host '  KEY_PASSWORD      : いま入力したパスワード'
Write-Host ''
Write-Host "重要: $keystore とパスワードは必ずバックアップしてください。なくすとアプリのアップデートができなくなります。" -ForegroundColor Red
Write-Host "登録が終わったら $b64File は削除してかまいません（.jks は残してバックアップ）。"
