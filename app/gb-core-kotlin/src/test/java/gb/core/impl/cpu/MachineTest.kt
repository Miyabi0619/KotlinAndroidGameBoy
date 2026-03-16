package gb.core.impl.cpu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.ExperimentalUnsignedTypes

@OptIn(ExperimentalUnsignedTypes::class)
class MachineTest {
    companion object {
        private const val ROM_SIZE = 0x8000

        /** Machine のプライベートフィールドをリフレクションで取得するヘルパー */
        private inline fun <reified T> Machine.getField(name: String): T {
            val field = Machine::class.java.getDeclaredField(name).apply { isAccessible = true }
            return field.get(this) as T
        }
    }

    @Test
    fun `stepInstruction returns correct cycle count for NOP`() {
        val rom = UByteArray(ROM_SIZE) { 0x00u }
        rom[0x0100] = 0x00u // NOP

        val machine = Machine(rom)
        machine.cpu.registers.pc = 0x0100u.toUShort()

        val totalCycles = machine.stepInstruction()
        assertEquals(4, totalCycles)
    }

    // ────────────────────────────────────────────────────────────────
    // EI 命令の 1 命令遅延
    // Pan Docs: EI は「次の1命令実行後」に IME を有効化する
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `EI enables interrupt after exactly one instruction delay at Machine level`() {
        val rom = UByteArray(ROM_SIZE) { 0x00u }
        // 0x0100: FB (EI)
        // 0x0101: 00 (NOP)  ← この命令実行後に IME が有効になる
        // 0x0102: 00 (NOP)
        rom[0x0100] = 0xFBu
        rom[0x0101] = 0x00u
        rom[0x0102] = 0x00u

        val machine = Machine(rom)
        machine.cpu.registers.pc = 0x0100u.toUShort()

        val ic = machine.getField<InterruptController>("interruptController")
        ic.writeIe(0x01u) // VBLANK 割り込みを許可

        // VBlank 割り込みをペンディングに設定
        ic.request(InterruptController.Type.VBLANK)

        // EI 実行: IME は次の命令後まで無効のまま → 割り込みサービスなし
        val cyclesEi = machine.stepInstruction()
        assertEquals(4, cyclesEi) // NOP と同じ4サイクル（割り込みサービスなし）

        // NOP 実行: この命令の実行で IME が有効化 → 命令実行後に割り込みがサービスされる
        // 割り込みサービス = 20 サイクル追加
        val cyclesNop = machine.stepInstruction()
        assertTrue("EI 後の NOP では割り込みサービス分のサイクルが追加されるはず（4+20=24以上）", cyclesNop > 4)
    }

    // ────────────────────────────────────────────────────────────────
    // HALT からのウェイクアップ
    // Pan Docs: IME=1 かつ IE & IF != 0 → HALT 解除 → 割り込みサービス
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `HALT wakes up and services interrupt when IME is enabled and interrupt is pending`() {
        val rom = UByteArray(ROM_SIZE) { 0x00u }
        // 0x0100: FB (EI)
        // 0x0101: 76 (HALT)
        // 0x0102: 00 (NOP)
        rom[0x0100] = 0xFBu
        rom[0x0101] = 0x76u
        rom[0x0102] = 0x00u

        val machine = Machine(rom)
        machine.cpu.registers.pc = 0x0100u.toUShort()
        machine.cpu.registers.sp = 0xFFFEu.toUShort()

        val ic = machine.getField<InterruptController>("interruptController")
        ic.writeIe(0x01u) // VBLANK 許可

        // EI 実行（次命令後に IME 有効化）
        machine.stepInstruction()

        // VBlank ペンディングを設定してから HALT 実行
        ic.request(InterruptController.Type.VBLANK)
        val cyclesHalt = machine.stepInstruction() // HALT: IME が有効化され、割り込みサービスも発生
        // HALT(4) + 割り込みサービス(20) = 24 サイクル以上
        assertTrue("HALT 後に割り込みサービスが発生するはず（4+20=24以上）", cyclesHalt >= 24)
    }

    @Test
    fun `HALT wakes up when IME is disabled but IE and IF both have matching bits`() {
        // IME=0 でも (IE & IF) != 0 の場合は HALT から抜ける（割り込みサービスはしない）
        val rom = UByteArray(ROM_SIZE) { 0x00u }
        // 0x0100: 76 (HALT)
        // 0x0101: 3C (INC A)
        rom[0x0100] = 0x76u
        rom[0x0101] = 0x3Cu

        val machine = Machine(rom)
        machine.cpu.registers.pc = 0x0100u.toUShort()
        machine.cpu.registers.a = 0x00u

        val ic = machine.getField<InterruptController>("interruptController")
        ic.writeIe(0x01u) // VBLANK 許可
        ic.request(InterruptController.Type.VBLANK) // VBlank ペンディング

        // HALT 実行: HALTバグ発動（IME=0, IE & IF != 0）→ PC 進む
        machine.stepInstruction()

        // 次の INC A が実行されるはず（HALT から抜けた証拠）
        machine.stepInstruction()
        assertTrue("HALT バグで HALT から抜けた後 INC A が実行されるはず", machine.cpu.registers.a > 0u.toUByte())
    }

    // ────────────────────────────────────────────────────────────────
    // 割り込みサービスのサイクル計算
    // Pan Docs: 割り込みサービスは 20 T-cycles
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `interrupt service adds 20 cycles to step total`() {
        val rom = UByteArray(ROM_SIZE) { 0x00u }
        // 0x0100: FB (EI)
        // 0x0101: 00 (NOP)
        // 0x0040: C9 (RET) ← VBLANK ハンドラ（即リターン）
        rom[0x0100] = 0xFBu
        rom[0x0101] = 0x00u
        rom[0x0040] = 0xC9u // VBLANK vector

        val machine = Machine(rom)
        machine.cpu.registers.pc = 0x0100u.toUShort()
        machine.cpu.registers.sp = 0xFFFEu.toUShort()

        val ic = machine.getField<InterruptController>("interruptController")
        ic.writeIe(0x01u) // VBLANK 許可
        ic.request(InterruptController.Type.VBLANK)

        // EI 実行
        machine.stepInstruction()

        // NOP 実行 + 割り込みサービス: 4(NOP) + 20(サービス) = 24
        val cycles = machine.stepInstruction()
        assertEquals("NOP(4) + 割り込みサービス(20) = 24 サイクルのはず", 24, cycles)
    }
}
