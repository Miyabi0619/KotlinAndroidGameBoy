package gb.core.impl.cpu

import kotlin.ExperimentalUnsignedTypes

/**
 * Game Boy 全体のメモリマップを扱う Bus 実装。
 *
 * - 0000–7FFF: ROM（現時点ではバンク切り替えなし）
 * - 8000–9FFF: VRAM
 * - A000–BFFF: カートリッジ RAM（未接続なら読み書き無視）
 * - C000–DFFF: WRAM
 * - E000–FDFF: Echo RAM（C000–DDFF のミラー）
 * - FE00–FE9F: OAM
 * - FEA0–FEFF: 未使用（読み取り 0xFF, 書き込み無視）
 * - FF00–FF7F: I/O レジスタ
 * - FF80–FFFE: HRAM
 * - FFFF: IE レジスタ
 */
@OptIn(ExperimentalUnsignedTypes::class)
class SystemBus(
    private val rom: UByteArray,
    private val wram: UByteArray = UByteArray(0x2000) { 0u },
    private val vram: UByteArray = UByteArray(0x2000) { 0u },
    private val oam: UByteArray = UByteArray(0xA0) { 0u },
    private val hram: UByteArray = UByteArray(0x7F) { 0u },
    private val cartridgeRam: UByteArray? = null,
    private val interruptController: InterruptController,
    private val timer: Timer,
) : Bus {
    /**
     * Joypad 入力レジスタ（FF00）。
     *
     * - 現時点では単純なバッファとしてのみ保持し、入力ロジックは後続で実装する。
     */
    private var joypadReg: UByte = 0xFFu

    /**
     * 汎用 I/O レジスタ（FF10–FF7F）のバックアップ。
     *
     * - PPU やサウンドなどはまだ未実装なので、とりあえず値を保持するだけ。
     */
    private val ioRegs: UByteArray = UByteArray(0x70) { 0u }

    override fun readByte(address: UShort): UByte {
        val addr = address.toInt()
        return when (addr) {
            in 0x0000..0x7FFF -> {
                rom.getOrElse(addr) { 0xFFu }
            }
            in 0x8000..0x9FFF -> {
                vram[addr - 0x8000]
            }
            in 0xA000..0xBFFF -> {
                val ram = cartridgeRam ?: return 0xFFu
                ram[addr - 0xA000]
            }
            in 0xC000..0xDFFF -> {
                wram[addr - 0xC000]
            }
            in 0xE000..0xFDFF -> {
                // Echo RAM（C000–DDFF のミラー）
                val echoAddr = addr - 0x2000
                if (echoAddr in 0xC000..0xDFFF) {
                    wram[echoAddr - 0xC000]
                } else {
                    0xFFu
                }
            }
            in 0xFE00..0xFE9F -> {
                oam[addr - 0xFE00]
            }
            in 0xFEA0..0xFEFF -> {
                // 未使用領域
                0xFFu
            }
            0xFF00 -> joypadReg
            in 0xFF04..0xFF07 -> {
                // タイマレジスタ（DIV/TIMA/TMA/TAC）
                val offset = addr - 0xFF04
                timer.readRegister(offset)
            }
            0xFF0F -> interruptController.readIf()
            in 0xFF10..0xFF7F -> {
                ioRegs[addr - 0xFF10]
            }
            in 0xFF80..0xFFFE -> {
                hram[addr - 0xFF80]
            }
            0xFFFF -> interruptController.readIe()
            else -> 0xFFu
        }
    }

    override fun writeByte(
        address: UShort,
        value: UByte,
    ) {
        val addr = address.toInt()
        when (addr) {
            in 0x0000..0x7FFF -> {
                // ROM 領域（将来的には MBC による制御が入るが、今は読み取り専用）
            }
            in 0x8000..0x9FFF -> {
                vram[addr - 0x8000] = value
            }
            in 0xA000..0xBFFF -> {
                val ram = cartridgeRam ?: return
                ram[addr - 0xA000] = value
            }
            in 0xC000..0xDFFF -> {
                wram[addr - 0xC000] = value
            }
            in 0xE000..0xFDFF -> {
                // Echo RAM: C000–DDFF のミラー
                val echoAddr = addr - 0x2000
                if (echoAddr in 0xC000..0xDFFF) {
                    wram[echoAddr - 0xC000] = value
                }
            }
            in 0xFE00..0xFE9F -> {
                oam[addr - 0xFE00] = value
            }
            in 0xFEA0..0xFEFF -> {
                // 未使用領域: 書き込みは無視
            }
            0xFF00 -> {
                joypadReg = value
            }
            in 0xFF04..0xFF07 -> {
                val offset = addr - 0xFF04
                timer.writeRegister(
                    offset = offset,
                    value = value,
                )
            }
            0xFF0F -> interruptController.writeIf(value)
            in 0xFF10..0xFF7F -> {
                ioRegs[addr - 0xFF10] = value
            }
            in 0xFF80..0xFFFE -> {
                hram[addr - 0xFF80] = value
            }
            0xFFFF -> interruptController.writeIe(value)
        }
    }
}
