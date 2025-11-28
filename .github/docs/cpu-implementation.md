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

### 7.1 Registers / Bus / Cpu の骨格

- `gb.core.impl.cpu.Registers`
  - 8bit レジスタ、PC/SP、フラグ (Z/N/H/C) を保持
  - 16bit ペアレジスタ（BC/DE/HL/AF）を get/set できるプロパティを追加
- `gb.core.impl.cpu.Bus`
  - CPU がメモリにアクセスするためのインターフェース
- `gb.core.impl.cpu.Cpu`
  - `executeInstruction()` を実装し、現在は NOP (`0x00`) のみをサポート
  - 命令ごとのサイクル数を `Cycles` オブジェクトで管理

### 7.2 テスト用 Bus と NOP テスト

- `app/gb-core-kotlin/src/test/java/gb/core/impl/cpu/CpuTest.kt`
  - `InMemoryBus`（`UByteArray` で 64KB メモリを模倣）を使ったユニットテスト
  - `executeInstruction()` が 4 サイクルを返し、PC が 1 バイト進むことを確認
- 実行コマンド

```sh
./gradlew :app:gb-core-kotlin:test
```

この状態で「最小の CPU + バス + テスト」が整ったので、ここから命令セットを順に実装していく。

---

## 参考資料

- [Game Boy CPU Manual](https://ia803208.us.archive.org/30/items/GameBoyProgManVer1.1/GameBoyProgManVer1.1.pdf)（英語、公式マニュアル）
- [Pan Docs](https://gbdev.io/pandocs/)（Game Boy の技術仕様書）

---

このドキュメントは、CPU 実装を進めながら随時更新していきます。

