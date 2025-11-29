package gb.core.impl.cpu

/**
 * MBC1（Memory Bank Controller 1）の最小実装。
 *
 * - ROM バンク切り替え
 * - RAM 有効／無効
 * - ROM/RAM バンキングモード切り替え
 *
 * 実機には ROM サイズや RAM サイズに応じた制約があるが、
 * ここでは「Pokémon Red など典型的な MBC1 カートリッジ」をターゲットにした
 * シンプルな挙動だけを実装している。
 */
class Mbc1(
    private val romSize: Int,
    private val ramSize: Int,
) {
    private var ramEnabled: Boolean = false
    private var romBankLow5: Int = 1 // バンク番号下位 5 ビット（0 は 1 にマップ）
    private var bankHigh2: Int = 0 // ROM バンクの bit5-6 または RAM バンク
    private var bankingModeRam: Boolean = false // false: ROM モード, true: RAM モード

    /**
     * 0000–7FFF への書き込みで MBC レジスタを更新する。
     */
    fun writeControl(
        address: Int,
        value: UByte,
    ) {
        when (address) {
            in 0x0000..0x1FFF -> {
                // RAM Enable: 下位 4 ビットが 0x0A なら有効
                ramEnabled = (value.toInt() and 0x0F) == 0x0A
            }
            in 0x2000..0x3FFF -> {
                // ROM bank number (lower 5 bits)
                romBankLow5 = value.toInt() and 0x1F
                if (romBankLow5 == 0) {
                    romBankLow5 = 1
                }
            }
            in 0x4000..0x5FFF -> {
                // RAM bank number or ROM bank high bits (2 bits)
                bankHigh2 = value.toInt() and 0x03
            }
            in 0x6000..0x7FFF -> {
                // Banking mode select
                bankingModeRam = (value.toInt() and 0x01) == 0x01
            }
        }
    }

    /**
     * CPU が見る 0000–3FFF のアドレスを ROM 先頭〜の物理インデックスに変換する。
     *
     * - ROM モード: 常にバンク 0
     * - RAM モード: bankHigh2 を上位ビットとして持つ「大きな」バンク 0
     */
    fun mapRom0(address: Int): Int {
        val bank =
            if (bankingModeRam) {
                (bankHigh2 shl 5)
            } else {
                0
            }
        val base = bank * 0x4000
        return ((base + address) % romSize).coerceAtLeast(0)
    }

    /**
     * CPU が見る 4000–7FFF のアドレスを ROM 先頭〜の物理インデックスに変換する。
     *
     * - ROM/RAM モード共通で「選択中のスイッチバンク」を返す。
     */
    fun mapRomX(address: Int): Int {
        val bankNumber =
            if (bankingModeRam) {
                // RAM モード: 上位 2 ビットは別用途（RAM バンク）なので ROM バンクは下位 5 ビットのみ
                romBankLow5
            } else {
                // ROM モード: 上位 2 ビットも ROM バンクに使う
                (bankHigh2 shl 5) or romBankLow5
            }

        val effectiveBank =
            when (bankNumber) {
                0 -> 1 // 0 は 1 にマップ
                0x20, 0x40, 0x60 -> bankNumber + 1 // 無効バンクをスキップ
                else -> bankNumber
            }

        val base = effectiveBank * 0x4000
        val offset = address - 0x4000
        return ((base + offset) % romSize).coerceAtLeast(0)
    }

    /**
     * A000–BFFF のアドレスをカートリッジ RAM 上のインデックスに変換する。
     *
     * - RAM が無効なら null
     * - RAM モード時のみ bankHigh2 を RAM バンク番号として使用（最大 4 バンク）。
     */
    fun mapRam(address: Int): Int? {
        if (!ramEnabled || ramSize == 0) return null

        val bank =
            if (bankingModeRam) {
                bankHigh2 and 0x03
            } else {
                0
            }

        val bankSize = 0x2000
        val base = bank * bankSize
        val offset = address - 0xA000
        val index = base + offset
        return if (index in 0 until ramSize) index else null
    }
}
