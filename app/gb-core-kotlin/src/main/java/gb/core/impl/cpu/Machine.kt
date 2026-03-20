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

    val bus: SystemBus

    val cpu: Cpu
    val ppu: Ppu

    /** バッテリバックアップ付きカートリッジかどうか */
    val hasBattery: Boolean

    /** カートリッジRAMの内容を取得（セーブデータ永続化用） */
    fun getCartridgeRam(): ByteArray? = bus.cartridgeRam?.toByteArray()

    /** カートリッジRAMにデータをロード（セーブデータ復元用） */
    fun loadCartridgeRam(data: ByteArray) {
        val ram = bus.cartridgeRam ?: return
        val copySize = minOf(data.size, ram.size)
        for (i in 0 until copySize) {
            ram[i] = data[i].toUByte()
        }
    }

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
        sound.step(cycles, timer.getDivInternalCounter())
        bus.stepDma(cycles)
        bus.stepSerial(cycles)

        val interruptCycles = handleInterrupts()
        if (interruptCycles > 0) {
            timer.step(interruptCycles)
            ppu.step(interruptCycles)
            sound.step(interruptCycles, timer.getDivInternalCounter())
            bus.stepDma(interruptCycles)
            bus.stepSerial(interruptCycles)
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
        val pendingEnabled = (ifReg and ieReg)

        if (ifReg == 0u.toUByte()) {
            return 0
        }

        // IMEが有効な場合、IE/IFの両方で有効な割り込みだけを処理
        if (cpu.isInterruptsEnabled() && pendingEnabled != 0u.toUByte()) {
            val pending = interruptController.nextPending(true)
            if (pending != null) {
                return cpu.serviceInterrupt(pending)
            }
        }

        // HALT/STOP状態の場合、有効な割り込み要求があれば状態を解除
        // 実機では、IME=0でも IE/IF の両方で有効な割り込みが立てば HALT/STOP は解除される
        if ((cpu.isHalted() || cpu.isStopped()) && pendingEnabled != 0u.toUByte()) {
            cpu.wakeFromHalt()
        }

        return 0
    }

    init {
        // カートリッジタイプからバッテリバックアップの有無を判定
        val cartridgeType = rom.getOrNull(0x0147)?.toInt() ?: 0
        hasBattery =
            when (cartridgeType) {
                0x03, // MBC1+RAM+BATTERY
                0x10, // MBC3+TIMER+RAM+BATTERY
                0x13, // MBC3+RAM+BATTERY
                -> true

                else -> false
            }

        val mbcResult = createMbcAndRamIfNeeded(rom)
        val vram = UByteArray(0x2000) { 0u }
        val oam = UByteArray(0xA0) { 0u }
        ppu = Ppu(vram, oam, interruptController)
        bus =
            SystemBus(
                rom = rom,
                vram = vram,
                oam = oam,
                cartridgeRam = mbcResult.ram,
                interruptController = interruptController,
                timer = timer,
                mbc1 = mbcResult.mbc1,
                mbc3 = mbcResult.mbc3,
                mbc5 = mbcResult.mbc5,
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

    private class MbcResult(
        val mbc1: Mbc1?,
        val mbc3: Mbc3?,
        val mbc5: Mbc5?,
        val ram: UByteArray?,
    )

    private fun createMbcAndRamIfNeeded(rom: UByteArray): MbcResult {
        if (rom.isEmpty()) {
            return MbcResult(null, null, null, null)
        }

        val cartridgeType = rom.getOrNull(0x0147)?.toInt() ?: 0
        val hasMbc1 =
            when (cartridgeType) {
                0x01, 0x02, 0x03 -> true
                else -> false
            }
        val hasMbc3 =
            when (cartridgeType) {
                0x0F, 0x10, 0x11, 0x12, 0x13 -> true
                else -> false
            }
        val hasMbc5 =
            when (cartridgeType) {
                0x19, 0x1A, 0x1B, 0x1C, 0x1D, 0x1E -> true
                else -> false
            }

        val ramSizeCode = rom.getOrNull(0x0149)?.toInt() ?: 0
        val ramSizeBytes =
            when (ramSizeCode) {
                0x00 -> 0

                0x01 -> 0x800

                // 2KB
                0x02 -> 0x2000

                // 8KB
                0x03 -> 0x8000

                // 32KB (4 banks)
                0x04 -> 0x20000

                // 128KB
                0x05 -> 0x10000

                // 64KB
                else -> 0
            }

        val needsRam = (hasMbc1 || hasMbc3 || hasMbc5) && ramSizeBytes > 0
        val cartridgeRam = if (needsRam) UByteArray(ramSizeBytes) { 0u } else null

        val mbc1 = if (hasMbc1) Mbc1(romSize = rom.size, ramSize = ramSizeBytes) else null
        val mbc3 = if (hasMbc3) Mbc3(romSize = rom.size, ramSize = ramSizeBytes) else null
        val mbc5 = if (hasMbc5) Mbc5(romSize = rom.size, ramSize = ramSizeBytes) else null

        return MbcResult(mbc1, mbc3, mbc5, cartridgeRam)
    }
}
