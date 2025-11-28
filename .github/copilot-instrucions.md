# Copilot Instructions for KotlinAndroidGameBoy

## このドキュメントについて

- GitHub Copilot や各種 AI ツールが本リポジトリのコンテキストを理解しやすくするためのガイドです。
- 新しい機能を実装する際はここで示す技術選定・設計方針・モジュール構成を前提にしてください。
- 不確かな点がある場合は、リポジトリのファイルを探索し、ユーザーに「こういうことですか?」と確認をするようにしてください。

## 前提条件

- 回答は必ず日本語でしてください。
- コードの変更をする際、変更量が200行を超える可能性が高い場合は、事前に「この指示では変更量が200行を超える可能性がありますが、実行しますか?」とユーザーに確認をとるようにしてください。
- 何か大きい変更を加える場合、まず何をするのか計画を立てた上で、ユーザーに「このような計画で進めようと思います。」と提案してください。この時、ユーザーから計画の修正を求められた場合は計画を修正して、再提案をしてください。

## アプリ概要

KotlinAndroidGameBoy は、Android 端末上で Game Boy（DMG）ROM を再生するエミュレータです。初期リリースでは MBC1 カートリッジのポケットモンスター赤がタイトル画面からニューゲーム開始まで動くことを目標にしています。

### 主な機能

- SAF による `.gb` ROM ファイル選択とロード
- `GameBoyCore` による CPU/PPU/メモリ/入力エミュレーション
- Jetpack Compose で構築した `GameScreen`／`VirtualGamepad`
- 実行／一時停止／再開のゲームループ制御
- テスト ROM（CPU/PPU/MBC1）による回帰確認

## 技術スタック概要

- **言語**: Kotlin 2.x 系。`gb-core-*` では `inline` やプリミティブ配列でパフォーマンスを確保
- **ビルド**: Android Gradle Plugin 8.2 系 / Gradle Version Catalog (`gradle/libs.versions.toml`)
- **対応OS**: Min SDK 26, Target & Compile SDK 34
- **依存性注入**: Hilt 未導入。`CoreProvider` で `GameBoyCore` 実装を供給し将来的な差し替えに備える
- **非同期処理**: Kotlin Coroutines。`GameLoop` は `Dispatchers.Default`、ROM 読み込みは `Dispatchers.IO`
- **シリアライゼーション**: なし（ROM はバイト配列として扱う）
- **ネットワーク**: 完全オフライン。`INTERNET` 権限は不要
- **UI**: Jetpack Compose + Material3 テーマ
- **ナビゲーション**: シングルアクティビティ。複数画面化する場合は `Navigation Compose`
- **認証/通知**: いずれも対象外
- **テスト**: `.github/docs/testing.md` に沿ってユニット／テスト ROM／手動テストを実施

## モジュール構成と役割
`.github/docs/architecture.md` で定義した 3 モジュール構成を前提に開発します。現状単一モジュールでも、この構造を念頭にコードを整理してください。

### アプリモジュール

- **app-android**: Android アプリ本体。`MainActivity`, Compose UI, `CoreProvider`, `GameLoop`, `RomLoader` を含み、ROM 読み込みと描画面の管理を担当

### Core モジュール群

- **gb-core-api**: `GameBoyCore`, `InputState`, `FrameResult` などの公開インターフェース
- **gb-core-kotlin**: Kotlin 実装。`GameBoyCoreImpl`, `Cpu`, `Ppu`, `Bus`, `Cartridge/Mbc1`, `Timer`, `Joypad` 等で構成し、`gb-core-api` を実装

### Feature モジュール群

- 現状なし。セーブ／ネットワーク／設定など大きな機能を追加する際は feature として切り離す

### 依存関係の方向

```
gb-core-kotlin -> gb-core-api
app-android    -> gb-core-api, gb-core-kotlin
```

- `app-android` は `gb-core-api` を境界にコアへアクセスし、実装詳細へ直接依存しない
- feature モジュールを作る場合も `gb-core-api` への依存に留める
- core モジュール間は上記一方向依存のみを許可する

## アーキテクチャ指針

### レイヤ構成

