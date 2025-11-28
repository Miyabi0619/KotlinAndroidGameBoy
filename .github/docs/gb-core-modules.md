## gb-core モジュール設計メモ

このドキュメントは、`gb-core-api` / `gb-core-kotlin` / `app` の役割と依存関係、  
およびコード実装時に前提とする公開 API をまとめたものです。

### 1. モジュール構成と依存方向

- **app**
  - Android アプリ本体（Compose UI, `GameLoop`, `RomLoader`, `CoreProvider` 等）。
  - `GameBoyCore` を利用する側。
  - 依存: `gb-core-api`, `gb-core-kotlin`。

- **gb-core-api**
  - エミュレータコアの **公開インターフェース** と **DTO（データクラス）** を定義するモジュール。
  - Android / UI への依存は持たない（コード上で Android クラスを import しない）。
  - 依存: Kotlin 標準ライブラリのみ（ビルド上は Android ライブラリだが、実装はプラットフォーム非依存を維持する）。

- **gb-core-kotlin**
  - `gb-core-api` を実装する **エミュレータコア実装モジュール**。
  - CPU / PPU / Timer / Joypad / MBC1 など、ハードウェアロジックをすべて含む。
  - Android / UI 依存なし。
  - 依存: `gb-core-api`, Kotlin 標準ライブラリ, 必要に応じて `kotlinx.coroutines` 等の汎用ライブラリ。

依存関係の方向は次の通り固定する。

```text
gb-core-kotlin -> gb-core-api
app            -> gb-core-api, gb-core-kotlin
```

### 2. gb-core-api の設計

#### 2.1 役割

- エミュレータコアに対する「ホスト側から見た操作インターフェース」を定義する。
- UI やプラットフォーム固有の関心事（View, Context, ファイル I/O 等）は含めない。
- コア実装 (`gb-core-kotlin`) とホスト (`app`) 間の契約を明文化する。

#### 2.2 パッケージ方針

- ベースパッケージ例: `gb.core.api`
  - 実際の `android.namespace` は `gb.core.api` としつつ、クラスもこのパッケージ配下に配置する。

#### 2.3 主要公開インターフェース・データ構造（ドラフト）

実装しやすさと仕様書の要件を両立するため、当面は次の API を前提とする。
必要に応じて段階的に拡張する。

```kotlin
package gb.core.api

/**
 * Game Boy エミュレータコアの公開インターフェース。
 * Android / UI 非依存の純粋な論理モデルを提供する。
 */
interface GameBoyCore {

    /**
     * ROM データをロードする。
     *
     * @param rom 生の ROM バイト列（.gb ファイルの内容）。
     */
    fun loadRom(rom: ByteArray): CoreResult<Unit>

    /**
     * コア内部状態を初期化する（電源 ON / リセット相当）。
     */
    fun reset()

    /**
     * 1 フレーム分エミュレーションを進める。
     *
     * @param input 現在の入力状態（ボタンの押下情報）。
     * @return 描画結果とデバッグ情報などを含むフレーム結果。
     */
    fun runFrame(input: InputState): CoreResult<FrameResult>

    /**
     * セーブステートを作成する。
     * 初期実装では必須ではないが、拡張性のために API としては確保しておく。
     */
    fun saveState(): CoreResult<SaveState>

    /**
     * セーブステートから復元する。
     */
    fun loadState(state: SaveState): CoreResult<Unit>
}
```

```kotlin
/**
 * ボタン入力の状態。
 * UI 層はユーザのタッチイベントなどをこの構造に変換してコアへ渡す。
 */
data class InputState(
    val a: Boolean = false,
    val b: Boolean = false,
    val select: Boolean = false,
    val start: Boolean = false,
    val up: Boolean = false,
    val down: Boolean = false,
    val left: Boolean = false,
    val right: Boolean = false
)
```

```kotlin
/**
 * 1 フレーム分のエミュレーション結果。
 */
data class FrameResult(
    /**
     * 画面フレームバッファ。
     *
     * - フォーマットは 160x144 のピクセル列。
     * - 具体的なフォーマットは以下いずれかで実装を検討:
     *   - ARGB8888 の IntArray（1 ピクセル 1 Int）
     *   - Game Boy 固有パレットインデックスの UByteArray（UI 側で色に変換）
     */
    val frameBuffer: IntArray,

    /**
     * デバッグ用統計情報（FPS, 経過サイクル数など）。
     * UI では任意表示。
     */
    val stats: FrameStats? = null
)
```

```kotlin
data class FrameStats(
    val frameIndex: Long,
    val cpuCycles: Long,
    val fpsEstimate: Double?
)
```

```kotlin
/**
 * セーブステート。
 * 実装側では内部状態をシリアライズしたバイト列として保持する。
 */
data class SaveState(
    val data: ByteArray
)
```

```kotlin
/**
 * コアから返される結果型。
 * Android の Result とは独立した、自前の軽量ラッパー。
 */
sealed class CoreResult<out T> {
    data class Success<T>(val value: T) : CoreResult<T>()
    data class Error(val error: CoreError) : CoreResult<Nothing>()
}

/**
 * コア内部で発生しうるエラー種別。
 */
sealed class CoreError {
    object RomNotLoaded : CoreError()
    data class InvalidRom(val reason: String) : CoreError()
    data class IllegalState(val message: String) : CoreError()
    data class InternalError(val throwable: Throwable) : CoreError()
}
```

#### 2.4 追加 API の判断基準

新しい操作やデータを `gb-core-api` に追加すべきかどうかは、以下で判断する。

