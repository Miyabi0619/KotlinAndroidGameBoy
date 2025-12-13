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
    joypad: Joypad,
    /**
     * PPU インスタンス（I/O レジスタ読み書き用）。
     */
    ppu: Ppu,
    /**
     * サウンド処理（APU）
     */
    sound: Sound,
    /**
     * ROM が MBC1 カートリッジであれば Mbc1 インスタンスを渡す。
     * それ以外のカートリッジでは null（ノーマッパ）とする。
     */
    private val mbc1: Mbc1? = null,
) : Bus {
    /** Joypad 入力レジスタ（FF00）は [joypad] に委譲する。 */
    private val joypad: Joypad = joypad

    /**
     * PPU インスタンス（I/O レジスタ読み書き用）。
     */
    private val ppu: Ppu = ppu

    /**
     * サウンド処理（APU）
     */
    private val sound: Sound = sound

    /**
     * その他 I/O レジスタ（FF50–FF7F）のバックアップ。
     */
    private val ioRegs: UByteArray = UByteArray(0x30) { 0u }

    override fun readByte(address: UShort): UByte {
        val addr = address.toInt()
        return readByteInternal(addr)
    }

    private fun readByteInternal(addr: Int): UByte =
        when {
            // 0x38のアドレスに0xFF（RST 38H）がある場合は、無限ループを防ぐためRETI（0xD9）を返す
            // これは、ROMの内容が0xFFの場合にのみ適用される（実機では通常RETIが配置される）
            addr == 0x38 -> {
                val index = mbc1?.mapRom0(addr) ?: addr
                val romValue = rom.getOrElse(index) { 0xFFu }
                // ROMの内容が0xFF（RST 38H）の場合は、RETI（0xD9）を返す
                if (romValue == 0xFFu.toUByte()) {
                    0xD9u.toUByte() // RETI命令
                } else {
                    romValue
                }
            }
            addr in 0x0000..0x3FFF -> {
                val index = mbc1?.mapRom0(addr) ?: addr
                rom.getOrElse(index) { 0xFFu }
            }
            addr in 0x4000..0x7FFF -> {
                val index = mbc1?.mapRomX(addr) ?: addr
                rom.getOrElse(index) { 0xFFu }
            }
            addr in 0x8000..0x9FFF -> vram[addr - 0x8000]
            addr in 0xA000..0xBFFF -> {
                val ram = cartridgeRam ?: return 0xFFu
                val ramIndex = mbc1?.mapRam(addr) ?: (addr - 0xA000)
                if (ramIndex !in 0 until ram.size) {
                    0xFFu
                } else {
                    ram[ramIndex]
                }
            }
            addr in 0xC000..0xDFFF -> wram[addr - 0xC000]
            addr in 0xE000..0xFDFF -> {
                // Echo RAM（C000–DDFF のミラー）
                val echoAddr = addr - 0x2000
                if (echoAddr in 0xC000..0xDFFF) {
                    wram[echoAddr - 0xC000]
                } else {
                    0xFFu
                }
            }
            addr in 0xFE00..0xFE9F -> oam[addr - 0xFE00]
            addr in 0xFEA0..0xFEFF -> 0xFFu // 未使用領域
            addr == 0xFF00 -> joypad.read()
            addr in 0xFF01..0xFF03 -> 0xFFu // シリアル通信（未実装）
            addr in 0xFF04..0xFF07 -> {
                // タイマレジスタ（DIV/TIMA/TMA/TAC）
                val offset = addr - 0xFF04
                timer.readRegister(offset)
            }
            addr in 0xFF08..0xFF0E -> 0xFFu // 未使用領域
            addr == 0xFF0F -> interruptController.readIf()
            addr in 0xFF40..0xFF4B -> {
                // PPU I/O レジスタ（LCDC/STAT/SCY/SCX/LY/LYC/DMA/BGP/OBP0/OBP1/WY/WX）
                val offset = addr - 0xFF40
                ppu.readRegister(offset)
            }
            addr in 0xFF10..0xFF3F -> {
                // サウンドレジスタ（0xFF10-0xFF3F）
                val offset = addr - 0xFF10
                sound.readRegister(offset)
            }
            addr in 0xFF50..0xFF7F -> ioRegs[addr - 0xFF50] // その他I/O（未実装）
            addr in 0xFF80..0xFFFE -> hram[addr - 0xFF80]
            addr == 0xFFFF -> interruptController.readIe()
            else -> 0xFFu
        }

    override fun writeByte(
        address: UShort,
        value: UByte,
    ) {
        val addr = address.toInt()
        when (addr) {
            in 0x0000..0x7FFF -> {
                // MBC 制御レジスタ
                mbc1?.writeControl(addr, value)
            }
            in 0x8000..0x9FFF -> {
                val vramIndex = addr - 0x8000
                vram[vramIndex] = value
            }
            in 0xA000..0xBFFF -> {
                val ram = cartridgeRam ?: return
                val ramIndex =
                    mbc1
                        ?.mapRam(addr)
                        ?: (addr - 0xA000)
                if (ramIndex !in 0 until ram.size) {
                    return
                }
                ram[ramIndex] = value
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
                // DMA転送中は、OAMへの直接書き込みは無視される（実機の仕様）
                if (!ppu.dmaActive) {
                    oam[addr - 0xFE00] = value
                }
            }
            in 0xFEA0..0xFEFF -> {
                // 未使用領域: 書き込みは無視
            }
            0xFF00 -> {
                joypad.write(value)
            }
            in 0xFF04..0xFF07 -> {
                val offset = addr - 0xFF04
                timer.writeRegister(
                    offset = offset,
                    value = value,
                )
            }
            0xFF0F -> interruptController.writeIf(value)
            in 0xFF40..0xFF4B -> {
                // PPU I/O レジスタ（LCDC/STAT/SCY/SCX/LY/LYC/DMA/BGP/OBP0/OBP1/WY/WX）
                val offset = addr - 0xFF40
                ppu.writeRegister(offset, value)

                // DMA転送が開始された場合、即座に転送を実行（簡易実装）
                // 実機では160サイクルかかるが、ここでは即座に完了させる
                if (offset == 0x06 && ppu.dmaActive) {
                    performDmaTransfer(ppu.dmaSourceBase)
                }
            }
            in 0xFF10..0xFF3F -> {
                // サウンドレジスタ（0xFF10-0xFF3F）
                val offset = addr - 0xFF10
                sound.writeRegister(offset, value)
            }
            in 0xFF50..0xFF7F -> {
                ioRegs[addr - 0xFF50] = value // その他I/O（未実装）
            }
            in 0xFF80..0xFFFE -> {
                hram[addr - 0xFF80] = value
            }
            0xFFFF -> interruptController.writeIe(value)
        }
    }

    /**
     * OAM DMA転送を実行する。
     *
     * - 転送元: [sourceBase] から 160バイト
     * - 転送先: 0xFE00-0xFE9F (OAM)
     * - 実機では160サイクルかかるが、ここでは即座に完了させる
     */
    private fun performDmaTransfer(sourceBase: UShort) {
        val sourceBaseInt = sourceBase.toInt()
        for (i in 0 until 0xA0) {
            val sourceAddr = sourceBaseInt + i
            val byte = readByteInternal(sourceAddr)
            oam[i] = byte
        }
    }
}
