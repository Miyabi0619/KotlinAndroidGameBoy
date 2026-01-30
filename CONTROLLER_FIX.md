# コントローラー入力の修正

## 🎮 問題

### 修正前の挙動
画面のボタンが**トグル式**（押すたびにON/OFFが切り替わる）になっていたため、以下の問題がありました：

1. **ゲームボーイらしくない操作感**
   - 実機は「ボタンを押している間だけON」
   - トグル式では「1回押すとON、もう1回押すとOFF」

2. **コントローラーが画面ボタンを操作してしまう**
   - USBやBluetoothコントローラーのボタンを押すと、画面のボタンがトグルされる
   - ゲームは正しく操作できるが、視覚的に混乱する

---

## ✅ 修正内容

### 新しい挙動
画面のボタンを**ホールド式**（押している間だけON、離すとOFF）に変更しました。

**技術的な変更**:
1. `Button`の`onClick`から`pointerInput`の`detectTapGestures`に変更
2. `onPress`で押下時にON、`tryAwaitRelease`で離した時にOFFに変更
3. トグル式の`toggle()`関数を削除し、`setButton()`関数に置き換え

**コード例**:
```kotlin
// 修正前（トグル式）
Button(onClick = { toggle(input.up) { v -> input.copy(up = v) } }) {
    Text(if (input.up) "↑ ON" else "↑")
}

// 修正後（ホールド式）
androidx.compose.material3.FilledTonalButton(
    onClick = { },
    modifier = Modifier.pointerInput(Unit) {
        detectTapGestures(
            onPress = {
                setButton(true) { v -> input.copy(up = v) }
                tryAwaitRelease()
                setButton(false) { v -> input.copy(up = v) }
            }
        )
    }
) {
    Text("↑")
}
```

---

## 🎯 効果

### 1. **実機に近い操作感**
- ボタンを押している間だけゲームに入力される
- 離すと即座に入力が解除される
- ゲームボーイ本来の操作感に一致

### 2. **コントローラーが正しく動作**
- USBコントローラー: ボタンを押している間だけ入力
- Bluetoothコントローラー: ボタンを押している間だけ入力
- 画面ボタンは視覚的な混乱を引き起こさない

### 3. **タッチ操作の改善**
- 画面のボタンも「押している間だけON」になる
- タッチ感が自然になり、ゲームプレイが快適に

---

## 🧪 動作確認

### 画面タッチ
1. 画面の十字キーやAボタンを**押し続ける**
2. **押している間だけ**ゲームに入力される
3. 指を離すと即座に入力が解除される

### USBコントローラー
1. USB OTGケーブルでコントローラーを接続
2. コントローラーのボタンを**押し続ける**
3. **押している間だけ**ゲームに入力される
4. ボタンを離すと即座に入力が解除される
5. 画面のボタン表示はトグルされない ✅

### Bluetoothコントローラー
1. Bluetoothでコントローラーをペアリング
2. コントローラーのボタンを**押し続ける**
3. **押している間だけ**ゲームに入力される
4. ボタンを離すと即座に入力が解除される
5. 画面のボタン表示はトグルされない ✅

---

## 📝 技術的な詳細

### pointerInputとdetectTapGestures
`detectTapGestures`の`onPress`ブロックは以下のように動作します：

```kotlin
onPress = {
    // ボタンが押された瞬間
    setButton(true) { v -> input.copy(up = v) }

    // ボタンが離されるまで待機
    tryAwaitRelease()

    // ボタンが離された瞬間
    setButton(false) { v -> input.copy(up = v) }
}
```

この実装により、押下と解放が正確に検出され、ゲームボーイ本来の挙動を再現できます。

### mergeInput関数
画面入力とコントローラー入力は`mergeInput`関数で統合されます：

```kotlin
private fun mergeInput(ui: InputState, controller: InputState): InputState =
    InputState(
        a = ui.a || controller.a,      // どちらか一方が押されていればON
        b = ui.b || controller.b,
        select = ui.select || controller.select,
        start = ui.start || controller.start,
        up = ui.up || controller.up,
        down = ui.down || controller.down,
        left = ui.left || controller.left,
        right = ui.right || controller.right,
    )
```

これにより、画面タッチとコントローラーを同時に使用できます。

---

## 🎉 結果

**修正前**:
- ❌ トグル式のボタン
- ❌ コントローラーが画面ボタンを操作
- ❌ 実機と異なる操作感

**修正後**:
- ✅ ホールド式のボタン（押している間だけON）
- ✅ コントローラーが直接ゲームを操作
- ✅ 実機に近い操作感

これで、デバッグがしやすく、ゲームプレイも快適になりました！

---

**修正日**: 2026-01-30
**ファイル**: `MainActivity.kt`
**影響**: 画面タッチ、USBコントローラー、Bluetoothコントローラー
