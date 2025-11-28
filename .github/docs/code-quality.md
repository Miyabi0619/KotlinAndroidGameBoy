## コード品質チェック指針

このドキュメントでは、KotlinAndroidGameBoy リポジトリで利用する `ktlint` / `detekt` / CI のルールと実行方法をまとめる。

### 1. ktlint

- 使用プラグイン: `org.jlleitschuh.gradle.ktlint` (v12.1.1)
- 全モジュールに自動適用され、`ktlintCheck` / `ktlintFormat` タスクが利用可能。
- 設定方針:
  - `android = true`（Android 向け Kotlin コードスタイル基準）
  - 失敗時はビルドを止める (`ignoreFailures = false`)
  - レポート: `plain` / `checkstyle`
  - `build/` や `generated/` ディレクトリはスキャン対象外

**手動実行例**

```sh
./gradlew ktlintCheck        # スタイル検査
./gradlew ktlintFormat       # 自動整形
```

### 2. detekt

- 使用プラグイン: `io.gitlab.arturbosch.detekt` (v1.23.7)
- `detekt.yml`（リポジトリルート）で設定を管理。`buildUponDefaultConfig = true` なので Gradle のデフォルトルールに上書きする形。
- 主なカスタマイズ:
  - `maxIssues = 0`（検出された違反があれば失敗）
  - `MaxLineLength = 140`
  - `MagicNumber` は一旦無効化（ハードウェア実装で定数が多いため）
  - `ForbiddenComment` に `FIXME` / `STOPSHIP` を登録（`TODO(username):` 形式のみ許可）
  - HTML レポートを `build/reports/detekt/detekt.html` に出力

**手動実行例**

```sh
./gradlew detekt
```

### 3. CI（GitHub Actions）

- ワークフロー: `.github/workflows/lint.yml`
- トリガー: `main` ブランチへの push、全 PR
- 実行内容:
  1. `actions/checkout`
  2. `actions/setup-java`（Temurin 21）
  3. `gradle/actions/setup-gradle`
  4. `./gradlew ktlintCheck detekt`

CI で失敗した場合は、ローカルで同じコマンドを実行し、スタイル修正またはルール設定のアップデートを検討する。

