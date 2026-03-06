package gb.core.impl.cpu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.ExperimentalUnsignedTypes
import kotlin.OptIn

/**
 * Timer のユニットテスト。
 *
 * GB仕様（Pan Docs: https://gbdev.io/pandocs/Timer_and_Divider_Registers.html）に基づき、
 * DIV/TIMA/TMA/TAC レジスタの動作を検証する。
 *
 * 実装の核心: 内部16bitカウンタの特定ビットの立ち下がりエッジで TIMA がインクリメントされる。
 *   TAC clock=00 → bit9（1024 T-cycles周期）
 *   TAC clock=01 → bit3（16 T-cycles周期）
 *   TAC clock=10 → bit5（64 T-cycles周期）
 *   TAC clock=11 → bit7（256 T-cycles周期）
 *
 * 各ビットの初回立ち下がりタイミング（カウンタ=0 start）:
 *   bit9: カウンタ 1023(=1) → 1024(=0) → T=1024
 *   bit3: カウンタ   7(=1) →    8(=0) → T=8（ただし16周期の半分。初回立ち下がりは8T）
 *
 * 注意: TAC clock=01 の場合は bit3。bit3が0→1(T=8)→0(T=16)となるため、
 *       初回 TIMA インクリメントは T=16 に発生。
 */
@OptIn(ExperimentalUnsignedTypes::class)
class TimerTest {
    /** テスト用の Timer + InterruptController ペアを生成する */
    private fun makeTimer(): Pair<Timer, InterruptController> {
        val ic = InterruptController()
        ic.writeIe(0x1Fu) // 全割り込み許可
        return Pair(Timer(ic), ic)
    }

    // ────────────────────────────────────────────────────────────────
    // DIV レジスタ（内部16bitカウンタの上位8bit）
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `DIV reads as upper 8 bits of 16-bit internal counter`() {
        val (timer, _) = makeTimer()
        // 256 T-cycles で内部カウンタが256（= 0x100） → DIV = 1
        timer.step(256)
        assertEquals(1u.toUByte(), timer.div)
    }

    @Test
    fun `DIV increments every 256 T-cycles`() {
        val (timer, _) = makeTimer()
        timer.step(512)
        assertEquals(2u.toUByte(), timer.div)
        timer.step(256)
        assertEquals(3u.toUByte(), timer.div)
    }

    @Test
    fun `writing any value to DIV resets internal counter to 0`() {
        val (timer, _) = makeTimer()
        timer.step(512) // DIV = 2
        assertEquals(2u.toUByte(), timer.div)
        // 書き込み値は無視され、内部カウンタが 0 にリセットされる
        timer.writeRegister(0, 0xFFu)
        assertEquals(0u.toUByte(), timer.div)
    }

    @Test
    fun `DIV counter wraps around after 65536 T-cycles`() {
        val (timer, _) = makeTimer()
        // 256 * 256 = 65536 T-cycles でフルラップ
        timer.step(65536)
        assertEquals(0u.toUByte(), timer.div)
    }

    // ────────────────────────────────────────────────────────────────
    // TAC: タイマ有効/無効
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `TIMA does not increment when TAC timer is disabled`() {
        val (timer, _) = makeTimer()
        // TAC bit2 = 0（タイマ無効）
        timer.writeRegister(3, 0x00u)

        timer.step(1024 * 100) // 大量のサイクルを進めても TIMA は変化しない
        assertEquals(0u.toUByte(), timer.tima)
    }

    // ────────────────────────────────────────────────────────────────
    // TAC クロック選択（4種類全て検証）
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `TAC clock select 00 increments TIMA every 1024 T-cycles`() {
        val (timer, _) = makeTimer()
        // TAC = 0x04: enabled (bit2=1), clock=00 → bit9 立ち下がり = 1024 T-cycles周期
        timer.writeRegister(3, 0x04u)

        timer.step(1024) // 1回目インクリメント
        assertEquals(1u.toUByte(), timer.tima)
        timer.step(1024) // 2回目インクリメント
        assertEquals(2u.toUByte(), timer.tima)
    }

    @Test
    fun `TAC clock select 01 increments TIMA every 16 T-cycles`() {
        val (timer, _) = makeTimer()
        // TAC = 0x05: enabled, clock=01 → bit3 立ち下がり = 16 T-cycles周期
        timer.writeRegister(3, 0x05u)

        timer.step(16)
        assertEquals(1u.toUByte(), timer.tima)
        timer.step(16)
        assertEquals(2u.toUByte(), timer.tima)
    }

    @Test
    fun `TAC clock select 10 increments TIMA every 64 T-cycles`() {
        val (timer, _) = makeTimer()
        // TAC = 0x06: enabled, clock=10 → bit5 立ち下がり = 64 T-cycles周期
        timer.writeRegister(3, 0x06u)

        timer.step(64)
        assertEquals(1u.toUByte(), timer.tima)
    }

