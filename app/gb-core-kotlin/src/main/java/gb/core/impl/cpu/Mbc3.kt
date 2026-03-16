package gb.core.impl.cpu

/**
 * MBC3（Memory Bank Controller 3）の実装。
 *
 * - ポケモン金銀など RTC 付きカートリッジをサポート
 * - ROM バンク切り替え（7bit、0 → 1 に補正）
 * - RAM バンク切り替え（0-3）または RTC レジスタアクセス（0x08-0x0C）
 * - RTC ラッチ（0x6000-0x7FFF）
 */
class Mbc3(
    private val romSize: Int,
    private val ramSize: Int,
) {
    private var ramTimerEnabled: Boolean = false
    private var romBankNumber: Int = 1 // 7bit (0 → 1)
    private var ramBankOrRtcSelect: Int = 0 // 0-3: RAMバンク、0x08-0x0C: RTCレジスタ

    // ラッチされた RTC 値（0=S, 1=M, 2=H, 3=DL, 4=DH）
    private val rtcLatched = IntArray(5) { 0 }

    // 書き込み可能な RTC 値
    private val rtcWritable = IntArray(5) { 0 }
    private var latchPrepared: Boolean = false

    fun writeControl(
        address: Int,
        value: UByte,
    ) {
        val v = value.toInt()
        when (address) {
            in 0x0000..0x1FFF -> {
                ramTimerEnabled = (v and 0x0F) == 0x0A
            }
            in 0x2000..0x3FFF -> {
                romBankNumber = (v and 0x7F).coerceAtLeast(1)
            }
            in 0x4000..0x5FFF -> {
                ramBankOrRtcSelect = v and 0x0F
            }
            in 0x6000..0x7FFF -> {
                // ラッチクロック: 0x00 の後に 0x01 で現在の RTC 値をラッチ
                when (v and 0x01) {
                    0 -> latchPrepared = true
                    1 ->
                        if (latchPrepared) {
                            rtcWritable.copyInto(rtcLatched)
                            latchPrepared = false
                        }
                }
            }
        }
    }

    fun mapRom0(address: Int): Int = address % romSize

    fun mapRomX(address: Int): Int {
        val base = romBankNumber * 0x4000
        val offset = address - 0x4000
        return ((base + offset) % romSize).coerceAtLeast(0)
    }

    /** RAM バンクアクセスの場合に RAM インデックスを返す。RTC の場合は null。 */
    fun mapRam(address: Int): Int? {
        if (!ramTimerEnabled || isRtcAccess()) return null
        if (ramSize == 0) return null
        val bank = ramBankOrRtcSelect and 0x03
        val index = bank * 0x2000 + (address - 0xA000)
        return if (index in 0 until ramSize) index else null
    }

    fun isRtcAccess(): Boolean = ramBankOrRtcSelect in 0x08..0x0C

    fun readRtc(): UByte {
        if (!ramTimerEnabled) return 0xFFu
        val reg = ramBankOrRtcSelect - 0x08
        return if (reg in 0..4) rtcLatched[reg].toUByte() else 0xFFu
    }

    fun writeRtc(value: UByte) {
        if (!ramTimerEnabled) return
        val reg = ramBankOrRtcSelect - 0x08
        if (reg in 0..4) {
            rtcWritable[reg] = value.toInt() and RTC_MASKS[reg]
        }
    }

    companion object {
        private val RTC_MASKS = intArrayOf(0x3F, 0x3F, 0x1F, 0xFF, 0xC1)
    }
}
