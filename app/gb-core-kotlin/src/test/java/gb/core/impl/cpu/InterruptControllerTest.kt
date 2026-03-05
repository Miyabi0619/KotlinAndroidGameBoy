package gb.core.impl.cpu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.ExperimentalUnsignedTypes
import kotlin.OptIn

/**
 * InterruptController のユニットテスト。
 *
 * GB仕様（Pan Docs: https://gbdev.io/pandocs/Interrupts.html）に基づき、
 * 割り込み優先順位・IE/IF レジスタ動作・IME 制御を検証する。
 *
 * 割り込み優先順位（高→低）: VBLANK > LCD_STAT > TIMER > SERIAL > JOYPAD
 * ベクタアドレス: 0x40 / 0x48 / 0x50 / 0x58 / 0x60
 */
@OptIn(ExperimentalUnsignedTypes::class)
class InterruptControllerTest {
    // ────────────────────────────────────────────────────────────────
    // 割り込み優先順位
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `VBLANK has highest priority when all interrupts are pending`() {
        val ic = InterruptController()
        ic.writeIe(0x1Fu) // 全割り込み許可
        // 全種類をリクエスト（低優先→高優先の順でも、VBLANK が最初に選ばれる）
        InterruptController.Type.entries.forEach { ic.request(it) }

        val result = ic.nextPending(imeEnabled = true)
        assertEquals(InterruptController.Type.VBLANK, result)
    }

    @Test
    fun `LCD_STAT has second priority over TIMER SERIAL JOYPAD`() {
        val ic = InterruptController()
        ic.writeIe(0x1Fu)
        ic.request(InterruptController.Type.JOYPAD)
        ic.request(InterruptController.Type.SERIAL)
        ic.request(InterruptController.Type.TIMER)
        ic.request(InterruptController.Type.LCD_STAT) // VBLANK はなし

        val result = ic.nextPending(imeEnabled = true)
        assertEquals(InterruptController.Type.LCD_STAT, result)
    }

    @Test
    fun `TIMER has third priority over SERIAL and JOYPAD`() {
        val ic = InterruptController()
        ic.writeIe(0x1Fu)
        ic.request(InterruptController.Type.JOYPAD)
        ic.request(InterruptController.Type.SERIAL)
        ic.request(InterruptController.Type.TIMER) // LCD_STAT, VBLANK はなし

        val result = ic.nextPending(imeEnabled = true)
        assertEquals(InterruptController.Type.TIMER, result)
    }

    @Test
    fun `SERIAL has fourth priority over JOYPAD`() {
        val ic = InterruptController()
        ic.writeIe(0x1Fu)
        ic.request(InterruptController.Type.JOYPAD)
        ic.request(InterruptController.Type.SERIAL)

        val result = ic.nextPending(imeEnabled = true)
        assertEquals(InterruptController.Type.SERIAL, result)
    }

    @Test
    fun `JOYPAD has lowest priority`() {
        val ic = InterruptController()
        ic.writeIe(0x1Fu)
        ic.request(InterruptController.Type.JOYPAD) // 他の割り込みなし

        val result = ic.nextPending(imeEnabled = true)
        assertEquals(InterruptController.Type.JOYPAD, result)
    }

    // ────────────────────────────────────────────────────────────────
    // IE マスク
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `interrupt is blocked when not enabled in IE register`() {
        val ic = InterruptController()
        ic.writeIe(0x01u) // VBLANK のみ許可
        ic.request(InterruptController.Type.TIMER) // IE に含まれない
        ic.request(InterruptController.Type.JOYPAD) // IE に含まれない

        val result = ic.nextPending(imeEnabled = true)
        assertNull("IE でマスクされた割り込みはサービスされないはず", result)
    }

    @Test
    fun `interrupt is serviced when enabled in IE and IF`() {
        val ic = InterruptController()
        ic.writeIe(0x04u) // TIMER のみ許可
        ic.request(InterruptController.Type.TIMER)

        val result = ic.nextPending(imeEnabled = true)
        assertEquals(InterruptController.Type.TIMER, result)
    }