    @Test
    fun `TAC clock select 11 increments TIMA every 256 T-cycles`() {
        val (timer, _) = makeTimer()
        // TAC = 0x07: enabled, clock=11 → bit7 立ち下がり = 256 T-cycles周期
        timer.writeRegister(3, 0x07u)

        timer.step(256)
        assertEquals(1u.toUByte(), timer.tima)
    }

    @Test
    fun `TAC clock 01 does not increment before 16 T-cycles`() {
        val (timer, _) = makeTimer()
        timer.writeRegister(3, 0x05u)
        timer.step(15) // 15サイクルでは立ち下がりが発生しない
        assertEquals(0u.toUByte(), timer.tima)
    }

    // ────────────────────────────────────────────────────────────────
    // TIMA オーバーフローと遅延リロード（実機仕様のグリッチ）
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `TIMA overflow requests TIMER interrupt after 4 T-cycle delay`() {
        // 実機仕様: TIMA が 0xFF → 0x00 にオーバーフローしてから
        // 正確に 4 T-cycles 後に TIMER 割り込みが発生する
        val (timer, ic) = makeTimer()
        timer.writeRegister(3, 0x04u) // clock=00（1024サイクル周期）
        timer.writeRegister(1, 0xFFu) // TIMA = 0xFF（次のインクリメントでオーバーフロー）
        timer.writeRegister(2, 0xABu) // TMA = 0xAB

        timer.step(1024) // TIMA オーバーフロー発生、遅延開始
        timer.step(4) // 4 T-cycles の遅延が完了 → 割り込み発火

        val pending = ic.nextPending(imeEnabled = true)
        assertEquals(InterruptController.Type.TIMER, pending)
    }

    @Test
    fun `TIMA reloads with TMA value after 4 T-cycle overflow delay`() {
        val (timer, _) = makeTimer()
        timer.writeRegister(3, 0x04u)
        timer.writeRegister(1, 0xFFu)
        timer.writeRegister(2, 0xCDu) // TMA = 0xCD

        // オーバーフロー + 遅延完了（1024 + 4 T-cycles）
        timer.step(1024 + 4)
        assertEquals(0xCDu.toUByte(), timer.tima)
    }

    @Test
    fun `writing TIMA during overflow delay cancels TMA reload and interrupt`() {
        // 実機仕様: 遅延中に TIMA へ書き込むと、TMA リロードがキャンセルされ
        // 割り込みも発生しない
        val (timer, ic) = makeTimer()
        timer.writeRegister(3, 0x04u)
        timer.writeRegister(1, 0xFFu)
        timer.writeRegister(2, 0xCDu) // TMA = 0xCD

        timer.step(1024) // オーバーフロー発生、遅延開始（pending=true, delay=4）
        // 遅延中（まだ4T-cycles経過していない）に TIMA を書き換え → リロードキャンセル
        timer.writeRegister(1, 0x42u)
        timer.step(4) // 遅延期間が過ぎても、既にキャンセル済みなので割り込みなし

        assertEquals(0x42u.toUByte(), timer.tima)
        val pending = ic.nextPending(imeEnabled = true)
        assertNull("リロードキャンセル後は TIMER 割り込みが発生しないはず", pending)
    }

    @Test
    fun `writing TMA during overflow delay takes effect at reload`() {
        // 実機仕様: 遅延中に TMA を更新すると、リロード時に新しい値が使われる
        val (timer, _) = makeTimer()
        timer.writeRegister(3, 0x04u)
        timer.writeRegister(1, 0xFFu)
        timer.writeRegister(2, 0x00u) // TMA = 0x00 で開始

        timer.step(1024) // オーバーフロー発生
        timer.writeRegister(2, 0x55u) // 遅延中に TMA を 0x55 に更新
        timer.step(4) // 遅延完了

        // 新しい TMA 値でリロードされるはず
        assertEquals(0x55u.toUByte(), timer.tima)
    }

    @Test
    fun `TIMA does not overflow immediately before delay completes`() {
        // 3 T-cycles では遅延完了しない
        val (timer, ic) = makeTimer()
        timer.writeRegister(3, 0x04u)
        timer.writeRegister(1, 0xFFu)
        timer.writeRegister(2, 0xBBu)

        timer.step(1024) // オーバーフロー発生
        timer.step(3) // まだ3T-cyclesしか経過していない

        // まだ割り込みは発生していないはず
        val pending = ic.nextPending(imeEnabled = true)
        assertNull("4T-cycles の遅延が完了する前は割り込みが発生しないはず", pending)
        // TIMA はまだ 0（TMA でリロードされていない）
        assertEquals(0u.toUByte(), timer.tima)
    }

    // ────────────────────────────────────────────────────────────────
    // TMA: リロード値
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `TIMA wraps through multiple overflows using TMA reload value`() {
        val (timer, _) = makeTimer()
        timer.writeRegister(3, 0x04u)
        timer.writeRegister(2, 0x00u) // TMA = 0

        // 256 回インクリメントして最初のオーバーフロー発生
        timer.step(1024 * 256 + 4)

        // TMA=0 なので、TIMA=0 にリロードされているはず
        assertEquals(0u.toUByte(), timer.tima)
    }
}
