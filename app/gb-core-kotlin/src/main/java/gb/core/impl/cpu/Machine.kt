package gb.core.impl.cpu

import kotlin.ExperimentalUnsignedTypes

/**
 * CPU・メモリバス・タイマ・割り込みコントローラを束ねた「本体」クラス。
 *
 * - 1 命令ごとの実行 (`stepInstruction`) と、割り込み受付を担当する。
 * - 将来的に PPU や Joypad もここに統合していく。
 */
@OptIn(ExperimentalUnsignedTypes::class)
class Machine(
    rom: UByteArray,
) {
    private val interruptController = InterruptController()
    private val timer = Timer(interruptController)
    private val joypad = Joypad(interruptController)
    val sound: Sound = Sound()

    private val bus: SystemBus

    val cpu: Cpu
    val ppu: Ppu

    /**
     * デバッグ用: IFレジスタを読み取る
     */
    fun readIf(): UByte = interruptController.readIf()

    /**
     * デバッグ用: IEレジスタを読み取る
     */
    fun readIe(): UByte = interruptController.readIe()

    /**
     * 1 命令を実行し、その間に消費した総サイクル数（割り込みサービスを含む）を返す。
     *
     * - 流れ:
     *   1. CPU で 1 命令実行
     *   2. そのサイクル数をタイマへ通知（DIV/TIMA 更新）
     *   3. IME と IF/IE を見て割り込みがあればサービスし、その分のサイクルもタイマへ通知
     */
    fun stepInstruction(): Int {
        val cycles = cpu.executeInstruction()
        timer.step(cycles)
        ppu.step(cycles)
        sound.step(cycles)

        val interruptCycles = handleInterrupts()
        if (interruptCycles > 0) {
            timer.step(interruptCycles)
            sound.step(interruptCycles)
        }

        return cycles + interruptCycles
    }

    /**
     * 現在の入力状態を Joypad に反映する。
     *
     * - 1 フレームの先頭で GameBoyCoreImpl から呼び出される想定。
     */
    fun updateInput(input: gb.core.api.InputState) {
        joypad.updateInput(input)
    }

    private fun handleInterrupts(): Int {
        val ifReg = interruptController.readIf()
        val ieReg = interruptController.readIe()

        if (ifReg == 0u.toUByte()) {
            return 0
        }

        // IMEが有効な場合、割り込みを処理
        if (cpu.isInterruptsEnabled()) {
            val pending = interruptController.nextPending(true)
            if (pending != null) {
                return cpu.serviceInterrupt(pending)
            }
            // IMEが有効でも、IEレジスタで許可されていない場合は割り込みを処理しない
            // しかし、HALT状態の場合は解除する必要がある
        }

        // HALT状態の場合、IFレジスタにフラグが立っていればHALT状態を解除
        // （IMEが無効でも、またはIEレジスタが無効でも、HALT状態は解除される）
        // 実機では、HALT状態でIMEが無効の場合、HALTバグが発生するが、ここでは簡略化
        if (cpu.isHalted() && ifReg != 0u.toUByte()) {
            // HALT状態を解除（割り込み待ちが完了）
            cpu.wakeFromHalt()
        }

        return 0
    }

    init {
        val (mbc1, cartridgeRam) = createMbc1AndRamIfNeeded(rom)
        val vram = UByteArray(0x2000) { 0u }
        val oam = UByteArray(0xA0) { 0u }
        ppu = Ppu(vram, oam, interruptController)
        bus =
            SystemBus(
                rom = rom,
                vram = vram,
                oam = oam,
                cartridgeRam = cartridgeRam,
                interruptController = interruptController,
                timer = timer,
                mbc1 = mbc1,
                joypad = joypad,
                ppu = ppu,
                sound = sound,
            )
        cpu = Cpu(bus)

        // Game Boy 起動時のレジスタ初期化
        // 実機では 0x0100 から実行開始（0x0000-0x00FF はブートROM領域だが、ここでは省略）
        cpu.registers.pc = 0x0100u.toUShort()
        cpu.registers.sp = 0xFFFEu.toUShort()
        // 実機の起動時レジスタ値（DMG のブートROM 終了時の状態）
        cpu.registers.af = 0x01B0u.toUShort() // A=0x01, F=0xB0 (Z=1, N=0, H=1, C=1)
        cpu.registers.bc = 0x0013u.toUShort()
        cpu.registers.de = 0x00D8u.toUShort()
        cpu.registers.hl = 0x014Du.toUShort()
    }

    private fun createMbc1AndRamIfNeeded(rom: UByteArray): Pair<Mbc1?, UByteArray?> {
        if (rom.isEmpty()) {
            return null to null
        }

        val cartridgeType = rom.getOrNull(0x0147)?.toInt() ?: 0
        val hasMbc1 =
            when (cartridgeType) {
                0x01, // MBC1
                0x02, // MBC1+RAM
                0x03, // MBC1+RAM+BATTERY
                -> true
                else -> false
            }

        val ramSizeCode = rom.getOrNull(0x0149)?.toInt() ?: 0
        val ramSizeBytes =
            when (ramSizeCode) {
                0x00 -> 0 // no RAM
                0x01 -> 0x800 // 2KB
                0x02 -> 0x2000 // 8KB
                0x03 -> 0x8000 // 32KB (4 banks)
                0x04 -> 0x20000 // 128KB
                0x05 -> 0x10000 // 64KB
                else -> 0
            }

        val cartridgeRam =
            if (ramSizeBytes > 0 && hasMbc1) {
                UByteArray(ramSizeBytes) { 0u }
            } else {
                null
            }

        val mbc1 =
            if (hasMbc1) {
                Mbc1(romSize = rom.size, ramSize = ramSizeBytes)
            } else {
                null
            }

        return mbc1 to cartridgeRam
    }
}