- **UI 層**: Compose `MainActivity` / `GameScreen`。`GameLoopState` を受け取り描画するだけに留める
- **ドメイン層**: `GameBoyCore` と `GameLoop`。入力→フレーム生成→結果配信を担う
- **データ層**: `RomLoader` 等の I/O。将来のセーブデータや設定もここで扱う

### 状態管理

- **UIState**: `GameScreenState` にフレームバッファ、FPS、実行状態、エラーをひとまとめにする
- **イベント**: 仮想ボタンからの入力は `InputState` にマッピングし `GameLoop` へ渡す
- **状態ホイスティング**: Compose 内で局所状態を持たず、`ViewModel`/`GameLoop` が発行する `StateFlow` をそのまま props として渡す

### 依存性注入 (CoreProvider)

- Hilt 未導入のため `CoreProvider` が `GameBoyCore` を生成し `GameLoop` へ注入
- 将来的に Hilt を導入する場合も `CoreProvider` 相当を `@Module` 化する

### データフロー

1. ユーザが「ROM を開く」をタップ → SAF から URI 取得
2. `RomLoader` が `ContentResolver` を通じてバイト配列化し、`GameBoyCore.loadRom()` に渡す
3. `GameLoop` がコルーチンで `runFrame()` を連続実行し、`FrameResult` を `StateFlow` で公開
4. Compose UI が `FrameResult` を `ImageBitmap` に変換して描画し、ボタン入力を `InputState` に変換して返す
5. 一時停止は `GameLoop` のジョブを停止／再開することで実現

## UI 実装ガイド

### Compose コンポーネント設計

- `FrameResult` の 160x144 ピクセルを `ImageBitmap` へコピーし、`Modifier.aspectRatio(160f/144f)` で表示
- `VirtualGamepad` は入力ボタン構成を `ButtonSpec` で宣言し、押下状態を `InputState` に変換する拡張関数を用意
- 状態は `GameLoopState`/`GameScreenState` にホイスティングし、Composable 内の `mutableStateOf` を最小限に
- `@Preview` ではダミーのフレームバッファと入力を使い UI レイアウトを確認
- UI テスト用に `testTag`（例: `game_screen_surface`, `virtual_button_a`）を付与

### ナビゲーション

- シングルアクティビティ構成。追加画面が必要になった場合は `Navigation Compose` を採用し `NavHost` を `MainActivity` に置く

### デザインシステム

- Material3 を採用。背景は暗色系でエミュレータ画面を際立たせる
- カラーテーマは `Theme.kt` で定義し、ボタンはテーマカラーに沿う
- タイポグラフィは Material3 デフォルト。設定テキストやエラーメッセージは `bodyMedium`
- アイコンは Compose `Icons` またはカスタムベクタを利用

## データ・ネットワーク層

### 使用する技術

- SAF (`ACTION_OPEN_DOCUMENT`) で ROM の URI を取得
- `ContentResolver.openInputStream()` を `ByteArrayOutputStream` へコピーし、そのまま `GameBoyCore.loadRom()` に渡す
- 永続化ストレージ・ネットワーク通信は未使用。必要になったら Repository 層で抽象化する

### ログ出力

- `.github/docs/logging-monitoring.md` の方針に従う
- デバッグビルド: CPU/PPU/Joypad の詳細ログや命令トレースを opt-in で有効化
- リリースビルド: エラーログのみ出力し、パフォーマンスに影響するログは無効化
- 出力先は Logcat。将来ファイル出力が必要ならユーザが明示的に切り替えられるようにする

## ビルド構成

### Build Types

- **debug**: トレースログや開発者向け設定を有効化。日常開発は `./gradlew :app-android:assembleDebug`
- **release**: 将来の配布用。ログ抑制・最適化・署名を実施（`docs/build-and-release.md` 参照）

```sh
./gradlew :app-android:assembleDebug
```

## コーディング規約・ベストプラクティス

### Kotlin の作法

