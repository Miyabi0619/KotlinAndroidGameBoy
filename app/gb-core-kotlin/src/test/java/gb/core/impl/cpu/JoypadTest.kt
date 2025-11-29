package gb.core.impl.cpu

import gb.core.api.InputState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.ExperimentalUnsignedTypes
import kotlin.OptIn

@OptIn(ExperimentalUnsignedTypes::class)
class JoypadTest {
    @Test
    fun `read returns all ones when nothing is pressed`() {
        val ic = InterruptController()
        val joypad = Joypad(ic)

        // P14=1, P15=1（0x30）を書き込む
        joypad.write(0x30u)
        val value = joypad.read()

        // 下位 4bit はすべて 1 のまま（どのボタンも押されていない）
        assertEquals(0x0F, value.toInt() and 0x0F)
    }

    @Test
    fun `pressing A clears bit0 when button group is selected`() {
        val ic = InterruptController()
        val joypad = Joypad(ic)

        // ボタン（P15）を選択: bit5=0, bit4=1 → 0x10
        joypad.write(0x10u)
        joypad.updateInput(InputState(a = true))

        val value = joypad.read()

        // A が押されているので bit0 が 0 になっていることだけ確認する
        assertEquals(0, value.toInt() and 0x01)
    }

    @Test
    fun `pressing Right clears bit0 when dpad group is selected`() {
        val ic = InterruptController()
        val joypad = Joypad(ic)

        // 方向キー（P14）を選択: bit4=0, bit5=1 → 0x20
        joypad.write(0x20u)
        joypad.updateInput(InputState(right = true))

        val value = joypad.read()

        // Right が押されているので bit0 が 0 になっていることだけ確認する
        assertEquals(0, value.toInt() and 0x01)
    }

    @Test
    fun `updateInput requests JOYPAD interrupt on new press`() {
        val ic = InterruptController()
        val joypad = Joypad(ic)

        // JOYPAD 割り込みを有効化
        ic.writeIe(0xFFu)

        // まだ何も押されていない状態から A を押す
        joypad.updateInput(InputState(a = false))
        joypad.updateInput(InputState(a = true))

        val pending = ic.nextPending(imeEnabled = true)
        assertTrue(pending == InterruptController.Type.JOYPAD)
    }
}
