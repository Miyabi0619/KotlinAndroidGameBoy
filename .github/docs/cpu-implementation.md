# Game Boy CPU 実装ガイド

このドキュメントは、Game Boy エミュレータの CPU（LR35902）を実装するための詳細な解説と設計方針をまとめたものです。  
CPU についての知識がなくても理解できるよう、用語説明から段階的に進めます。

---

## 目次

1. [Game Boy CPU（LR35902）とは](#1-game-boy-cpulr35902とは)
2. [CPU の基本構造](#2-cpuの基本構造)
3. [命令実行の流れ](#3-命令実行の流れ)
4. [実装の設計方針](#4-実装の設計方針)
5. [最初の命令実装（NOP）](#5-最初の命令実装nop)
6. [次のステップ](#6-次のステップ)

---

## 1. Game Boy CPU（LR35902）とは

### 1.1 概要

- **LR35902** は、Game Boy（DMG）に搭載されている CPU の型番です。
- **8bit CPU** で、Z80 という古い CPU をベースに、Game Boy 専用の命令を追加したものです。
- アドレス空間は **16bit**（0x0000 ～ 0xFFFF、つまり 65536 バイト）です。

### 1.2 CPU の役割

CPU は、ROM に書かれた「プログラム（命令列）」を読み取り、順番に実行していく装置です。

例：
- ROM の 0x0100 番地に `0x00`（NOP 命令）が書かれている
- CPU はそれを読み取り、「何もしない」という処理を実行する
- 次の 0x0101 番地の命令に進む

この繰り返しで、ゲームのロジックが動いていきます。

---

## 2. CPU の基本構造

CPU は、**レジスタ** と **フラグ** という「内部の記憶領域」を持っています。  
これらは、計算の途中結果や状態を保存するために使われます。

### 2.1 レジスタ（Register）

レジスタは、CPU 内部の「小さな記憶領域」です。  
Game Boy の CPU には、以下のレジスタがあります。

#### 8bit レジスタ（1バイト = 0 ～ 255 の値を保存できる）

- **A（Accumulator）**: 演算の結果を保存する「主レジスタ」
- **B, C, D, E, H, L**: 汎用レジスタ（計算やメモリアドレスの指定に使う）

#### 16bit レジスタ（2バイト = 0 ～ 65535 の値を保存できる）

- **AF**: A と F（フラグ）を組み合わせた 16bit レジスタ
- **BC**: B と C を組み合わせた 16bit レジスタ
- **DE**: D と E を組み合わせた 16bit レジスタ
- **HL**: H と L を組み合わせた 16bit レジスタ（メモリアドレス指定でよく使う）
- **PC（Program Counter）**: 次に実行する命令のアドレスを指す（重要！）
- **SP（Stack Pointer）**: スタック（一時的なデータ保存領域）の先頭を指す

#### レジスタのイメージ図

```
8bit レジスタ:
┌─────┬─────┬─────┬─────┬─────┬─────┐
│  A  │  B  │  C  │  D  │  E  │ ... │
└─────┴─────┴─────┴─────┴─────┴─────┘
 0-255 0-255 0-255 0-255 0-255

16bit レジスタ（例: BC）:
┌─────────────┬─────────────┐
│      B      │      C      │
│   (上位8bit) │  (下位8bit) │
└─────────────┴─────────────┘
     0 ～ 65535 の値を保存
```

### 2.2 フラグ（Flag）

フラグは、**計算結果の状態**を記録する 1bit のフラグです。  
Game Boy の CPU には、以下の 4 つのフラグがあります（F レジスタに格納）。

- **Z（Zero）**: 計算結果が 0 になったか
- **N（Negative）**: 減算命令を実行したか（加算と区別するため）
- **H（Half Carry）**: 下位 4bit から上位 4bit への桁上がりが発生したか
- **C（Carry）**: 8bit の範囲を超えて桁上がりが発生したか（例: 255 + 1 = 0 になる）

#### フラグの例

```
A = 0x10 (16)
B = 0x20 (32)
A + B = 0x30 (48)

→ 結果は 0 ではない → Z = 0
→ 加算 → N = 0
→ 下位4bit: 0 + 0 = 0 → H = 0
→ 48 < 255 → C = 0
```

### 2.3 メモリマップ

CPU は、メモリ（ROM、RAM など）にアクセスできます。  
Game Boy のメモリマップは以下の通りです。

```
0x0000 ～ 0x3FFF: ROM バンク 0（固定）
0x4000 ～ 0x7FFF: ROM バンク 1～N（切り替え可能）
0x8000 ～ 0x9FFF: VRAM（画面用メモリ）
0xA000 ～ 0xBFFF: 外部 RAM（セーブデータなど）
0xC000 ～ 0xDFFF: WRAM（作業用 RAM）
0xE000 ～ 0xFDFF: ECHO RAM（WRAM のミラー）
0xFE00 ～ 0xFE9F: OAM（スプライト情報）
0xFF00 ～ 0xFF7F: I/O レジスタ（PPU、タイマー、Joypad など）
0xFF80 ～ 0xFFFE: HRAM（高速 RAM）
0xFFFF: IE（割り込み有効化レジスタ）
```

---

## 3. 命令実行の流れ

CPU は、以下の 3 ステップで 1 つの命令を実行します。

### 3.1 フェッチ（Fetch）

**PC が指しているアドレスから、命令コード（1 バイトまたは 2 バイト）を読み取る**

例：
- PC = 0x0100
- メモリの 0x0100 番地に `0x00` が書かれている
- → 命令コード `0x00` を取得

### 3.2 デコード（Decode）

**命令コードを見て、「この命令は何をするのか」を判断する**

例：
- `0x00` → 「NOP（何もしない）」
- `0x3E` → 「LD A, n（A レジスタに即値 n をロード）」

### 3.3 実行（Execute）

**デコードした内容に従って、実際の処理を行う**

例（NOP の場合）：
- 何もしない
- PC を 1 進める（次の命令に進む）

例（LD A, n の場合）：
- 次の 1 バイト（即値 n）を読み取る
- A レジスタに n を書き込む
- PC を 2 進める（命令 1 バイト + 即値 1 バイト = 2 バイト分進む）

### 3.4 サイクル数

各命令は、実行に必要な「クロックサイクル数」を持っています。  
Game Boy の CPU は約 4.19 MHz で動作するため、1 サイクル = 約 0.24 マイクロ秒です。

例：
- NOP: 4 サイクル
- LD A, n: 8 サイクル（命令読み取り 4 サイクル + 即値読み取り 4 サイクル）

**エミュレータでは、このサイクル数を正確にカウントすることが重要です。**  
PPU やタイマーは、CPU のサイクル数に同期して動作するためです。

---

## 4. 実装の設計方針

### 4.1 クラス構成

Kotlin で CPU を実装する場合、以下のようなクラス構成を推奨します。

```
gb.core.impl.cpu/
├── Cpu.kt              # CPU のメインクラス（命令実行の制御）
├── Registers.kt        # レジスタとフラグを管理
├── Instruction.kt      # 命令の種類を表す enum/sealed class
├── InstructionDecoder.kt  # 命令コードから命令を判定
└── instructions/       # 各命令の実装
    ├── Nop.kt
    ├── Load.kt
    └── ...
```

### 4.2 レジスタの実装方針

レジスタは、**データクラス**または**クラス**で管理します。

```kotlin
data class Registers(
    var a: UByte = 0u,      // 8bit レジスタ
    var b: UByte = 0u,
    var c: UByte = 0u,
    var d: UByte = 0u,
    var e: UByte = 0u,
    var h: UByte = 0u,
    var l: UByte = 0u,
    var pc: UShort = 0u,    // 16bit レジスタ
    var sp: UShort = 0u,
    // フラグ
    var flagZ: Boolean = false,
    var flagN: Boolean = false,
    var flagH: Boolean = false,
    var flagC: Boolean = false,
)
```

**16bit レジスタ（BC, DE, HL）の扱い**:
- BC は `(b.toInt() shl 8) or c.toInt()` で 16bit 値として扱える
- 逆に、16bit 値を BC に書き込む場合は `b = (value shr 8).toUByte(); c = (value and 0xFF).toUByte()`

### 4.3 命令の実装方針

各命令は、**関数**または**オブジェクト**として実装します。

```kotlin
// 例: NOP 命令
fun executeNop(cpu: Cpu): Int {
    // 何もしない
    // 戻り値はサイクル数（4 サイクル）
    return 4
}
```

### 4.4 メモリアクセス

CPU は、メモリにアクセスするために **Bus** クラスを経由します。

```kotlin
interface Bus {
    fun readByte(address: UShort): UByte
    fun writeByte(address: UShort, value: UByte)
}
```

CPU は、この Bus を通じて ROM、RAM、I/O レジスタなどにアクセスします。

---

## 5. 最初の命令実装（NOP）

### 5.1 NOP とは

**NOP（No Operation）** は、「何もしない」命令です。  
命令コードは `0x00` で、4 サイクルかかります。

### 5.2 実装の流れ

1. **Registers クラスを作成**
   - レジスタとフラグを管理するクラス

2. **Cpu クラスの骨格を作成**
   - Registers と Bus を持ち、`executeInstruction()` メソッドを用意

3. **NOP 命令を実装**
   - `0x00` が来たら「何もしない + PC を 1 進める + 4 サイクル返す」

4. **簡単なテストを書く**
   - PC が正しく進むか確認

### 5.3 実装例（イメージ）

```kotlin
// Registers.kt
data class Registers(
    var pc: UShort = 0u,
    // ... 他のレジスタ
)

// Cpu.kt
class Cpu(
    private val bus: Bus,
) {
    val registers = Registers()

    fun executeInstruction(): Int {
        val opcode = bus.readByte(registers.pc).toInt()
        registers.pc = (registers.pc.toInt() + 1).toUShort()

        return when (opcode) {
            0x00 -> executeNop()
            else -> throw IllegalStateException("Unknown opcode: 0x${opcode.toString(16)}")
        }
    }

    private fun executeNop(): Int {
        // 何もしない
        return 4 // 4 サイクル
    }
}
```

---

## 6. 次のステップ

1. **Registers クラスを実装**
   - すべてのレジスタとフラグを含める

2. **Cpu クラスの骨格を実装**
   - Bus インターフェースを定義（最初はスタブ実装で OK）
   - `executeInstruction()` メソッドを用意

3. **NOP 命令を実装**
   - `0x00` の処理を追加

4. **簡単なテストを書く**
   - PC が 1 進むことを確認

5. **次の命令を実装**
   - LD A, n（`0x3E`）など、簡単な命令から順に追加

---

## 7. 実装進捗ログ（2025-11-28）

### 7.1 Registers / Bus / Cpu の骨格（現状）

- `gb.core.impl.cpu.Registers`
  - 8bit レジスタ、PC/SP、フラグ (Z/N/H/C) を保持
  - 16bit ペアレジスタ（BC/DE/HL/AF）を get/set できるプロパティを追加
- `gb.core.impl.cpu.Bus`
  - CPU がメモリにアクセスするためのインターフェース
- `gb.core.impl.cpu.Cpu`
  - `executeInstruction()` を実装し、フェッチ → デコード → 実行 を 1 メソッド内で完結
  - 命令ごとのサイクル数を `Cycles` オブジェクトで管理

### 7.2 実装済みの命令一覧（2025-11-29 時点）

現在の CPU 実装では、以下の命令が実装済みで、すべてユニットテストで動作確認済みです。

- **制御系**
  - `0x00` **NOP**: 何もしない（PC+1, 4 cycles）

- **即値ロード**
  - `0x3E` **LD A, n**: `A ← 即値 n`（PC+2, 8 cycles）

- **8bit インクリメント**
  - `0x3C` **INC A**: `A ← A + 1`
    - フラグ: `Z`/`H` 更新、`N=0`、`C` は変更しない

- **16bit インクリメント / デクリメント**
  - 共通ヘルパー: `executeInc16(get, set)`, `executeDec16(get, set)`
    - いずれも **フラグは一切変更しない**（Game Boy 仕様）
  - `0x03` **INC BC**
  - `0x0B` **DEC BC**
  - `0x13` **INC DE**
  - `0x1B` **DEC DE**
  - `0x23` **INC HL**
  - `0x33` **INC SP**
  - `0x3B` **DEC SP**

- **レジスタ間コピー（8bit）**
  - ヘルパー: `executeLdRegister(setTarget, source)`（フラグは変更しない）
  - A ↔ 他レジスタ:
    - **A → 他レジスタ**
      - `0x47` **LD B, A**
      - `0x4F` **LD C, A**
      - `0x57` **LD D, A**
      - `0x5F` **LD E, A**
      - `0x67` **LD H, A**
      - `0x6F` **LD L, A**
    - **他レジスタ → A**
      - `0x78` **LD A, B**
      - `0x79` **LD A, C**
      - `0x7A` **LD A, D**
      - `0x7B` **LD A, E**
      - `0x7C` **LD A, H**
      - `0x7D` **LD A, L**

- **HL 経由のメモリアクセス**
  - Bus 経由で `[HL]` を読む / 書く命令群。いずれもフラグは変更しない。
  - 単発アクセス:
    - `0x7E` **LD A, (HL)**: `A ← [HL]`
    - `0x77` **LD (HL), A**: `[HL] ← A`
    - `0x46` **LD B, (HL)**: `B ← [HL]`
    - `0x70` **LD (HL), B**: `[HL] ← B`
  - 自動インクリメント付きアクセス:
    - `0x22` **LD (HL+), A**: `[HL] ← A; HL ← HL + 1`
    - `0x2A` **LD A, (HL+)**: `A ← [HL]; HL ← HL + 1`

これらについては、`CpuTest` で PC の進み方、レジスタ値、メモリアクセス結果、フラグの変化有無、サイクル数をそれぞれ検証済みです。

### 7.3 テスト用 Bus と NOP テスト

- `app/gb-core-kotlin/src/test/java/gb/core/impl/cpu/CpuTest.kt`
  - `InMemoryBus`（`UByteArray` で 64KB メモリを模倣）を使ったユニットテスト
  - `executeInstruction()` が 4 サイクルを返し、PC が 1 バイト進むことを確認
- 実行コマンド

```sh
./gradlew :app:gb-core-kotlin:test
```

この状態で「最小の CPU + バス + テスト」が整い、さらに上記の基本命令群（ロード／インクリメント／レジスタコピー）が加わったので、ここから命令セットを順に実装していく。

### 7.4 今後実装していく予定の LD 系命令（レジスタ間コピー）

レジスタ間 `LD` 命令は、Z80 系らしく **8×8 のマトリクス（r ← r'）** のような構造になっています。  
現在は「A ↔ その他」と「B ↔ (HL)」のみ実装済みで、それ以外はこれから実装していきます。

- **まだ未実装の主な `LD r, r'` グループ（例）**
  - B/C/D/E/H/L 間のコピー
    - 例: `LD B, C`, `LD D, E`, `LD H, L` など
  - `(HL)` を絡めたコピーの残り
    - 例: `LD C, (HL)`, `LD (HL), C`, `LD D, (HL)`, `LD (HL), D` など
  - 即値ロードの残り
    - 例: `LD B, n`, `LD C, n`, `LD D, n`, `LD E, n`, `LD H, n`, `LD L, n`
  - 自動デクリメント付き HL アクセス
    - `LD (HL-), A`, `LD A, (HL-)`（`(HL+)` の対になる命令）

これらはすべて、

- 「**レジスタまたはメモリから 1 バイト読み取って、別のレジスタ or [HL] に書き込むだけ**」
- 「**フラグは変更しない（LD 系は演算を伴わないため）**」

という共通ルールに従うため、既存のヘルパー（`executeLdRegister` / `executeLdRegisterFromHL` / `executeLdHLFromRegister`）を再利用しつつ、  
`executeByOpcode` に opcode と対応するターゲット／ソースレジスタの組み合わせを順に追加していく方針で実装していく。

### 7.5 CB プレフィックス命令（ローテート／シフト／BIT/RES/SET）（2025-11-29）

CB プレフィックス (`0xCB`) に続く 1 バイトを「拡張オペコード」として扱う命令群を、  
**RLC/RRC/RL/RR/SLA/SRA/SWAP/SRL と BIT/RES/SET（r, (HL)）まで一括で実装した。**

#### 7.5.1 CB プレフィックスの扱い

- メインの `executeInstruction()` で `0xCB` を検出したら `executeCbPrefixed()` を呼び出す。
- `executeCbPrefixed()` は
  - `cbOpcode = [PC]` を読み、
  - `PC ← PC+1`（拡張 opcode を読み飛ばす）、
  - `executeCbByOpcode(cbOpcode)` にディスパッチする。

CB opcode はビット単位で分解して解釈している:

- `group = cbOpcode[7:6]` … 上位 2bit でグループを判定
  - `00` … ローテート／シフト（RLC/RRC/RL/RR/SLA/SRA/SWAP/SRL）
  - `01` … `BIT b, r / BIT b, (HL)`
  - `10` … `RES b, r / RES b, (HL)`
  - `11` … `SET b, r / SET b, (HL)`
- `y = cbOpcode[5:3]` … ビット番号（BIT/RES/SET）またはローテート種別
- `z = cbOpcode[2:0]` … 対象レジスタ
  - `0=B, 1=C, 2=D, 3=E, 4=H, 5=L, 6=(HL), 7=A`

この 3 つの値を使って、`executeCbRotateShift(y,z)` / `executeCbBit(y,z)` / `executeCbRes(y,z)` / `executeCbSet(y,z)` に分岐する。

#### 7.5.2 ローテート／シフト（RLC/RRC/RL/RR/SLA/SRA/SWAP/SRL）

ローテート／シフトは `group=0`（上位 2bit=00）の CB 命令に対応し、  
`y` の値で演算種別を切り替える。

- 対応表:
  - `y=0` … `RLC r` / `RLC (HL)`（左ローテート、bit7 → C と bit0）
  - `y=1` … `RRC r` / `RRC (HL)`（右ローテート、bit0 → C と bit7）
  - `y=2` … `RL r` / `RL (HL)`（C を巻き込みつつ左ローテート）
  - `y=3` … `RR r` / `RR (HL)`（C を巻き込みつつ右ローテート）
  - `y=4` … `SLA r` / `SLA (HL)`（算術左シフト、下位ビットは 0）
  - `y=5` … `SRA r` / `SRA (HL)`（算術右シフト、bit7 を保持）
  - `y=6` … `SWAP r` / `SWAP (HL)`（上位4bitと下位4bitを入れ替え）
  - `y=7` … `SRL r` / `SRL (HL)`（論理右シフト、bit7 に 0）

フラグ挙動（共通）:

- `Z`: 結果が 0 のとき 1
- `N`: 0
- `H`: 0
- `C`: シフト／ローテートでこぼれたビット（左シフトなら元の bit7、右シフトなら元の bit0）

サイクル数:

- 対象がレジスタ `r`（B,C,D,E,H,L,A）の場合: 8 サイクル
- 対象が `(HL)` の場合: 16 サイクル（メモリアクセスを伴うため）

#### 7.5.3 BIT b, r / BIT b, (HL)

`group=1`（上位 2bit=01）の CB 命令は、指定ビットのテストを行う `BIT b, r/(HL)` である。

- 対象:
  - `b = y`（0〜7）
  - `r/(HL)` は `z` から決まる（0=B,...,6=(HL),7=A）
- 動作:
  - `mask = 1 << b`
  - `value` の該当ビットをチェックし、フラグを更新:
    - `Z`: 対象ビットが 0 なら 1、1 なら 0
    - `N`: 0
    - `H`: 1
    - `C`: 変更なし
  - レジスタやメモリの値自体は不変。
- サイクル数:
  - `BIT b, r`（レジスタ）: 8
  - `BIT b, (HL)`: 12

#### 7.5.4 RES b, r / RES b, (HL)

`group=2`（上位 2bit=10）は、ビットクリア命令 `RES b, r/(HL)` に対応する。

- 動作:
  - `mask = ~(1 << b)`
  - `result = value & mask`
  - レジスタ／メモリへ `result` を書き戻す。
- フラグ:
  - 仕様上、**変更しない**（フラグはそのまま）。
- サイクル数:
  - レジスタ: 8
  - `(HL)`: 16

#### 7.5.5 SET b, r / SET b, (HL)

`group=3`（上位 2bit=11）は、ビットセット命令 `SET b, r/(HL)` に対応する。

- 動作:
  - `mask = 1 << b`
  - `result = value | mask`
  - レジスタ／メモリへ `result` を書き戻す。
- フラグ:
  - こちらも **変更しない**。
- サイクル数:
  - レジスタ: 8
  - `(HL)`: 16

#### 7.5.6 実装状況（CB 系の進捗）

- 実装済み:
  - CB プレフィックスデコード (`executeCbPrefixed` / `executeCbByOpcode`)
  - シフト／ローテート全種（RLC/RRC/RL/RR/SLA/SRA/SWAP/SRL） for `r` と `(HL)`
  - `BIT b, r` / `BIT b, (HL)`（b=0〜7, r=B,C,D,E,H,L,A,HL）
  - `RES b, r` / `RES b, (HL)`
  - `SET b, r` / `SET b, (HL)`
- 今後必要に応じて追加・確認するもの:
  - ACE で特に多用される CB 命令については、テストケースを個別に増やしておく（今は代表パターンでカバー）。
  - 必要なら、CB 系全体を表に起こした簡易リファレンスを別ドキュメントに整理する。

---

### 7.6 フロー制御（JP / JR / CALL / RET / RST）（2025-11-29）

フェーズ2として、**条件分岐・サブルーチン呼び出し・割り込みベクタ飛び**に関わるフロー制御命令を実装した。  
これにより、ポケモン赤のメインループや ACE で多用される「条件付きジャンプ → サブルーチン呼び出し → RST による固定アドレスジャンプ」が CPU 上で一通り表現できる状態になっている。

#### 7.6.1 条件判定ヘルパー `Condition` / `checkCondition`

すでに CPU クラス内にあった `Condition` enum（`NZ/Z/NC/C`）を流用し、  
`JP/JR/CALL/RET` から共通で使えるヘルパー関数 `checkCondition(cond: Condition)` を追加した。

- `Condition.NZ` → `!flagZ`（Z フラグが 0 のとき真）
- `Condition.Z` → `flagZ`
- `Condition.NC` → `!flagC`
- `Condition.C` → `flagC`

これにより、**opcode デコード側（`executeByOpcode`）では「どの条件を使うか」だけを書き、  
実際のフラグ判定ロジックは 1 箇所に集約**されている。

#### 7.6.2 汎用 16bit 読み出し `readWordAt`

JP/CALL などが共通で使う「即値 16bit 読み出し」のために、  
`readWordAt(address: UShort): UShort` を追加した。

- 動作:
  - `low = [address]`
  - `high = [address + 1]`
  - 戻り値は `(high << 8) | low`（リトルエンディアン）
- 用途:
  - `JP nn` / `JP cc, nn` のジャンプ先 `nn`
  - `CALL nn` / `CALL cc, nn` の呼び出し先 `nn`

#### 7.6.3 相対ジャンプ用の `signExtend`

`JR e` / `JR cc, e` で使う **符号付き 8bit オフセット**を Int へ拡張するため、  
`signExtend(offset: UByte): Int` を導入した。

- 0x00〜0x7F → そのまま 0〜127
- 0x80〜0xFF → -128〜-1 になるように上位ビットを 1 で埋めて拡張
- 実際の新しい PC は  
  - 「**即値 1 バイトを読み飛ばしたあとのアドレス**」＋ `signExtend(e)`  
  - という形で計算している（Game Boy 仕様に合わせた実装）。

#### 7.6.4 スタック操作ヘルパー `pushWord` / `popWord`

CALL/RET/RST の共通処理として、16bit 単位の PUSH/POP ヘルパーを追加した。

- `pushWord(value: UShort)`
  - `spBefore = SP`
  - `high = value 上位 8bit`, `low = 下位 8bit`
  - `[SP-1] ← high`, `[SP-2] ← low`
  - `SP ← SP-2`
  - → **スタックは高アドレスから低アドレスへ伸びる** Game Boy 仕様に合わせている。
- `popWord(): UShort`
  - `low = [SP]`, `high = [SP+1]`
  - `SP ← SP+2`
  - 戻り値は `(high << 8) | low`

RST/CALL/RET からは「スタックに積む／戻りアドレスを読む」という高レベルな意図だけを書き、  
実際のアドレス計算・書き込みはこのヘルパーに任せる形になっている。

#### 7.6.5 JP nn / JP cc, nn

- 主な opcode:
  - `0xC3` … `JP nn`（無条件）
  - `0xC2` … `JP NZ, nn`
  - `0xCA` … `JP Z, nn`
  - `0xD2` … `JP NC, nn`
  - `0xDA` … `JP C, nn`
- フォーマット: `[OPCODE][low][high]`
- 動作:
  - 即値アドレス `nn` は `readWordAt(PC)` で取得（`PC` は opcode 実行時点で low の位置を指す）。
  - 無条件版: `PC ← nn`
  - 条件付き版:
    - `pcAfterImmediate = PC + 2`（即値 2 バイトを読み飛ばした位置）
    - 条件成立: `PC ← nn`
    - 条件不成立: `PC ← pcAfterImmediate`（そのまま次へ）
- サイクル:
  - 無条件: 16
  - 条件付き:
    - 条件成立: 16
    - 条件不成立: 12

#### 7.6.6 JR e / JR cc, e

- 主な opcode:
  - `0x18` … `JR e`（無条件）
  - `0x20` … `JR NZ, e`
  - `0x28` … `JR Z, e`
  - `0x30` … `JR NC, e`
  - `0x38` … `JR C, e`
- フォーマット: `[OPCODE][e]`（e は符号付き 8bit オフセット）
- 動作:
  - opcode フェッチ後、`PC` は即値 `e` の位置を指す。
  - `pcAfterImmediate = PC + 1`（即値 1 バイトを読み飛ばした位置＝**次の命令の先頭**）
  - 無条件 JR:
    - `PC ← pcAfterImmediate + signExtend(e)`
  - 条件付き JR:
    - 条件成立: `PC ← pcAfterImmediate + signExtend(e)`
    - 条件不成立: `PC ← pcAfterImmediate`（オフセットを足さない）
- サイクル:
  - 無条件: 12
  - 条件付き:
    - 条件成立: 12
    - 条件不成立: 8

#### 7.6.7 CALL nn / CALL cc, nn

サブルーチン呼び出し命令は「戻りアドレスをスタックに退避 → PC を新しいアドレスへ変更」という 2 ステップで動く。

- 主な opcode:
  - `0xCD` … `CALL nn`
  - `0xC4` … `CALL NZ, nn`
  - `0xCC` … `CALL Z, nn`
  - `0xD4` … `CALL NC, nn`
  - `0xDC` … `CALL C, nn`
- フォーマット: `[OPCODE][low][high]`
- 動作（無条件版）:
  - `pcImmediate = PC`（即値 low の位置）
  - `target = readWordAt(pcImmediate)`
  - `returnAddress = pcImmediate + 2`（即値 2 バイトを読み飛ばした、**次の命令の先頭**）
  - `pushWord(returnAddress)`
  - `PC ← target`
- 動作（条件付き版）:
  - `pcImmediate` / `target` / `pcAfterImmediate = pcImmediate + 2` を同様に計算。
  - 条件成立:
    - `pushWord(pcAfterImmediate)`
    - `PC ← target`
  - 条件不成立:
    - `PC ← pcAfterImmediate`（即値を読み飛ばすだけ）
- サイクル:
  - 無条件: 24
  - 条件付き:
    - 条件成立: 24
    - 条件不成立: 12

#### 7.6.8 RET / RET cc

サブルーチンからの復帰命令。**スタックから戻りアドレスを POP して PC に戻す**。

- 主な opcode:
  - `0xC9` … `RET`
  - `0xC0` … `RET NZ`
  - `0xC8` … `RET Z`
  - `0xD0` … `RET NC`
  - `0xD8` … `RET C`
- 動作:
  - 無条件: `PC ← popWord()`
  - 条件付き:
    - 条件成立: `PC ← popWord()`
    - 条件不成立: スタック操作なし
- サイクル:
  - 無条件: 16
  - 条件付き:
    - 条件成立: 20
    - 条件不成立: 8

#### 7.6.9 RST n（リスタートベクタ）

RST は「固定アドレスの CALL」と考えるとわかりやすい。  
8 個の固定アドレス（0x00, 0x08, 0x10, 0x18, 0x20, 0x28, 0x30, 0x38）へジャンプしつつ、  
戻りアドレスをスタックに積む。

- 主な opcode:
  - `0xC7` … `RST 00H`
  - `0xCF` … `RST 08H`
  - `0xD7` … `RST 10H`
  - `0xDF` … `RST 18H`
  - `0xE7` … `RST 20H`
  - `0xEF` … `RST 28H`
  - `0xF7` … `RST 30H`
  - `0xFF` … `RST 38H`
- 動作:
  - `returnAddress = PC`（opcode 実行後、すでに「次の命令の先頭」を指している）
  - `pushWord(returnAddress)`
  - `PC ← ベクタアドレス`
- サイクル: 16

RST は主に **割り込みベクタ**や、ゲーム側の「共通サブルーチン入口」として多用される。  
ACE 解説の際には「どの RST からどの処理に飛んでいるか」を追うことが重要になる。

---

### 7.7 残りの LD 系（16bit 即値ロード・SP 周辺・高位 I/O）（2025-11-29）

フェーズ3の一部として、これまで後回しにしていた「16bit 即値ロード」「SP とメモリのやり取り」「高位 I/O（HRAM）アクセス」関連の LD 命令をまとめて実装した。

#### 7.7.1 16bit 即値ロード LD rr, nn

- 対応 opcode:
  - `0x01` … `LD BC, nn`
  - `0x11` … `LD DE, nn`
  - `0x21` … `LD HL, nn`
  - `0x31` … `LD SP, nn`
- フォーマット: `[OPCODE][low][high]`
- 動作:
  - `value = readWordAt(PC)`（low/high を 16bit に組み立て）
  - `PC ← PC + 2`
  - 対応する 16bit レジスタ（BC/DE/HL/SP）に `value` を代入
- サイクル: 12
- 実装は `executeLd16Immediate(set: (UShort) -> Unit)` に集約し、`executeByOpcode` から「どのレジスタに書くか」だけ渡す形にしている。

#### 7.7.2 SP とメモリの LD（LD SP, HL / LD (nn), SP / LD HL, SP+e）

- `LD SP, HL` (`0xF9`)
  - 動作: `SP ← HL`
  - フラグ: 変更なし
  - サイクル: 8
- `LD (nn), SP` (`0x08`)
  - フォーマット: `[0x08][low][high]`
  - 動作:
    - `addr = readWordAt(PC)`（PC は即値 low を指している）
    - `PC ← PC + 2`
    - `[addr] ← SP の下位 8bit`, `[addr+1] ← SP の上位 8bit`
  - フラグ: 変更なし
  - サイクル: 20
- `LD HL, SP+e` (`0xF8`)
  - フォーマット: `[0xF8][e]`（e は符号付き 8bit オフセット）
  - 動作:
    - `pcAfter = PC + 1`（即値を読み飛ばした次の命令アドレス）
    - `result = SP + signExtend(e)` を計算し、`HL ← result`
  - フラグ:
    - `Z = 0`
    - `N = 0`
    - `H/C` は **SP の下位 8bit とオフセットの加算結果**に基づいて設定
  - サイクル: 12

これにより、ブート ROM やスタック初期化コードで多用される「スタックポインタのセットアップ」「SP を基準にした HL の算出」が一通り表現できる。

#### 7.7.3 A とメモリの間の LD（(BC)/(DE)/(nn) 経由）

- `LD A, (BC)` (`0x0A`), `LD A, (DE)` (`0x1A`)
  - 動作: `A ← [BC]` / `A ← [DE]`
  - フラグ: 変更なし
  - サイクル: 8
- `LD (BC), A` (`0x02`), `LD (DE), A` (`0x12`)
  - 動作: `[BC] ← A` / `[DE] ← A`
  - フラグ: 変更なし
  - サイクル: 8
- `LD A, (nn)` (`0xFA`)
  - フォーマット: `[0xFA][low][high]`
  - 動作:
    - `addr = readWordAt(PC)`
    - `PC ← PC + 2`
    - `A ← [addr]`
  - サイクル: 16
- `LD (nn), A` (`0xEA`)
  - フォーマット: `[0xEA][low][high]`
  - 動作:
    - `addr = readWordAt(PC)`
    - `PC ← PC + 2`
    - `[addr] ← A`
  - サイクル: 16

これらは、ポケモン ROM やワーク RAM への単発アクセスを行う際の基本的なロード／ストアとして多用される。

#### 7.7.4 高位 I/O / HRAM への LD（LDH, LD (C),A / LD A,(C)）

Game Boy では I/O レジスタや HRAM が 0xFF00〜0xFFFF に配置されており、  
専用の短い命令でアクセスできるようになっている。

- `LDH (n), A` (`0xE0`)
  - 実アドレス: `0xFF00 + n`
  - フォーマット: `[0xE0][n]`
  - 動作: `[0xFF00 + n] ← A`
  - サイクル: 12
- `LDH A, (n)` (`0xF0`)
  - 実アドレス: `0xFF00 + n`
  - フォーマット: `[0xF0][n]`
  - 動作: `A ← [0xFF00 + n]`
  - サイクル: 12
- `LD (C), A` (`0xE2`)
  - 実アドレス: `0xFF00 + C`
  - 動作: `[0xFF00 + C] ← A`
  - サイクル: 8
- `LD A, (C)` (`0xF2`)
  - 実アドレス: `0xFF00 + C`
  - 動作: `A ← [0xFF00 + C]`
  - サイクル: 8

これらは Joypad 入力、LCD コントローラ、タイマ、割り込みフラグなど、  
**ポケモン側が頻繁に触る I/O レジスタ**へのアクセスに直結するため、ACE の挙動を読む際にも重要になる。

---

### 7.8 A 専用ローテート／フラグ操作（RLCA/RRCA/RLA/RRA/CPL/SCF/CCF）（2025-11-29）

CB 系とは別に、プレフィックスなしで A レジスタに対してのみ動くローテート／フラグ操作命令がある。  
これらは GB の古い 8080 系譲りの命令で、**CB 系とはフラグ仕様が微妙に違う**点に注意する。

#### 7.8.1 RLCA / RRCA / RLA / RRA

- `RLCA (0x07)`
  - 動作: A を 1bit 左ローテートし、元の bit7 を C と bit0 にコピー。
  - フラグ:
    - `Z = 0`（CB 系と違い、結果が 0 でも 0 に固定）
    - `N = 0`
    - `H = 0`
    - `C = 元の A の bit7`
  - サイクル: 4
- `RRCA (0x0F)`
  - 動作: A を 1bit 右ローテートし、元の bit0 を C と bit7 にコピー。
  - フラグ:
    - `Z = 0`
    - `N = 0`
    - `H = 0`
    - `C = 元の A の bit0`
  - サイクル: 4
- `RLA (0x17)`
  - 動作: C を巻き込みつつ A を左ローテート（`A ← (A << 1) | C_old`）。
  - フラグ:
    - `Z = 0`
    - `N = 0`
    - `H = 0`
    - `C = 元の A の bit7`
  - サイクル: 4
- `RRA (0x1F)`
  - 動作: C を巻き込みつつ A を右ローテート（`A ← (A >> 1) | (C_old << 7)`）。
  - フラグ:
    - `Z = 0`
    - `N = 0`
    - `H = 0`
    - `C = 元の A の bit0`
  - サイクル: 4

これら 4 つは「**Z フラグを一切立てない**」点が CB 系ローテート（`RLC/RRC/RL/RR`）と決定的に異なる。  
ACE のチャートを読むときは「CB 付きかどうか」で Z フラグの挙動が変わることに注意が必要。

#### 7.8.2 CPL / SCF / CCF

- `CPL (0x2F)` – 補数（A のビット反転）
  - 動作: `A ← ~A`
  - フラグ:
    - `N = 1`
    - `H = 1`
    - `Z/C は変更なし`
  - サイクル: 4
- `SCF (0x37)` – Set Carry Flag
  - 動作: キャリーフラグを 1 にセット
  - フラグ:
    - `C = 1`
    - `N = 0`
    - `H = 0`
    - `Z は変更なし`
  - サイクル: 4
- `CCF (0x3F)` – Complement Carry Flag
  - 動作: キャリーフラグを反転
  - フラグ:
    - `C = !C`
    - `N = 0`
    - `H = 0`
    - `Z は変更なし`
  - サイクル: 4

これらは算術命令や条件分岐の直前に「C フラグだけを整える」目的でよく挟まれる。  
特に ACE のチャート上では、「SCF で C=1 にしてから RRA で bit を落とす」「CCF で条件を反転する」といった使い方が現れるため、  
**Z を変えない／C だけをいじる**という性質を覚えておくと読みやすくなる。

---

### 7.9 残りのミスレニアス命令（JP (HL), RETI, EI/DI, HALT/STOP）（2025-11-29）

最後に、プレフィックスなしの残りミスレニアス命令を実装した。  
現時点では割り込みコントローラや HALT バグの再現までは行わず、「ACE のチャートを読み解くうえで必要になる最低限の挙動」を実装している。

#### 7.9.1 JP (HL)

- opcode: `0xE9`
- 動作: `PC ← HL`
- フラグ: 変更なし
- サイクル: 4

無条件の間接ジャンプであり、「PC を HL の値で上書きするだけ」のシンプルな命令。  
ポケモンのスクリプトエンジンなどで「ジャンプテーブル」や「関数ポインタ」のように使われることがある。

#### 7.9.2 RETI（割り込み復帰）

- opcode: `0xD9`
- 動作:
  - `PC ← popWord()`（`RET` と同様）
  - `IME ← 1`（割り込みマスタフラグを有効化）
- フラグ: 変更なし
- サイクル: 16

`RETI` は「割り込みハンドラの最後に必ず置かれる戻り命令」で、  
`RET` と違って「戻りつつ IME を再度有効にする」役割を持つ。  
ACE 的には「どのアドレスから割り込みハンドラに入り、どこで `RETI` しているか」が重要になる。

#### 7.9.3 EI / DI と IME（割り込みマスタフラグ）

内部的に `Cpu` クラスには以下の 2 つのフラグを持たせている:

- `interruptMasterEnabled` … 実際の IME（割り込み許可フラグ）
- `imeEnablePending` … `EI` を実行した直後に 1 になり、「**次の 1 命令実行後**」に IME を有効化するためのペンディングフラグ

`executeInstruction()` 冒頭で

- `imeEnablePending` が立っていれば `interruptMasterEnabled = true` にしてペンディングをクリア

することで、「EI の効果が 1 命令遅れて反映される」Game Boy 仕様を再現している。

- `DI (0xF3)`
  - 動作:
    - `IME ← 0`
    - `imeEnablePending ← 0`（保留中の EI もキャンセル）
  - フラグ: 変更なし
  - サイクル: 4
- `EI (0xFB)`
  - 動作:
    - `imeEnablePending ← 1`（IM E 自体はこの命令の直後にはまだ 0）
  - フラグ: 変更なし
  - サイクル: 4

今の段階では `interruptMasterEnabled` 自体は割り込み受付処理にまだ接続していないが、  
後続で割り込みコントローラを実装するときにこのフラグを参照する。

#### 7.9.4 HALT / STOP（簡易版）

`HALT` と `STOP` はどちらも「CPU の実行を一時停止する」命令だが、  
実機では割り込みやジョイパッド入力、CGB のスピード切り替えなどと絡んでかなり複雑な挙動を持つ。

ここではまず簡易版として、

- `Cpu` 内に
  - `halted: Boolean`
  - `stopped: Boolean`
  を持たせる
- `executeInstruction()` 冒頭で
  - `if (halted || stopped) return Cycles.NOP`
  として、「停止中は何もしない 4 サイクルの NOP 相当」として扱う

という実装にしている。

- `HALT (0x76)`
  - 動作: `halted ← true`
  - フラグ: 変更なし
  - サイクル: 4
- `STOP (0x10)`
  - 動作: `stopped ← true`
  - フラグ: 変更なし
  - サイクル: 4

実機では「IME=0 かつ未処理の割り込みあり」の場合に HALT バグが発生するなど、  
かなり細かい仕様があるが、ACE のチャートを読むうえではまず「**ここで CPU の実行が止まる／（将来的に）割り込みで再開する**」  
というレベルの理解があれば十分なので、詳細は後続フェーズでの最適化対象とする。

---

## 参考資料

- [Game Boy CPU Manual](https://ia803208.us.archive.org/30/items/GameBoyProgManVer1.1/GameBoyProgManVer1.1.pdf)（英語、公式マニュアル）
- [Pan Docs](https://gbdev.io/pandocs/)（Game Boy の技術仕様書）

---

このドキュメントは、CPU 実装を進めながら随時更新していきます。

