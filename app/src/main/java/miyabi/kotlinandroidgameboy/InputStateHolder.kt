package miyabi.kotlinandroidgameboy

import android.view.KeyEvent
import androidx.compose.runtime.mutableStateOf
import gb.core.api.InputState

/**
 * USB コントローラーやハードウェアキーからの入力を保持する簡易ホルダー。
 *
 * - Activity の onKeyDown/onKeyUp から更新され、
 * - Compose 側は [controllerInput] を参照して UI 入力とマージする。
 */
object InputStateHolder {
    val controllerInput = mutableStateOf(InputState())

    /**
     * キーイベントを InputState に反映する。
     *
     * @return 対応するキーであれば true（イベントを消費）、それ以外は false。
     */
    fun updateFromKey(
        keyCode: Int,
        isDown: Boolean,
    ): Boolean {
        val current = controllerInput.value
        val next =
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> current.copy(up = isDown)
                KeyEvent.KEYCODE_DPAD_DOWN -> current.copy(down = isDown)
                KeyEvent.KEYCODE_DPAD_LEFT -> current.copy(left = isDown)
                KeyEvent.KEYCODE_DPAD_RIGHT -> current.copy(right = isDown)
                KeyEvent.KEYCODE_BUTTON_A -> current.copy(a = isDown)
                KeyEvent.KEYCODE_BUTTON_B -> current.copy(b = isDown)
                KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_ENTER -> current.copy(start = isDown)
                KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BACK -> current.copy(select = isDown)
                else -> return false
            }

        controllerInput.value = next
        return true
    }
}
