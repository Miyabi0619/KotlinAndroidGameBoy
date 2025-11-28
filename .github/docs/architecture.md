---
title: 無題のファイル
aliases: []
tags:
  - docs
created: 2025-11-27 22:58
---
# Game Boy エミュレータ アーキテクチャ設計書

## 1. 目的

本書は、本アプリのモジュール構成およびコンポーネント間の依存関係を示し、実装および保守の指針とすることを目的とする。

## 2. モジュール構成

本プロジェクトは以下のモジュールで構成する。

- `gb-core-api`  
  - エミュレータコアの公開インタフェースを定義するモジュールである。
- `gb-core-kotlin`  
  - Kotlin による Game Boy コア実装を提供するモジュールである。
- `app-android`  
  - Android アプリ本体であり、UI と入力、およびコアの実行制御を行う。

依存関係は以下の通りとする。

```text
gb-core-kotlin -> gb-core-api
app-android    -> gb-core-api, gb-core-kotlin
```


将来的に `gb-core-native`（Rust/C++ 実装）を追加する場合、`gb-core-api` を実装する形で追加するものとする。

## 3. コンポーネント構成

### 3.1 コア API

`gb-core-api` モジュールは以下のインタフェースを提供する。

- `GameBoyCore`
    
    - エミュレータ 1 インスタンスを表す。
        
    - `reset()`, `loadRom()`, `runFrame()` などを定義する。
        
- `InputState`
    
    - フレーム単位の入力状態を表すデータクラスである。
        
- `FrameResult`
    
    - 1 フレームぶんの描画結果およびオーディオサンプルを保持する。
        

### 3.2 コア実装

`gb-core-kotlin` モジュールは内部に以下のコンポーネントを持つ。

- `GameBoyCoreImpl`
    
    - `GameBoyCore` インタフェースの実装クラスである。
        
    - CPU・PPU・メモリバス・タイマー・Joypad 等を統合し、`runFrame()` を提供する。
        
- `Cpu` / `Registers`
    
    - LR35902 CPU のレジスタおよび命令実行を担当する。
        
- `Ppu` / `FrameBuffer`
    
    - 画面描画処理およびフレームバッファ管理を担当する。
        
- `Bus`
    
    - アドレス空間全体への読み書きを仲介する。
        
- `Cartridge` / `Mbc1`
    
    - ROM データと MBC1 ロジックを提供する。
        
- `Timer` / `Joypad`
    
    - タイマおよび入力状態管理を行う。
        

### 3.3 Android アプリ

`app-android` モジュールは以下のコンポーネントを持つ。

- UI レイヤ
    
    - `MainActivity`
        
    - `GameScreen`（Jetpack Compose など）
        
    - `VirtualGamepad` コンポーネント
        
- アプリケーションサービス
    
    - `CoreProvider`
        
        - `GameBoyCore` の生成を行う。将来的な実装切り替えポイントとする。
            
    - `GameLoop`
        
        - フレーム実行ループを管理し、画面リフレッシュとコアの `runFrame` を同期させる。
            
- インフラ層
    
    - `RomLoader`
        
        - SAF を用いて ROM を読み込み、バイト配列としてコアへ渡す