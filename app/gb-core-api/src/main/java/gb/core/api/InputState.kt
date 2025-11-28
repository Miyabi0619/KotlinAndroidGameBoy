package gb.core.api

/**
 * Game Boy のボタン入力状態を表すデータクラス。
 *
 * UI 層はユーザーのタッチイベントや物理キー入力をこの構造に変換し、
 * [GameBoyCore.runFrame] に渡す。
 */
data class InputState(
    val a: Boolean = false,
    val b: Boolean = false,
    val select: Boolean = false,
    val start: Boolean = false,
    val up: Boolean = false,
    val down: Boolean = false,
    val left: Boolean = false,
    val right: Boolean = false,
)
