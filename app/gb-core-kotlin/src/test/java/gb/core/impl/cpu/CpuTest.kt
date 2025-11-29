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

    @Test
    fun `INC A increments value and updates flags`() {
        val memory = UByteArray(MEMORY_SIZE) { 0x00u }
        val bus = InMemoryBus(memory)

        // 0x0100: 0x3C (INC A)
        memory[0x0100] = 0x3Cu

        val cpu = Cpu(bus)
        cpu.registers.pc = 0x0100u.toUShort()
        cpu.registers.a = 0x01u.toUByte()
        cpu.registers.flagC = true // C は変化しないことを確認したいので事前に 1 にしておく

        val cycles = cpu.executeInstruction()

        assertEquals(4, cycles)
        assertEquals(0x02u.toUByte(), cpu.registers.a)
        assertEquals(false, cpu.registers.flagZ)
        assertEquals(false, cpu.registers.flagN)
        assertEquals(false, cpu.registers.flagH)
        assertEquals(true, cpu.registers.flagC)
    }

    @Test
    fun `INC A sets half-carry and zero when expected`() {
        val memory = UByteArray(MEMORY_SIZE) { 0x00u }
        val bus = InMemoryBus(memory)

        // 0x0100: 0x3C (INC A)
        memory[0x0100] = 0x3Cu

        val cpu = Cpu(bus)
        cpu.registers.pc = 0x0100u.toUShort()

        // 0x0F -> 0x10 で H が立つが Z は立たない
        cpu.registers.a = 0x0Fu
        cpu.executeInstruction()
        assertEquals(0x10u.toUByte(), cpu.registers.a)
        assertEquals(false, cpu.registers.flagZ)
        assertEquals(true, cpu.registers.flagH)

        // もう一度同じ命令を実行するために PC を戻す
        cpu.registers.pc = 0x0100u.toUShort()

        // 0xFF -> 0x00 で Z と H が立つ
        cpu.registers.a = 0xFFu
        cpu.executeInstruction()
        assertEquals(0x00u.toUByte(), cpu.registers.a)
        assertEquals(true, cpu.registers.flagZ)
        assertEquals(true, cpu.registers.flagH)
    }

    @Test
    fun `LD B A copies value from A to B without touching flags`() {
        val memory = UByteArray(MEMORY_SIZE) { 0x00u }
        val bus = InMemoryBus(memory)

        // 0x0100: 0x47 (LD B, A)
        memory[0x0100] = 0x47u

        val cpu = Cpu(bus)
        cpu.registers.pc = 0x0100u.toUShort()
        cpu.registers.a = 0x12u
        cpu.registers.b = 0x00u
        cpu.registers.flagZ = true
        cpu.registers.flagN = true
        cpu.registers.flagH = true
        cpu.registers.flagC = true

        val cycles = cpu.executeInstruction()

        assertEquals(4, cycles)
        assertEquals(0x12u.toUByte(), cpu.registers.b)
        assertEquals(0x12u.toUByte(), cpu.registers.a)
        assertEquals(0x0101u.toUShort(), cpu.registers.pc)
        // フラグは変化しない
        assertEquals(true, cpu.registers.flagZ)
        assertEquals(true, cpu.registers.flagN)
        assertEquals(true, cpu.registers.flagH)
        assertEquals(true, cpu.registers.flagC)
    }

    @Test
    fun `LD A B copies value from B to A without touching flags`() {
        val memory = UByteArray(MEMORY_SIZE) { 0x00u }
        val bus = InMemoryBus(memory)

        // 0x0100: 0x78 (LD A, B)
        memory[0x0100] = 0x78u

        val cpu = Cpu(bus)
        cpu.registers.pc = 0x0100u.toUShort()
        cpu.registers.a = 0x00u
        cpu.registers.b = 0x34u
        cpu.registers.flagZ = false
        cpu.registers.flagN = false
        cpu.registers.flagH = true
        cpu.registers.flagC = false

        val cycles = cpu.executeInstruction()

        assertEquals(4, cycles)
        assertEquals(0x34u.toUByte(), cpu.registers.a)
        assertEquals(0x34u.toUByte(), cpu.registers.b)
        assertEquals(0x0101u.toUShort(), cpu.registers.pc)
        // フラグは変化しない
        assertEquals(false, cpu.registers.flagZ)
        assertEquals(false, cpu.registers.flagN)
        assertEquals(true, cpu.registers.flagH)
        assertEquals(false, cpu.registers.flagC)
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
