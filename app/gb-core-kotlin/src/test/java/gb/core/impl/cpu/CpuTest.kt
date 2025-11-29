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
    fun `LD B n loads immediate value into B without touching flags`() {
        val memory = UByteArray(MEMORY_SIZE) { 0x00u }
        val bus = InMemoryBus(memory)

        // 0x0100: 0x06 (LD B, n), 0x0101: 0x12
        memory[0x0100] = 0x06u
        memory[0x0101] = 0x12u

        val cpu = Cpu(bus)
        cpu.registers.pc = 0x0100u.toUShort()
        cpu.registers.b = 0x00u
        cpu.registers.flagZ = true
        cpu.registers.flagN = false
        cpu.registers.flagH = true
        cpu.registers.flagC = false

        val cycles = cpu.executeInstruction()

        assertEquals(8, cycles)
        assertEquals(0x12u.toUByte(), cpu.registers.b)
        assertEquals(0x0102u.toUShort(), cpu.registers.pc)
        // フラグは変化しない
        assertEquals(true, cpu.registers.flagZ)
        assertEquals(false, cpu.registers.flagN)
        assertEquals(true, cpu.registers.flagH)
        assertEquals(false, cpu.registers.flagC)
    }

    @Test
    fun `LD H n loads immediate value into H without touching flags`() {
        val memory = UByteArray(MEMORY_SIZE) { 0x00u }
        val bus = InMemoryBus(memory)

        // 0x0100: 0x26 (LD H, n), 0x0101: 0x99
        memory[0x0100] = 0x26u
        memory[0x0101] = 0x99u

        val cpu = Cpu(bus)
        cpu.registers.pc = 0x0100u.toUShort()
        cpu.registers.h = 0x00u
        cpu.registers.flagZ = false
        cpu.registers.flagN = true
        cpu.registers.flagH = false
        cpu.registers.flagC = true

        val cycles = cpu.executeInstruction()

        assertEquals(8, cycles)
        assertEquals(0x99u.toUByte(), cpu.registers.h)
        assertEquals(0x0102u.toUShort(), cpu.registers.pc)
        // フラグは変化しない
        assertEquals(false, cpu.registers.flagZ)
        assertEquals(true, cpu.registers.flagN)
        assertEquals(false, cpu.registers.flagH)
        assertEquals(true, cpu.registers.flagC)
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
    fun `INC HL increments 16bit register and takes 8 cycles`() {
        val memory = UByteArray(MEMORY_SIZE) { 0x00u }
        val bus = InMemoryBus(memory)

        // 0x0100: 0x23 (INC HL)
        memory[0x0100] = 0x23u

        val cpu = Cpu(bus)
        cpu.registers.pc = 0x0100u.toUShort()
        cpu.registers.hl = 0x1234u
        cpu.registers.flagZ = true
        cpu.registers.flagN = true
        cpu.registers.flagH = false
        cpu.registers.flagC = true

        val cycles = cpu.executeInstruction()

        assertEquals(8, cycles)
        assertEquals(0x1235u.toUShort(), cpu.registers.hl)
        assertEquals(0x0101u.toUShort(), cpu.registers.pc)
        // 16bit INC はフラグを一切変更しない
        assertEquals(true, cpu.registers.flagZ)
        assertEquals(true, cpu.registers.flagN)
        assertEquals(false, cpu.registers.flagH)
        assertEquals(true, cpu.registers.flagC)
    }

    @Test
    fun `INC BC increments 16bit register and keeps flags unchanged`() {
        val memory = UByteArray(MEMORY_SIZE) { 0x00u }
        val bus = InMemoryBus(memory)

        // 0x0100: 0x03 (INC BC)
        memory[0x0100] = 0x03u

        val cpu = Cpu(bus)
        cpu.registers.pc = 0x0100u.toUShort()
        cpu.registers.bc = 0x0FFFu
        cpu.registers.flagZ = false
        cpu.registers.flagN = true
        cpu.registers.flagH = true
        cpu.registers.flagC = false

        val cycles = cpu.executeInstruction()

        assertEquals(8, cycles)
        assertEquals(0x1000u.toUShort(), cpu.registers.bc)
        assertEquals(0x0101u.toUShort(), cpu.registers.pc)
        // フラグはすべて変更されない
        assertEquals(false, cpu.registers.flagZ)
        assertEquals(true, cpu.registers.flagN)
        assertEquals(true, cpu.registers.flagH)
        assertEquals(false, cpu.registers.flagC)
    }

    @Test
    fun `DEC BC decrements 16bit register and keeps flags unchanged`() {
        val memory = UByteArray(MEMORY_SIZE) { 0x00u }
        val bus = InMemoryBus(memory)

        // 0x0100: 0x0B (DEC BC)
        memory[0x0100] = 0x0Bu

        val cpu = Cpu(bus)
        cpu.registers.pc = 0x0100u.toUShort()
        cpu.registers.bc = 0x1000u
        cpu.registers.flagZ = false
        cpu.registers.flagN = false
        cpu.registers.flagH = true
        cpu.registers.flagC = true

        val cycles = cpu.executeInstruction()

        assertEquals(8, cycles)
        assertEquals(0x0FFFu.toUShort(), cpu.registers.bc)
        assertEquals(0x0101u.toUShort(), cpu.registers.pc)
        // フラグはすべて変更されない
        assertEquals(false, cpu.registers.flagZ)
        assertEquals(false, cpu.registers.flagN)
        assertEquals(true, cpu.registers.flagH)
        assertEquals(true, cpu.registers.flagC)
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

    @Test
    fun `LD A (HL) loads from memory at HL`() {
        val memory = UByteArray(MEMORY_SIZE) { 0x00u }
        val bus = InMemoryBus(memory)

        // 0x0100: 0x7E (LD A, (HL))
        memory[0x0100] = 0x7Eu

        // メモリ[0xC000] に 0x99 を入れておく
        memory[0xC000] = 0x99u

        val cpu = Cpu(bus)
        cpu.registers.pc = 0x0100u.toUShort()
        cpu.registers.hl = 0xC000u
        cpu.registers.a = 0x00u
        cpu.registers.flagZ = true
        cpu.registers.flagN = true
        cpu.registers.flagH = false
        cpu.registers.flagC = true

        val cycles = cpu.executeInstruction()

        assertEquals(8, cycles)
        assertEquals(0x99u.toUByte(), cpu.registers.a)
        assertEquals(0x0101u.toUShort(), cpu.registers.pc)
        // フラグは変化しない
        assertEquals(true, cpu.registers.flagZ)
        assertEquals(true, cpu.registers.flagN)
        assertEquals(false, cpu.registers.flagH)
        assertEquals(true, cpu.registers.flagC)
    }

    @Test
    fun `LD (HL) A stores A into memory at HL`() {
        val memory = UByteArray(MEMORY_SIZE) { 0x00u }
        val bus = InMemoryBus(memory)

        // 0x0100: 0x77 (LD (HL), A)
        memory[0x0100] = 0x77u

        val cpu = Cpu(bus)
        cpu.registers.pc = 0x0100u.toUShort()
        cpu.registers.hl = 0xC000u
        cpu.registers.a = 0x55u
        cpu.registers.flagZ = false
        cpu.registers.flagN = false
        cpu.registers.flagH = true
        cpu.registers.flagC = false

        val cycles = cpu.executeInstruction()

        assertEquals(8, cycles)
        assertEquals(0x55u.toUByte(), memory[0xC000])
        assertEquals(0x0101u.toUShort(), cpu.registers.pc)
        // フラグは変化しない
        assertEquals(false, cpu.registers.flagZ)
        assertEquals(false, cpu.registers.flagN)
        assertEquals(true, cpu.registers.flagH)
        assertEquals(false, cpu.registers.flagC)
    }

    @Test
    fun `LD B (HL) loads from memory at HL into B`() {
        val memory = UByteArray(MEMORY_SIZE) { 0x00u }
        val bus = InMemoryBus(memory)

        // 0x0100: 0x46 (LD B, (HL))
        memory[0x0100] = 0x46u
        memory[0xC000] = 0x22u

        val cpu = Cpu(bus)
        cpu.registers.pc = 0x0100u.toUShort()
        cpu.registers.hl = 0xC000u
        cpu.registers.b = 0x00u
        cpu.registers.flagZ = false
        cpu.registers.flagN = true
        cpu.registers.flagH = false
        cpu.registers.flagC = true

        val cycles = cpu.executeInstruction()

        assertEquals(8, cycles)
        assertEquals(0x22u.toUByte(), cpu.registers.b)
        assertEquals(0x0101u.toUShort(), cpu.registers.pc)
        // フラグは変化しない
        assertEquals(false, cpu.registers.flagZ)
        assertEquals(true, cpu.registers.flagN)
        assertEquals(false, cpu.registers.flagH)
        assertEquals(true, cpu.registers.flagC)
    }

    @Test
    fun `LD (HL) B stores B into memory at HL`() {
        val memory = UByteArray(MEMORY_SIZE) { 0x00u }
        val bus = InMemoryBus(memory)

        // 0x0100: 0x70 (LD (HL), B)
        memory[0x0100] = 0x70u

        val cpu = Cpu(bus)
        cpu.registers.pc = 0x0100u.toUShort()
        cpu.registers.hl = 0xC000u
        cpu.registers.b = 0x33u
        cpu.registers.flagZ = true
        cpu.registers.flagN = false
        cpu.registers.flagH = true
        cpu.registers.flagC = false

        val cycles = cpu.executeInstruction()

        assertEquals(8, cycles)
        assertEquals(0x33u.toUByte(), memory[0xC000])
        assertEquals(0x0101u.toUShort(), cpu.registers.pc)
        // フラグは変化しない
        assertEquals(true, cpu.registers.flagZ)
        assertEquals(false, cpu.registers.flagN)
        assertEquals(true, cpu.registers.flagH)
        assertEquals(false, cpu.registers.flagC)
    }

    @Test
    fun `LD C D copies value from D to C without touching flags`() {
        val memory = UByteArray(MEMORY_SIZE) { 0x00u }
        val bus = InMemoryBus(memory)

        // 0x0100: 0x4A (LD C, D)
        memory[0x0100] = 0x4Au

        val cpu = Cpu(bus)
        cpu.registers.pc = 0x0100u.toUShort()
        cpu.registers.c = 0x00u
        cpu.registers.d = 0x12u
        cpu.registers.flagZ = true
        cpu.registers.flagN = false
        cpu.registers.flagH = true
        cpu.registers.flagC = false

        val cycles = cpu.executeInstruction()

        assertEquals(4, cycles)
        assertEquals(0x12u.toUByte(), cpu.registers.c)
        assertEquals(0x12u.toUByte(), cpu.registers.d)
        assertEquals(0x0101u.toUShort(), cpu.registers.pc)
        // フラグは変化しない
        assertEquals(true, cpu.registers.flagZ)
        assertEquals(false, cpu.registers.flagN)
        assertEquals(true, cpu.registers.flagH)
        assertEquals(false, cpu.registers.flagC)
    }

    @Test
    fun `LD H L copies value from L to H without touching flags`() {
        val memory = UByteArray(MEMORY_SIZE) { 0x00u }
        val bus = InMemoryBus(memory)

        // 0x0100: 0x65 (LD H, L)
        memory[0x0100] = 0x65u

        val cpu = Cpu(bus)
        cpu.registers.pc = 0x0100u.toUShort()
        cpu.registers.h = 0x00u
        cpu.registers.l = 0x34u
        cpu.registers.flagZ = false
        cpu.registers.flagN = true
        cpu.registers.flagH = false
        cpu.registers.flagC = true

        val cycles = cpu.executeInstruction()

        assertEquals(4, cycles)
        assertEquals(0x34u.toUByte(), cpu.registers.h)
        assertEquals(0x34u.toUByte(), cpu.registers.l)
        assertEquals(0x0101u.toUShort(), cpu.registers.pc)
        // フラグは変化しない
        assertEquals(false, cpu.registers.flagZ)
        assertEquals(true, cpu.registers.flagN)
        assertEquals(false, cpu.registers.flagH)
        assertEquals(true, cpu.registers.flagC)
    }

    @Test
    fun `LD E HL loads from memory at HL into E`() {
        val memory = UByteArray(MEMORY_SIZE) { 0x00u }
        val bus = InMemoryBus(memory)

        // 0x0100: 0x5E (LD E, (HL))
        memory[0x0100] = 0x5Eu
        memory[0xC000] = 0x99u

        val cpu = Cpu(bus)
        cpu.registers.pc = 0x0100u.toUShort()
        cpu.registers.hl = 0xC000u
        cpu.registers.e = 0x00u
        cpu.registers.flagZ = true
        cpu.registers.flagN = true
        cpu.registers.flagH = false
        cpu.registers.flagC = true

        val cycles = cpu.executeInstruction()

        assertEquals(8, cycles)
        assertEquals(0x99u.toUByte(), cpu.registers.e)
        assertEquals(0x0101u.toUShort(), cpu.registers.pc)
        // フラグは変化しない
        assertEquals(true, cpu.registers.flagZ)
        assertEquals(true, cpu.registers.flagN)
        assertEquals(false, cpu.registers.flagH)
        assertEquals(true, cpu.registers.flagC)
    }

    @Test
    fun `LD HL C stores C into memory at HL`() {
        val memory = UByteArray(MEMORY_SIZE) { 0x00u }
        val bus = InMemoryBus(memory)

        // 0x0100: 0x71 (LD (HL), C)
        memory[0x0100] = 0x71u

        val cpu = Cpu(bus)
        cpu.registers.pc = 0x0100u.toUShort()
        cpu.registers.hl = 0xC000u
        cpu.registers.c = 0xABu
        cpu.registers.flagZ = false
        cpu.registers.flagN = false
        cpu.registers.flagH = true
        cpu.registers.flagC = false

        val cycles = cpu.executeInstruction()

        assertEquals(8, cycles)
        assertEquals(0xABu.toUByte(), memory[0xC000])
        assertEquals(0x0101u.toUShort(), cpu.registers.pc)
        // フラグは変化しない
        assertEquals(false, cpu.registers.flagZ)
        assertEquals(false, cpu.registers.flagN)
        assertEquals(true, cpu.registers.flagH)
        assertEquals(false, cpu.registers.flagC)
    }

    @Test
    fun `LD HL+ A stores A then increments HL without touching flags`() {
        val memory = UByteArray(MEMORY_SIZE) { 0x00u }
        val bus = InMemoryBus(memory)

        // 0x0100: 0x22 (LD (HL+), A)
        memory[0x0100] = 0x22u

        val cpu = Cpu(bus)
        cpu.registers.pc = 0x0100u.toUShort()
        cpu.registers.hl = 0xC000u
        cpu.registers.a = 0x5Au
        cpu.registers.flagZ = false
        cpu.registers.flagN = true
        cpu.registers.flagH = false
        cpu.registers.flagC = true

        val cycles = cpu.executeInstruction()

        // メモリ書き込み
        assertEquals(0x5Au.toUByte(), memory[0xC000])
        // HL は 1 増加
        assertEquals(0xC001u.toUShort(), cpu.registers.hl)
        // PC は 1 バイト進む
        assertEquals(0x0101u.toUShort(), cpu.registers.pc)
        // サイクル数
        assertEquals(8, cycles)
        // フラグは変更されない
        assertEquals(false, cpu.registers.flagZ)
        assertEquals(true, cpu.registers.flagN)
        assertEquals(false, cpu.registers.flagH)
        assertEquals(true, cpu.registers.flagC)
    }

    @Test
    fun `LD A HL+ loads then increments HL without touching flags`() {
        val memory = UByteArray(MEMORY_SIZE) { 0x00u }
        val bus = InMemoryBus(memory)

        // 0x0100: 0x2A (LD A, (HL+))
        memory[0x0100] = 0x2Au
        // HL が指すアドレスに値を置いておく
        memory[0xC000] = 0x7Fu

        val cpu = Cpu(bus)
        cpu.registers.pc = 0x0100u.toUShort()
        cpu.registers.hl = 0xC000u
        cpu.registers.a = 0x00u
        cpu.registers.flagZ = true
        cpu.registers.flagN = false
        cpu.registers.flagH = true
        cpu.registers.flagC = false

        val cycles = cpu.executeInstruction()

        // メモリからロード
        assertEquals(0x7Fu.toUByte(), cpu.registers.a)
        // HL は 1 増加
        assertEquals(0xC001u.toUShort(), cpu.registers.hl)
        // PC は 1 バイト進む
        assertEquals(0x0101u.toUShort(), cpu.registers.pc)
        // サイクル数
        assertEquals(8, cycles)
        // フラグは変更されない
        assertEquals(true, cpu.registers.flagZ)
        assertEquals(false, cpu.registers.flagN)
        assertEquals(true, cpu.registers.flagH)
        assertEquals(false, cpu.registers.flagC)
    }

    @Test
    fun `INC DE increments 16bit register and keeps flags unchanged`() {
        val memory = UByteArray(MEMORY_SIZE) { 0x00u }
        val bus = InMemoryBus(memory)

        // 0x0100: 0x13 (INC DE)
        memory[0x0100] = 0x13u

        val cpu = Cpu(bus)
        cpu.registers.pc = 0x0100u.toUShort()
        cpu.registers.de = 0xABCDu
        cpu.registers.flagZ = true
        cpu.registers.flagN = false
        cpu.registers.flagH = false
        cpu.registers.flagC = true

        val cycles = cpu.executeInstruction()

        assertEquals(8, cycles)
        assertEquals(0xABCEu.toUShort(), cpu.registers.de)
        assertEquals(0x0101u.toUShort(), cpu.registers.pc)
        // フラグはすべて変更されない
        assertEquals(true, cpu.registers.flagZ)
        assertEquals(false, cpu.registers.flagN)
        assertEquals(false, cpu.registers.flagH)
        assertEquals(true, cpu.registers.flagC)
    }

    @Test
    fun `DEC DE decrements 16bit register and keeps flags unchanged`() {
        val memory = UByteArray(MEMORY_SIZE) { 0x00u }
        val bus = InMemoryBus(memory)

        // 0x0100: 0x1B (DEC DE)
        memory[0x0100] = 0x1Bu

        val cpu = Cpu(bus)
        cpu.registers.pc = 0x0100u.toUShort()
        cpu.registers.de = 0x0000u
        cpu.registers.flagZ = true
        cpu.registers.flagN = true
        cpu.registers.flagH = false
        cpu.registers.flagC = false

        val cycles = cpu.executeInstruction()

        assertEquals(8, cycles)
        assertEquals(0xFFFFu.toUShort(), cpu.registers.de)
        assertEquals(0x0101u.toUShort(), cpu.registers.pc)
        // フラグはすべて変更されない
        assertEquals(true, cpu.registers.flagZ)
        assertEquals(true, cpu.registers.flagN)
        assertEquals(false, cpu.registers.flagH)
        assertEquals(false, cpu.registers.flagC)
    }

    @Test
    fun `INC SP increments 16bit register and keeps flags unchanged`() {
        val memory = UByteArray(MEMORY_SIZE) { 0x00u }
        val bus = InMemoryBus(memory)

        // 0x0100: 0x33 (INC SP)
        memory[0x0100] = 0x33u

        val cpu = Cpu(bus)
        cpu.registers.pc = 0x0100u.toUShort()
        cpu.registers.sp = 0xFFF0u
        cpu.registers.flagZ = false
        cpu.registers.flagN = true
        cpu.registers.flagH = true
        cpu.registers.flagC = false

        val cycles = cpu.executeInstruction()

        assertEquals(8, cycles)
        assertEquals(0xFFF1u.toUShort(), cpu.registers.sp)
        assertEquals(0x0101u.toUShort(), cpu.registers.pc)
        // フラグはすべて変更されない
        assertEquals(false, cpu.registers.flagZ)
        assertEquals(true, cpu.registers.flagN)
        assertEquals(true, cpu.registers.flagH)
        assertEquals(false, cpu.registers.flagC)
    }

    @Test
    fun `DEC SP decrements 16bit register and keeps flags unchanged`() {
        val memory = UByteArray(MEMORY_SIZE) { 0x00u }
        val bus = InMemoryBus(memory)

        // 0x0100: 0x3B (DEC SP)
        memory[0x0100] = 0x3Bu

        val cpu = Cpu(bus)
        cpu.registers.pc = 0x0100u.toUShort()
        cpu.registers.sp = 0x0001u
        cpu.registers.flagZ = true
        cpu.registers.flagN = false
        cpu.registers.flagH = false
        cpu.registers.flagC = true

        val cycles = cpu.executeInstruction()

        assertEquals(8, cycles)
        assertEquals(0x0000u.toUShort(), cpu.registers.sp)
        assertEquals(0x0101u.toUShort(), cpu.registers.pc)
        // フラグはすべて変更されない
        assertEquals(true, cpu.registers.flagZ)
        assertEquals(false, cpu.registers.flagN)
        assertEquals(false, cpu.registers.flagH)
        assertEquals(true, cpu.registers.flagC)
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
