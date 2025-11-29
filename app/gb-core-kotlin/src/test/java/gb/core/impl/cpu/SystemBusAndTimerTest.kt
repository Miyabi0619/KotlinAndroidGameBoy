package gb.core.impl.cpu

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.ExperimentalUnsignedTypes

@OptIn(ExperimentalUnsignedTypes::class)
class SystemBusAndTimerTest {
    companion object {
        private const val ROM_SIZE = 0x8000
    }

    @Test
    fun `SystemBus routes ROM and WRAM and HRAM correctly`() {
        val rom = UByteArray(ROM_SIZE) { 0x00u }
        rom[0x0001] = 0x12u

        val interruptController = InterruptController()
        val timer = Timer(interruptController)
        val vram = UByteArray(0x2000) { 0u }
        val ppu = Ppu(vram)
        val bus =
            SystemBus(
                rom = rom,
                interruptController = interruptController,
                timer = timer,
                joypad = Joypad(interruptController),
                ppu = ppu,
            )

        // ROM 読み取り
        assertEquals(0x12u.toUByte(), bus.readByte(0x0001u))

        // WRAM 書き込み／読み取り
        bus.writeByte(0xC000u, 0x34u)
        assertEquals(0x34u.toUByte(), bus.readByte(0xC000u))

        // Echo RAM 経由でも同じ値が見える
        assertEquals(0x34u.toUByte(), bus.readByte(0xE000u))

        // HRAM 書き込み／読み取り
        bus.writeByte(0xFF80u, 0x56u)
        assertEquals(0x56u.toUByte(), bus.readByte(0xFF80u))
    }

    @Test
    fun `Timer increments DIV and TIMA and requests interrupt on overflow`() {
        val rom = UByteArray(ROM_SIZE) { 0x00u }
        val interruptController = InterruptController()
        val timer = Timer(interruptController)
        val vram = UByteArray(0x2000) { 0u }
        val ppu = Ppu(vram)
        val bus =
            SystemBus(
                rom = rom,
                interruptController = interruptController,
                timer = timer,
                joypad = Joypad(interruptController),
                ppu = ppu,
            )

        // IE: すべての割り込みを許可
        interruptController.writeIe(0xFFu)

        // TAC: タイマ有効 + クロック選択 0 (CPU/1024)
        bus.writeByte(0xFF07u, 0b100u)

        // TIMA/TMA を設定
        bus.writeByte(0xFF05u, 0xFEu) // TIMA
        bus.writeByte(0xFF06u, 0xAAu) // TMA

        // まずは DIV の確認: 256 サイクルごとに 1 インクリメント
        timer.step(256)
        assertEquals(1u.toUByte(), timer.div)

        // TIMA: period=1024 サイクルごとに 1 インクリメント
        timer.step(1024)
        assertEquals(0xFFu.toUByte(), timer.tima)

        // さらに 1024 サイクルでオーバーフローし、TIMA ← TMA, Timer 割り込みが要求される
        timer.step(1024)
        assertEquals(0xAAu.toUByte(), timer.tima)

        val pending =
            interruptController.nextPending(
                imeEnabled = true,
            )
        assertEquals(InterruptController.Type.TIMER, pending)
    }
}
