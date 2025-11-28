---
title: 無題のファイル
aliases: []
tags:
  - docs
created: 2025-11-27 23:02
---

---

## 4. コーディング規約（簡易版）

**ファイル例:** `docs/coding-style.md`


# コーディング規約（簡易）

## 1. 目的

本書は、本プロジェクトにおける Kotlin コードのスタイルおよび構成方針を簡易的に定めるものである。

## 2. 基本方針

- Kotlin の一般的なコーディングスタイル（公式スタイルガイド）に準拠する。  
- エミュレータコアはパフォーマンスを重視し、不要なオブジェクト生成を抑える。  
- 公開インタフェースは `gb-core-api` に集約し、実装詳細は `gb-core-kotlin` に隠蔽する。

## 3. パッケージ構成

- コア API: `gb.api.*`  
- コア実装: `gb.core.*`, `gb.cpu.*`, `gb.ppu.*`, `gb.mem.*`, `gb.cart.*`, `gb.io.*`  
- Android アプリ: `com.example.gbemu.ui.*`, `com.example.gbemu.emulator.*` 等

## 4. 命名規則

- クラス名: PascalCase（例: `GameBoyCoreImpl`, `FrameBuffer`）  
- インタフェース名: PascalCase（`GameBoyCore` のように先頭に `I` は付与しない）。  
- 関数名・プロパティ名: camelCase（例: `runFrame`, `readByte`）。  
- 定数: UPPER_SNAKE_CASE（例: `CYCLES_PER_FRAME`）。  

## 5. 型の扱い

- メモリ関連の値は可能な範囲で符号なし型（`UByte`, `UShort`）を用いる。  
- 配列は `ByteArray` / `IntArray` などプリミティブ配列を使用し、`List` は原則として内部ループでは使用しない。

## 6. ログ出力

- デバッグ目的のログには Android 側では `Log.d`、コア側では独自の `Logger` ヘルパを使用する。  
- コア内部でのログはデバッグビルドのみ有効とし、リリースビルドでは無効化できるようにする。  

## 7. コメント

- エミュレータ固有のトリッキーな処理（フラグの更新、MBC の挙動等）には簡潔なコメントを付与する。  
- 実装上の前提（例: 「この関数は 1 命令のみを実行する」など）は KDoc として明記する。
