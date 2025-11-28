---
title: 無題のファイル
aliases: []
tags:
  - docs
created: 2025-11-27 23:03
---
# ビルドおよびリリース手順書（簡易）

## 1. 開発環境

- IDE: Android Studio 最新安定版  
- JDK: Android Studio 同梱 JDK  
- Android SDK: API 26 以上

## 2. ビルド手順（デバッグ）

1. Android Studio でプロジェクトを開く。  
2. ビルドターゲットを `app-android` に設定する。  
3. メニューから「Run」を選択し、接続された実機またはエミュレータを選択する。  
4. アプリが自動的にビルド・インストールされる。

コマンドラインからビルドする場合は以下を実行する。

```sh
./gradlew :app-android:assembleDebug
