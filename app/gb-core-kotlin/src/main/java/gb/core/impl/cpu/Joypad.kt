package gb.core.impl.cpu

import gb.core.api.InputState
import kotlin.ExperimentalUnsignedTypes
import kotlin.OptIn

/**
 * Joypad 入力（FF00）を扱うクラス。
 *
 * - InputState からボタン状態を受け取り、
 * - FF00 の読み書きを Game Boy 仕様に近い形でエミュレートする。
 * - キー押下の立ち上がりがあれば JOYPAD 割り込みを要求する。
 */
@OptIn(ExperimentalUnsignedTypes::class)
class Joypad(
    private val interruptController: InterruptController,
) {
    /**
     * FF00 に書き込まれた選択ビット（P14/P15）。
     *
     * - bit4 (P14): 0 なら方向キー（Right/Left/Up/Down）を選択
     * - bit5 (P15): 0 ならボタン（A/B/Select/Start）を選択
     * - 初期値 0x30: どちらも未選択（=1）
     */
    private var selectMask: Int = 0x30

    private var inputState: InputState = InputState()

    /**
     * CPU からの FF00 書き込み。
     *
     * - 実機では下位 4bit は読み取り専用なので、ここでは上位 2bit（4,5）のみ保持する。
     */
    fun write(value: UByte) {
        selectMask = value.toInt() and 0x30
    }

    /**
     * CPU からの FF00 読み取り。
     *
     * - bit7-6: 常に 1
     * - bit5-4: selectMask の値（P15/P14）
     * - bit3-0: 選択されているグループのボタン状態（押されていれば 0）
     */
    fun read(): UByte {
        var result = 0xC0 or selectMask or 0x0F // 上位 2bit=1, 下位4bit=1 で初期化

        // P14 (bit4) が 0 のとき方向キー
        if ((selectMask and 0x10) == 0) {
            if (inputState.right) result = result and 0b1110
            if (inputState.left) result = result and 0b1101
            if (inputState.up) result = result and 0b1011
            if (inputState.down) result = result and 0b0111
        }

        // P15 (bit5) が 0 のときボタン
        if ((selectMask and 0x20) == 0) {
            if (inputState.a) result = result and 0b1110
            if (inputState.b) result = result and 0b1101
            if (inputState.select) result = result and 0b1011
            if (inputState.start) result = result and 0b0111
        }

        return result.toUByte()
    }

    /**
     * アプリ側からの入力更新。
     *
     * - 前回状態と比較し、新たに押されたボタンがあれば JOYPAD 割り込みを要求する。
     */
    fun updateInput(newState: InputState) {
        val prev = inputState
        inputState = newState

        val pressedNow =
            listOf(
                !prev.a && newState.a,
                !prev.b && newState.b,
                !prev.select && newState.select,
                !prev.start && newState.start,
                !prev.right && newState.right,
                !prev.left && newState.left,
                !prev.up && newState.up,
                !prev.down && newState.down,
            )

        if (pressedNow.any { it }) {
            // ログ出力を削除（パフォーマンス向上のため）
            interruptController.request(InterruptController.Type.JOYPAD)
        }
    }
}