- **UI / app から呼び出したいか？**
  - 例: 「リセット」「セーブ」「ロード」「デバッグ情報取得」など。
- **他プラットフォーム（Desktop 等）でも同じ操作が必要になりそうか？**
  - Android 固有でない汎用操作は API に昇格させる価値が高い。
- **コア内部の詳細を隠蔽できているか？**
  - CPU のレジスタ構造やメモリマップの詳細は基本的に `gb-core-kotlin` 内に留め、  
    外から本当に必要なものだけを公開する。
- **仕様書に明示された要件か？**
  - 仕様書の要件（例: 「MBC1 のバンク切り替え」「Timer 割り込み」）を実現する上で  
    ホスト側から操作が必要な場合は API を検討する。

これらを満たさない「内部専用」の関数やデータは `gb-core-kotlin` 内に閉じ込める。

### 3. gb-core-kotlin の設計

#### 3.1 役割

- `GameBoyCore` を実装し、仕様書に定義された Game Boy の挙動を再現する。
- Android / UI に依存しない純 Kotlin 実装とし、将来的に Desktop 等への移植も見据える。

#### 3.2 パッケージ構成案

ベースパッケージ例: `gb.core.impl`

- `gb.core.impl`
  - `GameBoyCoreImpl` : `GameBoyCore` の実装クラス。
  - `CoreConfig` : 動作モードやデバッグフラグなどの設定。
- `gb.core.impl.cpu`
  - `Cpu`, `InstructionDecoder`, `Registers` など。
- `gb.core.impl.ppu`
  - `Ppu`, `LcdController`, `Renderer`, `Palette` など。
- `gb.core.impl.mem`
  - `Bus`, `WorkRam`, `VideoRam`, `Oam`, `IoRegisters` など。
- `gb.core.impl.cartridge`
  - `Cartridge`, `Mbc1`, `RomHeader` など。
- `gb.core.impl.timer`
  - `Timer`, `DivRegister` など。
- `gb.core.impl.interrupt`
  - `InterruptController`（IME / IF / IE 管理）。
- `gb.core.impl.joypad`
  - `Joypad`（`InputState` を内部のレジスタ表現に変換）。

#### 3.3 GameBoyCoreImpl の責務（概要）

- `loadRom(rom: ByteArray)`
  - `Cartridge` / `Mbc1` を初期化し、ROM ヘッダ検証やバンク構成の計算を行う。
  - ROM 不正時は `CoreResult.Error(InvalidRom)` を返す。
- `reset()`
  - CPU レジスタ、メモリ、PPU、Timer、割り込みフラグ等を電源 ON 相当の状態に戻す。
- `runFrame(input: InputState)`
  - `Joypad` に入力状態を反映。
  - 仕様書の `CYCLES_PER_FRAME` (= 約 70224 サイクル) を基準に、CPU/PPU/Timer/APU をサイクル単位で進める。
  - フレーム終了時点の画面バッファを `FrameResult.frameBuffer` に詰めて返す。
  - デバッグ用にサイクル数やフレーム番号などを `FrameStats` として付加する。
- `saveState() / loadState(state)`
  - 将来的に CPU/PPU/メモリのスナップショットをバイト列として保存・復元する。
  - 初期実装では未実装として `CoreResult.Error(IllegalState)` を返すことも許容する（UI では非対応機能として扱う）。

### 4. app 側の GameLoop と CoreProvider

#### 4.1 GameLoop の役割

- `GameBoyCore` を一定周期で呼び出し、UI へ `FrameResult` を配信する。
- 実行／一時停止／再開／リセットを制御しつつ、Android のライフサイクルと連携する。

典型的な責務:

- `start(rom: ByteArray)`
  - ROM をロードし、`reset()` してからゲームループを起動。
- `pause() / resume()`
  - コルーチンジョブのキャンセル・再作成などでループを止めたり再開したりする。
- `updateInput(input: InputState)`
  - 最新の入力状態を保持し、次の `runFrame` 呼び出し時に渡す。
- `frames: StateFlow<FrameResult?>`
  - Compose UI が collect して描画に使うストリーム。

#### 4.2 CoreProvider の役割

- `GameBoyCore` 実装インスタンスの生成を一箇所に集約し、将来の差し替え（テスト用モック、別実装など）を容易にする。

例:

```kotlin
interface CoreProvider {
    fun createCore(): GameBoyCore
}
```

Android アプリ本体では、Hilt 未導入の間はシングルトン的なオブジェクトまたは簡易 DI で提供し、  
将来的に DI フレームワークを導入する場合もこのインターフェースをモジュールに置き換える。

### 5. 「正しい API / コンポーネントか」を判断するためのチェックリスト

1. **UI から見て自然な操作か？**
   - 「この操作が欲しい」と UI 側で感じたら `gb-core-api` を検討する。
2. **プラットフォーム非依存か？**
   - Android クラス (`Context`, `Uri`, `Bitmap` など) を直接扱うなら `app` 側に留める。
3. **仕様書の要件と結びついているか？**
   - 仕様書に書かれている CPU/PPU/MBC/Timer/割り込み/入力の要件を満たすために必要な操作であること。
4. **内部実装の詳細を漏らしていないか？**
   - レジスタ構造やメモリマップなど、差し替えが難しくなる情報を API で直接公開していないか確認する。
5. **将来的な拡張に耐えられるか？**
   - 例: セーブ/ロード機能を今後実装したくなったとき、API を大きく壊さずに追加できるか。

このチェックリストを満たすものは `gb-core-api` に、そうでないものは `gb-core-kotlin`（内部実装）または `app`（UI / I/O / ライフサイクル管理）に配置する。


