package gb.core.impl.cpu

import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalUnsignedTypes::class)
class CpuTest {
    @Test
    fun `NOP increments PC and takes 4 cycles`() {
        // メモリ全体を 0 で初期化し、先頭アドレスに NOP(0x00) を置く
        val memory = UByteArray(MEMORY_SIZE) { 0x00u }
        val bus = InMemoryBus(memory)

        val cpu = Cpu(bus)
        cpu.registers.pc = 0x0100u.toUShort()
        // cpu.registers.pc = 0x0000u.toUShort()

        val cycles = cpu.executeInstruction()

        // NOP は 4 サイクル
        assertEquals(4, cycles)
        // PC は 1 バイト進む
        assertEquals(0x0101u.toUShort(), cpu.registers.pc)
    }

    @Test
    fun `LD A n loads immediate value and advances PC`() {
        val memory = UByteArray(MEMORY_SIZE) { 0x00u }
        val bus = InMemoryBus(memory)

        // 0x0100: 0x3E (LD A, n), 0x0101: 0x42 (即値)
        memory[0x0100] = 0x3Eu
        memory[0x0101] = 0x42u

        val cpu = Cpu(bus)
        cpu.registers.pc = 0x0100u.toUShort()

        val cycles = cpu.executeInstruction()

        assertEquals(8, cycles)
        assertEquals(0x42u.toUByte(), cpu.registers.a)
        assertEquals(0x0102u.toUShort(), cpu.registers.pc)
    }

    private class InMemoryBus(
        private val memory: UByteArray,
    ) : Bus {
        override fun readByte(address: UShort): UByte {
            return memory[address.toInt()]
        }

        override fun writeByte(
            address: UShort,
            value: UByte,
        ) {
            memory[address.toInt()] = value
        }
    }

    private companion object {
        const val MEMORY_SIZE = 0x10000 // 64KB
    }
}