- `.github/docs/coding-style.md` を準拠元とする
- 不変性を優先し、`FrameResult` や `InputState` などは `val` で再利用
- Null 安全を徹底し、`!!` はテスト以外で禁止
- エミュレータコアはパフォーマンスのためプリミティブ配列／`UByte` 系を多用し、Boxing を避ける
- 共通処理は対象パッケージ（例: `gb.core.mem`）の拡張関数として定義する

### Coroutine の作法

- `GlobalScope` 禁止。`GameLoop` は `CoroutineScope(SupervisorJob() + Dispatchers.Default)` を保持し、ライフサイクルに合わせて cancel
- ROM 読み込みなど I/O は `Dispatchers.IO`、フレーム実行は `Dispatchers.Default`
- `CancellationException` を握りつぶさず UI へ状態を通知
- 例外は `runCatching` / `Result` で包み、ユーザへエラーダイアログや Snackbar を表示

### 命名規則

- 変数/関数: camelCase（例: `runFrame`, `readByte`, `currentBank`）
- クラス/インターフェース: PascalCase（例: `GameBoyCoreImpl`, `FrameBuffer`）
- 定数: UPPER_SNAKE_CASE（例: `CYCLES_PER_FRAME`, `SCREEN_WIDTH`）
- リソース ID: snake_case（例: `game_screen_surface`, `virtual_button_a`）

### コメント

- `gb-core-api` の公開クラス／関数には KDoc を必須とする
- CPU/PPU/MBC など仕様依存のロジックには簡潔なインラインコメントを追加
- 一時的な対応は `// TODO(username): ...` 形式で記載し、追跡しやすくする

## テスト方針

- `.github/docs/testing.md` に準拠し、ユニット／テスト ROM／手動テストを組み合わせる
- CPU 命令・MBC1 切替・PPU 表示などはユニットテストで検証（例: `UT-CPU-1`, `UT-MBC1-1`）
- blargg などのテスト ROM を実行し、指定アドレスの値が "OK" になることを確認
- 手動テストではポケモン赤をロードし、タイトル→ニューゲーム→最初の会話までの表示・操作性を確認

## アンチパターン

以下のパターンは避けてください。既存コードで発見した場合は、リファクタリングを提案してください。

### 状態管理

- **シングルトンでの可変状態保持**: メモリリークやテストの困難さにつながるため、状態は ViewModel や Repository で管理
- **Activity/Fragment での状態保持**: プロセス終了時に消失するため、重要な状態は永続化または `SavedStateHandle` で保存

### 非同期処理

- **GlobalScope の使用**: キャンセル処理が困難なため禁止
- **runBlocking の濫用**: UI スレッドでの使用は ANR の原因となるため禁止
- **コールバック地獄**: Coroutine や Flow で非同期処理を統一

### アーキテクチャ

- **God Class**: 一つのクラスに責務を詰め込まない。単一責任の原則を守る
- **レイヤーの逆転**: UI層から直接 DB や API にアクセスしない。必ず Repository を経由
- **feature 間の直接依存**: feature モジュール間で相互依存しない。共通処理は core に配置

### UI

- **Compose での副作用の誤用**: `LaunchedEffect`, `DisposableEffect`, `SideEffect` を適切に使い分ける
- **Composable 内での状態保持**: 状態は ViewModel で管理し、Composable は状態を受け取るのみ
- **過度なリコンポジション**: `remember`, `derivedStateOf` で不要な再計算を避ける

## セキュリティとプライバシー

- ROM はユーザ所有のファイル。読み込みは SAF を通じて行い、`READ_EXTERNAL_STORAGE` 権限は不要
- ネットワーク通信を行わないため `INTERNET` 権限も付与しない
- 将来クラウド連携を行う場合は API キー／トークンを `local.properties` など Git 管理外で扱う
- デバッグログに ROM の中身や個人情報を出力しない

## まとめ

このドキュメントを常に最新に保ち、新しい技術選定や設計変更があった場合は適宜更新してください。GitHub Copilot や AI ツールは、このドキュメントを参照することで、プロジェクトのコンテキストを正確に理解し、より適切なコード提案を行うことができます。

---

KotlinAndroidGameBoy は、モダンな Android 開発のベストプラクティスを取り入れた、保守性・拡張性の高いアーキテクチャを目指しています。
