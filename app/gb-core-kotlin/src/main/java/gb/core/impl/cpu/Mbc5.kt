package gb.core.impl.cpu

/**
 * MBC5（Memory Bank Controller 5）の実装。
 *
 * MBC5 は最大 64 MBit (8MB) の ROM と 1 MBit (128KB) の RAM に対応する。
 * 多くのホームブリューおよびカラー専用ゲームで使用される。
 *
 * レジスタマップ:
 * - 0x0000-0x1FFF: RAM 有効化（0x0A で有効）
 * - 0x2000-0x2FFF: ROM バンク番号 下位 8 ビット
 * - 0x3000-0x3FFF: ROM バンク番号 bit 8（9ビット合計、0-511）
 * - 0x4000-0x5FFF: RAM バンク番号（0-0x0F）
 *
 * MBC1 との主な違い:
 * - ROM バンク 0 は常に 0x0000-0x3FFF に固定（強制マッピングなし）
 * - バンク番号 0 のマッピングは 0 のまま（MBC1 のように 1 に読み替えない）
 * - ROM バンクは 9 ビット幅
 */
class Mbc5(
    private val romSize: Int,
    private val ramSize: Int,
) {
    private var ramEnabled: Boolean = false
    private var romBankLow8: Int = 1 // ROM バンク番号 下位 8 ビット（デフォルト 1）
    private var romBankHigh1: Int = 0 // ROM バンク番号 bit 8
    private var ramBank: Int = 0 // RAM バンク番号（0-0x0F）

    /** 0x000-0x7FFF への書き込みで MBC レジスタを更新する。 */
    fun writeControl(
        address: Int,
        value: UByte,
    ) {
        when (address) {
            in 0x0000..0x1FFF -> {
                ramEnabled = (value.toInt() and 0x0F) == 0x0A
            }
            in 0x2000..0x2FFF -> {
                romBankLow8 = value.toInt() and 0xFF
            }
            in 0x3000..0x3FFF -> {
                romBankHigh1 = value.toInt() and 0x01
            }
            in 0x4000..0x5FFF -> {
                ramBank = value.toInt() and 0x0F
            }
        }
    }

    /** 0x0000-0x3FFF を ROM バンク 0 の物理インデックスに変換する（常にバンク 0）。 */
    fun mapRom0(address: Int): Int {
        return (address % romSize).coerceAtLeast(0)
    }

    /** 0x4000-0x7FFF をスイッチャブル ROM バンクの物理インデックスに変換する。 */
    fun mapRomX(address: Int): Int {
        val bankNumber = (romBankHigh1 shl 8) or romBankLow8
        val base = bankNumber * 0x4000
        val offset = address - 0x4000
        return ((base + offset) % romSize).coerceAtLeast(0)
    }

    /** 0xA000-0xBFFF をカートリッジ RAM 上のインデックスに変換する。 */
    fun mapRam(address: Int): Int? {
        if (!ramEnabled || ramSize == 0) return null
        val base = ramBank * 0x2000
        val offset = address - 0xA000
        val index = base + offset
        return if (index in 0 until ramSize) index else null
    }
}