    @Test
    fun `no interrupt when IE is zero`() {
        val ic = InterruptController()
        ic.writeIe(0x00u) // 全割り込み禁止
        InterruptController.Type.entries.forEach { ic.request(it) }

        val result = ic.nextPending(imeEnabled = true)
        assertNull("IE=0 のとき割り込みはサービスされないはず", result)
    }

    // ────────────────────────────────────────────────────────────────
    // IME 制御
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `IME false prevents any interrupt from being serviced`() {
        val ic = InterruptController()
        ic.writeIe(0x1Fu)
        ic.request(InterruptController.Type.VBLANK)
        ic.request(InterruptController.Type.TIMER)

        val result = ic.nextPending(imeEnabled = false)
        assertNull("IME=false のとき割り込みはサービスされないはず", result)
    }

    // ────────────────────────────────────────────────────────────────
    // IF クリア / サービス後の状態
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `IF bit is cleared after interrupt is serviced`() {
        val ic = InterruptController()
        ic.writeIe(0x1Fu)
        ic.request(InterruptController.Type.VBLANK)

        ic.nextPending(imeEnabled = true)

        // VBLANK bit (bit0) がクリアされているはず
        assertEquals(0x00, ic.readIf().toInt() and 0x01)
    }

    @Test
    fun `only one interrupt is serviced per nextPending call`() {
        val ic = InterruptController()
        ic.writeIe(0x1Fu)
        ic.request(InterruptController.Type.VBLANK)
        ic.request(InterruptController.Type.TIMER)

        val first = ic.nextPending(imeEnabled = true)
        val second = ic.nextPending(imeEnabled = true)

        assertEquals(InterruptController.Type.VBLANK, first)
        assertEquals(InterruptController.Type.TIMER, second)
    }

    @Test
    fun `requesting same interrupt twice results in only one pending entry`() {
        val ic = InterruptController()
        ic.writeIe(0x1Fu)
        ic.request(InterruptController.Type.TIMER)
        ic.request(InterruptController.Type.TIMER) // 2回リクエスト

        val first = ic.nextPending(imeEnabled = true)
        val second = ic.nextPending(imeEnabled = true)

        assertEquals(InterruptController.Type.TIMER, first)
        assertNull("同じ割り込みを2回リクエストしても1つしか pending しないはず", second)
    }

    // ────────────────────────────────────────────────────────────────
    // IF 直接書き込み
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `manually written IF register triggers correct interrupt`() {
        val ic = InterruptController()
        ic.writeIe(0x1Fu)
        ic.writeIf(0x04u) // TIMER bit (bit2) を手動セット

        val result = ic.nextPending(imeEnabled = true)
        assertEquals(InterruptController.Type.TIMER, result)
    }

    @Test
    fun `IF register only uses lower 5 bits`() {
        val ic = InterruptController()
        ic.writeIf(0xFFu) // 全ビット書き込み
        // 仕様: 下位5bit のみ有効（IF[7:5] は未使用）
        assertEquals(0x1F, ic.readIf().toInt())
    }

    // ────────────────────────────────────────────────────────────────
    // 割り込みベクタ（GB 仕様との一致確認）
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `interrupt vectors match Game Boy specification`() {
        // Pan Docs: https://gbdev.io/pandocs/Interrupts.html
        assertEquals(0x40u.toUShort(), InterruptController.Type.VBLANK.vector)
        assertEquals(0x48u.toUShort(), InterruptController.Type.LCD_STAT.vector)
        assertEquals(0x50u.toUShort(), InterruptController.Type.TIMER.vector)
        assertEquals(0x58u.toUShort(), InterruptController.Type.SERIAL.vector)
        assertEquals(0x60u.toUShort(), InterruptController.Type.JOYPAD.vector)
    }
}