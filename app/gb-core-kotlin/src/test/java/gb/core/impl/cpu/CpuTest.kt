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
    fun `CB 67 BIT 4 A sets Z flag depending on bit 4 of A`() {
        val memory = UByteArray(MEMORY_SIZE) { 0x00u }
        val bus = InMemoryBus(memory)

        // 0x0100: 0xCB (prefix), 0x0101: 0x67 (BIT 4, A)
        memory[0x0100] = 0xCBu
        memory[0x0101] = 0x67u

        val cpu = Cpu(bus)
        cpu.registers.pc = 0x0100u.toUShort()
        cpu.registers.a = 0x10u // bit4 = 1
        cpu.registers.flagZ = true
        cpu.registers.flagN = true
        cpu.registers.flagH = false
        cpu.registers.flagC = true

        val cycles1 = cpu.executeInstruction()

        // bit4=1 なので Z=0、N=0、H=1、Cは維持
        assertEquals(8, cycles1)
        assertEquals(0x0102u.toUShort(), cpu.registers.pc)
        assertEquals(false, cpu.registers.flagZ)
        assertEquals(false, cpu.registers.flagN)
        assertEquals(true, cpu.registers.flagH)
        assertEquals(true, cpu.registers.flagC)

        // もう一度同じ命令を実行する準備: PC を戻し、A の bit4 を 0 にする
        cpu.registers.pc = 0x0100u.toUShort()
        cpu.registers.a = 0x00u

        val cycles2 = cpu.executeInstruction()

        // bit4=0 なので Z=1、N=0、H=1、Cはそのまま
        assertEquals(8, cycles2)
        assertEquals(0x0102u.toUShort(), cpu.registers.pc)
        assertEquals(true, cpu.registers.flagZ)
        assertEquals(false, cpu.registers.flagN)
        assertEquals(true, cpu.registers.flagH)
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
    fun `INC B updates value and flags like INC A`() {
        val memory = UByteArray(MEMORY_SIZE) { 0x00u }
        val bus = InMemoryBus(memory)

        // 0x0100: 0x04 (INC B)
        memory[0x0100] = 0x04u

        val cpu = Cpu(bus)
        cpu.registers.pc = 0x0100u.toUShort()
        cpu.registers.b = 0x0Fu
        cpu.registers.flagC = true

        val cycles1 = cpu.executeInstruction()

        // 0x0F -> 0x10 で H=1, Z=0, Cは維持
        assertEquals(4, cycles1)
        assertEquals(0x10u.toUByte(), cpu.registers.b)
        assertEquals(false, cpu.registers.flagZ)
        assertEquals(false, cpu.registers.flagN)
        assertEquals(true, cpu.registers.flagH)
        assertEquals(true, cpu.registers.flagC)

        // 0xFF -> 0x00 のとき Z=1, H=1
        cpu.registers.pc = 0x0100u.toUShort()
        cpu.registers.b = 0xFFu
        val cycles2 = cpu.executeInstruction()

        assertEquals(4, cycles2)
        assertEquals(0x00u.toUByte(), cpu.registers.b)
        assertEquals(true, cpu.registers.flagZ)
        assertEquals(false, cpu.registers.flagN)
        assertEquals(true, cpu.registers.flagH)
        assertEquals(true, cpu.registers.flagC)
    }

    @Test
    fun `DEC E updates value and flags correctly`() {
        val memory = UByteArray(MEMORY_SIZE) { 0x00u }
        val bus = InMemoryBus(memory)

        // 0x0100: 0x1D (DEC E)
        memory[0x0100] = 0x1Du

        val cpu = Cpu(bus)
        cpu.registers.pc = 0x0100u.toUShort()
        cpu.registers.e = 0x10u
        cpu.registers.flagC = false

        val cycles1 = cpu.executeInstruction()

        // 0x10 -> 0x0F で H=1, N=1, Z=0
        assertEquals(4, cycles1)
        assertEquals(0x0Fu.toUByte(), cpu.registers.e)
        assertEquals(false, cpu.registers.flagZ)
        assertEquals(true, cpu.registers.flagN)
        assertEquals(true, cpu.registers.flagH)
        assertEquals(false, cpu.registers.flagC)

        // 0x01 -> 0x00 で Z=1, H=0
        cpu.registers.pc = 0x0100u.toUShort()
        cpu.registers.e = 0x01u
        val cycles2 = cpu.executeInstruction()

        assertEquals(4, cycles2)
        assertEquals(0x00u.toUByte(), cpu.registers.e)
        assertEquals(true, cpu.registers.flagZ)
        assertEquals(true, cpu.registers.flagN)
        assertEquals(false, cpu.registers.flagH)
    }

    @Test
    fun `INC HL memory increments value and uses 12 cycles`() {
        val memory = UByteArray(MEMORY_SIZE) { 0x00u }
        val bus = InMemoryBus(memory)

        // 0x0100: 0x34 (INC (HL))
        memory[0x0100] = 0x34u
        memory[0xC000] = 0x0Fu

        val cpu = Cpu(bus)
        cpu.registers.pc = 0x0100u.toUShort()
        cpu.registers.hl = 0xC000u

        val cycles = cpu.executeInstruction()

        assertEquals(12, cycles)
        assertEquals(0x10u.toUByte(), memory[0xC000])
        assertEquals(false, cpu.registers.flagZ)
        assertEquals(false, cpu.registers.flagN)
        assertEquals(true, cpu.registers.flagH)
    }

    @Test
    fun `DEC HL memory decrements value and uses 12 cycles`() {
        val memory = UByteArray(MEMORY_SIZE) { 0x00u }
        val bus = InMemoryBus(memory)

        // 0x0100: 0x35 (DEC (HL))
        memory[0x0100] = 0x35u
        memory[0xC000] = 0x10u

        val cpu = Cpu(bus)
        cpu.registers.pc = 0x0100u.toUShort()
        cpu.registers.hl = 0xC000u

        val cycles = cpu.executeInstruction()

        assertEquals(12, cycles)
        assertEquals(0x0Fu.toUByte(), memory[0xC000])
        assertEquals(false, cpu.registers.flagZ)
        assertEquals(true, cpu.registers.flagN)
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

    @Test
    fun `JP nn jumps to absolute address`() {
        val memory = UByteArray(MEMORY_SIZE) { 0x00u }
        val bus = InMemoryBus(memory)

        // 0x0100: C3 nn nn (JP nn), nn=0x1234
        memory[0x0100] = 0xC3u
        memory[0x0101] = 0x34u // low
        memory[0x0102] = 0x12u // high

        val cpu = Cpu(bus)
        cpu.registers.pc = 0x0100u

        val cycles = cpu.executeInstruction()

        assertEquals(16, cycles)
        assertEquals(0x1234u.toUShort(), cpu.registers.pc)
    }

    @Test
    fun `JP NZ nn only jumps when Z is false`() {
        val memory = UByteArray(MEMORY_SIZE) { 0x00u }
        val bus = InMemoryBus(memory)

        memory[0x0100] = 0xC2u // JP NZ, nn
        memory[0x0101] = 0x00u
        memory[0x0102] = 0x20u // nn=0x2000

        val cpu = Cpu(bus)

        // Z=false → ジャンプする
        cpu.registers.pc = 0x0100u
        cpu.registers.flagZ = false
        var cycles = cpu.executeInstruction()
        assertEquals(16, cycles)
        assertEquals(0x2000u.toUShort(), cpu.registers.pc)

        // Z=true → ジャンプしない（PC は即値分だけ進む）
        cpu.registers.pc = 0x0100u
        cpu.registers.flagZ = true
        cycles = cpu.executeInstruction()
        assertEquals(12, cycles)
        assertEquals(0x0103u.toUShort(), cpu.registers.pc)
    }

    @Test
    fun `JR e jumps relative with signed offset`() {
        val memory = UByteArray(MEMORY_SIZE) { 0x00u }
        val bus = InMemoryBus(memory)

        // 0x0100: 18 FE (JR -2) → 0x0100 に戻る
        memory[0x0100] = 0x18u
        memory[0x0101] = 0xFEu // -2

        val cpu = Cpu(bus)
        cpu.registers.pc = 0x0100u

        val cycles = cpu.executeInstruction()

        assertEquals(12, cycles)
        assertEquals(0x0100u.toUShort(), cpu.registers.pc)
    }

    @Test
    fun `JR Z e only jumps when Z is true`() {
        val memory = UByteArray(MEMORY_SIZE) { 0x00u }
        val bus = InMemoryBus(memory)

        // 0x0100: 28 02 (JR Z, +2)
        memory[0x0100] = 0x28u
        memory[0x0101] = 0x02u

        val cpu = Cpu(bus)

        // Z=true → ジャンプする
        cpu.registers.pc = 0x0100u
        cpu.registers.flagZ = true
        var cycles = cpu.executeInstruction()
        assertEquals(12, cycles)
        // PC: 0x0100 (opcode) -> 0x0101 (after fetch) -> 0x0102 (after reading e) -> 0x0104 (+2)
        assertEquals(0x0104u.toUShort(), cpu.registers.pc)

        // Z=false → ジャンプしない（PC は即値分だけ進む）
        cpu.registers.pc = 0x0100u
        cpu.registers.flagZ = false
        cycles = cpu.executeInstruction()
        assertEquals(8, cycles)
        assertEquals(0x0102u.toUShort(), cpu.registers.pc)
    }

    @Test
    fun `ADD A B adds registers and sets flags correctly`() {
        val memory = UByteArray(MEMORY_SIZE) { 0x00u }
        val bus = InMemoryBus(memory)

        // 0x0100: 0x80 (ADD A, B)
        memory[0x0100] = 0x80u

        val cpu = Cpu(bus)
        cpu.registers.pc = 0x0100u.toUShort()
        cpu.registers.a = 0x0Fu
        cpu.registers.b = 0x01u
        cpu.registers.flagC = false

        val cycles = cpu.executeInstruction()

        assertEquals(4, cycles)
        assertEquals(0x10u.toUByte(), cpu.registers.a)
        // 0x0F + 0x01 -> H=1, Z=0, C=0
        assertEquals(false, cpu.registers.flagZ)
        assertEquals(false, cpu.registers.flagN)
        assertEquals(true, cpu.registers.flagH)
        assertEquals(false, cpu.registers.flagC)
    }

    @Test
    fun `ADD A n adds immediate and can set carry and zero`() {
        val memory = UByteArray(MEMORY_SIZE) { 0x00u }
        val bus = InMemoryBus(memory)

        // 0x0100: 0xC6 (ADD A, n), 0x0101: 0x01
        memory[0x0100] = 0xC6u
        memory[0x0101] = 0x01u

        val cpu = Cpu(bus)
        cpu.registers.pc = 0x0100u.toUShort()
        cpu.registers.a = 0xFFu
        cpu.registers.flagC = false

        val cycles = cpu.executeInstruction()

        assertEquals(8, cycles)
        assertEquals(0x00u.toUByte(), cpu.registers.a)
        // 0xFF + 0x01 -> 0x00, Z=1, H=1, C=1
        assertEquals(true, cpu.registers.flagZ)
        assertEquals(false, cpu.registers.flagN)
        assertEquals(true, cpu.registers.flagH)
        assertEquals(true, cpu.registers.flagC)
    }

    @Test
    fun `ADD A HL adds value from memory`() {
        val memory = UByteArray(MEMORY_SIZE) { 0x00u }
        val bus = InMemoryBus(memory)

        // 0x0100: 0x86 (ADD A, (HL))
        memory[0x0100] = 0x86u
        memory[0xC000] = 0x20u

        val cpu = Cpu(bus)
        cpu.registers.pc = 0x0100u.toUShort()
        cpu.registers.hl = 0xC000u
        cpu.registers.a = 0x10u
        cpu.registers.flagC = false

        val cycles = cpu.executeInstruction()

        assertEquals(8, cycles)
        assertEquals(0x30u.toUByte(), cpu.registers.a)
        assertEquals(false, cpu.registers.flagZ)
        assertEquals(false, cpu.registers.flagN)
        assertEquals(false, cpu.registers.flagH)
        assertEquals(false, cpu.registers.flagC)
    }

    @Test
    fun `DAA adjusts A for BCD after ADD and SUB`() {
        val memory = UByteArray(MEMORY_SIZE) { 0x00u }
        val bus = InMemoryBus(memory)

        // 0x0100: 0x27 (DAA)
        memory[0x0100] = 0x27u

        val cpu = Cpu(bus)

        // 加算後の例: A=0x0A, H=1, C=0 -> 0x10
        cpu.registers.pc = 0x0100u.toUShort()
        cpu.registers.a = 0x0Au
        cpu.registers.flagN = false
        cpu.registers.flagH = true
        cpu.registers.flagC = false

        val cycles1 = cpu.executeInstruction()

        assertEquals(4, cycles1)
        assertEquals(0x10u.toUByte(), cpu.registers.a)
        assertEquals(false, cpu.registers.flagZ)
        assertEquals(false, cpu.registers.flagN)
        assertEquals(false, cpu.registers.flagH)
        assertEquals(false, cpu.registers.flagC)

        // 減算後の例: A=0x15, N=1, H=1, C=0 -> 0x0F
        cpu.registers.pc = 0x0100u.toUShort()
        cpu.registers.a = 0x15u
        cpu.registers.flagN = true
        cpu.registers.flagH = true
        cpu.registers.flagC = false

        val cycles2 = cpu.executeInstruction()

        assertEquals(4, cycles2)
        assertEquals(0x0Fu.toUByte(), cpu.registers.a)
        assertEquals(false, cpu.registers.flagZ)
        assertEquals(true, cpu.registers.flagN)
        assertEquals(false, cpu.registers.flagH)
        assertEquals(false, cpu.registers.flagC)
    }

    @Test
    fun `JP nn sets PC to absolute address`() {
        val memory = UByteArray(MEMORY_SIZE) { 0x00u }
        val bus = InMemoryBus(memory)

        // 0x0100: C3 34 12  (JP 0x1234)
        memory[0x0100] = 0xC3u
        memory[0x0101] = 0x34u // low
        memory[0x0102] = 0x12u // high

        val cpu = Cpu(bus)
        cpu.registers.pc = 0x0100u.toUShort()

        val cycles = cpu.executeInstruction()

        assertEquals(16, cycles)
        assertEquals(0x1234u.toUShort(), cpu.registers.pc)
    }

    @Test
    fun `JP NZ nn jumps only when Z is false`() {
        val memory = UByteArray(MEMORY_SIZE) { 0x00u }
        val bus = InMemoryBus(memory)

        // 0x0100: C2 78 56  (JP NZ, 0x5678)
        memory[0x0100] = 0xC2u
        memory[0x0101] = 0x78u
        memory[0x0102] = 0x56u

        val cpu = Cpu(bus)

        // Z = false → ジャンプする
        cpu.registers.pc = 0x0100u.toUShort()
        cpu.registers.flagZ = false
        var cycles = cpu.executeInstruction()
        assertEquals(16, cycles)
        assertEquals(0x5678u.toUShort(), cpu.registers.pc)

        // Z = true → ジャンプしない（PC は即値を読み飛ばして 0x0103）
        cpu.registers.pc = 0x0100u.toUShort()
        cpu.registers.flagZ = true
        cycles = cpu.executeInstruction()
        assertEquals(12, cycles)
        assertEquals(0x0103u.toUShort(), cpu.registers.pc)
    }

    @Test
    fun `JR e adds signed offset to PC`() {
        val memory = UByteArray(MEMORY_SIZE) { 0x00u }
        val bus = InMemoryBus(memory)

        // 0x0100: 18 FE  (JR -2)  → PC=0x0100 -> 0x0102 -> 0x0100
        memory[0x0100] = 0x18u
        memory[0x0101] = 0xFEu // -2

        val cpu = Cpu(bus)
        cpu.registers.pc = 0x0100u.toUShort()

        val cycles = cpu.executeInstruction()

        assertEquals(12, cycles)
        assertEquals(0x0100u.toUShort(), cpu.registers.pc)
    }

    @Test
    fun `JR NZ e jumps only when Z is false`() {
        val memory = UByteArray(MEMORY_SIZE) { 0x00u }
        val bus = InMemoryBus(memory)

        // 0x0100: 20 02  (JR NZ, +2)
        memory[0x0100] = 0x20u
        memory[0x0101] = 0x02u

        val cpu = Cpu(bus)

        // Z = false → ジャンプする: PC=0x0100 -> 0x0102 -> 0x0104
        cpu.registers.pc = 0x0100u.toUShort()
        cpu.registers.flagZ = false
        var cycles = cpu.executeInstruction()
        assertEquals(12, cycles)
        assertEquals(0x0104u.toUShort(), cpu.registers.pc)

        // Z = true → ジャンプしない: PC=0x0100 -> 0x0102
        cpu.registers.pc = 0x0100u.toUShort()
        cpu.registers.flagZ = true
        cycles = cpu.executeInstruction()
        assertEquals(8, cycles)
        assertEquals(0x0102u.toUShort(), cpu.registers.pc)
    }

    @Test
    fun `CALL nn pushes return address and jumps`() {
        val memory = UByteArray(MEMORY_SIZE) { 0x00u }
        val bus = InMemoryBus(memory)

        // 0x0100: CD 34 12  (CALL 0x1234)
        memory[0x0100] = 0xCDu
        memory[0x0101] = 0x34u
        memory[0x0102] = 0x12u

        val cpu = Cpu(bus)
        cpu.registers.pc = 0x0100u.toUShort()
        cpu.registers.sp = 0xFFFEu

        val cycles = cpu.executeInstruction()

        // CALL は 24 cycles
        assertEquals(24, cycles)
        // PC は 0x1234 へ
        assertEquals(0x1234u.toUShort(), cpu.registers.pc)
        // 戻りアドレス 0x0103 がスタックに積まれ、SP が 2 減少していることだけ確認
        assertEquals(0xFFFCu.toUShort(), cpu.registers.sp)
    }

    @Test
    fun `CALL NZ nn only pushes and jumps when Z is false`() {
        val memory = UByteArray(MEMORY_SIZE) { 0x00u }
        val bus = InMemoryBus(memory)

        // 0x0100: C4 78 56  (CALL NZ, 0x5678)
        memory[0x0100] = 0xC4u
        memory[0x0101] = 0x78u
        memory[0x0102] = 0x56u

        val cpu = Cpu(bus)
        cpu.registers.sp = 0xFFFEu

        // Z=false → CALL 実行
        cpu.registers.pc = 0x0100u.toUShort()
        cpu.registers.flagZ = false
        var cycles = cpu.executeInstruction()
        assertEquals(24, cycles)
        assertEquals(0x5678u.toUShort(), cpu.registers.pc)
        assertEquals(0xFFFCu.toUShort(), cpu.registers.sp)

        // Z=true → 何もせず即値を読み飛ばすだけ
        cpu.registers.pc = 0x0100u.toUShort()
        cpu.registers.flagZ = true
        cpu.registers.sp = 0xFFFEu
        cycles = cpu.executeInstruction()
        assertEquals(12, cycles)
        assertEquals(0x0103u.toUShort(), cpu.registers.pc)
        // SP とスタック内容は不変（未使用）
        assertEquals(0xFFFEu.toUShort(), cpu.registers.sp)
    }

    @Test
    fun `RET pops address from stack`() {
        val memory = UByteArray(MEMORY_SIZE) { 0x00u }
        val bus = InMemoryBus(memory)

        // 0x0100: C9 (RET)
        memory[0x0100] = 0xC9u

        val cpu = Cpu(bus)
        cpu.registers.pc = 0x0100u.toUShort()

        // スタックに 0x1234 が積まれているとする（CALL 後など）
        cpu.registers.sp = 0xFFFCu
        bus.writeByte(0xFFFCu, 0x34u) // low
        bus.writeByte(0xFFFDU, 0x12u) // high

        val cycles = cpu.executeInstruction()

        assertEquals(16, cycles)
        assertEquals(0x1234u.toUShort(), cpu.registers.pc)
        assertEquals(0xFFFEu.toUShort(), cpu.registers.sp)
    }

    @Test
    fun `RET NZ only pops when Z is false`() {
        val memory = UByteArray(MEMORY_SIZE) { 0x00u }
        val bus = InMemoryBus(memory)

        // 0x0100: C0 (RET NZ)
        memory[0x0100] = 0xC0u

        val cpu = Cpu(bus)

        // Z=false → RET 実行
        cpu.registers.pc = 0x0100u.toUShort()
        cpu.registers.flagZ = false
        cpu.registers.sp = 0xFFFCu
        bus.writeByte(0xFFFCu, 0x78u)
        bus.writeByte(0xFFFDU, 0x56u)
        var cycles = cpu.executeInstruction()
        assertEquals(20, cycles)
        assertEquals(0x5678u.toUShort(), cpu.registers.pc)
        assertEquals(0xFFFEu.toUShort(), cpu.registers.sp)

        // Z=true → 何もしない
        cpu.registers.pc = 0x0100u.toUShort()
        cpu.registers.flagZ = true
        cpu.registers.sp = 0xFFFCu
        cycles = cpu.executeInstruction()
        assertEquals(8, cycles)
        assertEquals(0xFFFCu.toUShort(), cpu.registers.sp)
    }

    @Test
    fun `RST 38h pushes return address and jumps to vector`() {
        val memory = UByteArray(MEMORY_SIZE) { 0x00u }
        val bus = InMemoryBus(memory)

        // 0x0100: FF (RST 38H)
        memory[0x0100] = 0xFFu

        val cpu = Cpu(bus)
        cpu.registers.pc = 0x0100u.toUShort()
        cpu.registers.sp = 0xFFFEu

        val cycles = cpu.executeInstruction()

        assertEquals(16, cycles)
        // PC は 0x0038 へ
        assertEquals(0x0038u.toUShort(), cpu.registers.pc)
        // 戻りアドレス 0x0101 がスタックに積まれ、SP が 2 減少していることだけ確認
        assertEquals(0xFFFCu.toUShort(), cpu.registers.sp)
    }

    @Test
    fun `PUSH and POP BC save and restore register pair`() {
        val memory = UByteArray(MEMORY_SIZE) { 0x00u }
        val bus = InMemoryBus(memory)

        // 0x0100: C5 (PUSH BC), C1 (POP BC)
        memory[0x0100] = 0xC5u
        memory[0x0101] = 0xC1u

        val cpu = Cpu(bus)
        cpu.registers.pc = 0x0100u.toUShort()
        cpu.registers.sp = 0xFFFEu
        cpu.registers.b = 0x12u
        cpu.registers.c = 0x34u

        val cyclesPush = cpu.executeInstruction()
        assertEquals(16, cyclesPush)
        assertEquals(0xFFFCu.toUShort(), cpu.registers.sp)

        // 別の値を入れてから POP で元に戻ることを確認
        cpu.registers.b = 0x00u
        cpu.registers.c = 0x00u

        val cyclesPop = cpu.executeInstruction()
        assertEquals(12, cyclesPop)
        assertEquals(0xFFFEu.toUShort(), cpu.registers.sp)
        assertEquals(0x12u.toUByte(), cpu.registers.b)
        assertEquals(0x34u.toUByte(), cpu.registers.c)
    }

    @Test
    fun `PUSH and POP AF save and restore flags`() {
        val memory = UByteArray(MEMORY_SIZE) { 0x00u }
        val bus = InMemoryBus(memory)

        // 0x0100: F5 (PUSH AF), F1 (POP AF)
        memory[0x0100] = 0xF5u
        memory[0x0101] = 0xF1u

        val cpu = Cpu(bus)
        cpu.registers.pc = 0x0100u.toUShort()
        cpu.registers.sp = 0xFFFEu

        // 適当な A とフラグをセット
        cpu.registers.a = 0xABu
        cpu.registers.flagZ = true
        cpu.registers.flagN = false
        cpu.registers.flagH = true
        cpu.registers.flagC = false

        val cyclesPush = cpu.executeInstruction()
        assertEquals(16, cyclesPush)
        assertEquals(0xFFFCu.toUShort(), cpu.registers.sp)

        // A とフラグを別の値に変えてから POP
        cpu.registers.a = 0x00u
        cpu.registers.flagZ = false
        cpu.registers.flagN = true
        cpu.registers.flagH = false
        cpu.registers.flagC = true

        val cyclesPop = cpu.executeInstruction()
        assertEquals(12, cyclesPop)
        assertEquals(0xFFFEu.toUShort(), cpu.registers.sp)

        // A とフラグが元に戻っていること
        assertEquals(0xABu.toUByte(), cpu.registers.a)
        assertEquals(true, cpu.registers.flagZ)
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
