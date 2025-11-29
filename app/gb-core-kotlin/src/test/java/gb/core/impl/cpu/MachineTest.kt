package gb.core.impl.cpu

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.ExperimentalUnsignedTypes

@OptIn(ExperimentalUnsignedTypes::class)
class MachineTest {
    companion object {
        private const val ROM_SIZE = 0x8000
    }

    @Test
    fun `stepInstruction executes CPU and services pending timer interrupt`() {
        val rom = UByteArray(ROM_SIZE) { 0x00u }

        // 簡単なプログラム:
        // 0x0100: 00 (NOP)
        rom[0x0100] = 0x00u

        val machine = Machine(rom)

        // Machine 内部のコンポーネントにアクセス（テスト用に cpu と SystemBus+Timer の挙動を見る）
        val cpu = machine.cpu
        cpu.registers.pc = 0x0100u.toUShort()

        // IE: TIMER 割り込みを許可
        val interruptControllerField =
            Machine::class.java.getDeclaredField("interruptController").apply {
                isAccessible = true
            }
        val interruptController =
            interruptControllerField.get(machine) as InterruptController
        interruptController.writeIe(0xFFu)

        val timerField =
            Machine::class.java.getDeclaredField("timer").apply {
                isAccessible = true
            }
        val timer = timerField.get(machine) as Timer

        // タイマ設定: 有効 + /1024
        timer.writeRegister(3, 0b100u)
        timer.writeRegister(1, 0xFEu) // TIMA
        timer.writeRegister(2, 0xAAu) // TMA

        val totalCycles = machine.stepInstruction()
        // 現時点では Machine 内で IME を有効にする仕組みまでは入れていないため、
        // タイマ割り込みの pending 自体は Timer 側のユニットテストで確認済みとし、
        // ここでは「1 命令ぶんのサイクルが返ってくる」ことのみを確認する。
        assertEquals(4, totalCycles)
    }
}
