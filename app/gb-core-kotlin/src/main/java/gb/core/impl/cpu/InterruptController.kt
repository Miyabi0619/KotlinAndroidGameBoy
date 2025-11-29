package gb.core.impl.cpu

/**
 * Game Boy の割り込みコントローラ。
 *
 * - IF レジスタ (0xFF0F): 割り込み要求フラグ
 * - IE レジスタ (0xFFFF): 割り込み許可フラグ
 *
 * 割り込み種類ごとにビットを持ち、IME（割り込みマスタフラグ）と組み合わせて
 * 「どの割り込みをサービスするか」を決定する。
 */
class InterruptController {
    enum class Type(
        val bit: Int,
        val vector: UShort,
    ) {
        VBLANK(bit = 0, vector = 0x40u),
        LCD_STAT(bit = 1, vector = 0x48u),
        TIMER(bit = 2, vector = 0x50u),
        SERIAL(bit = 3, vector = 0x58u),
        JOYPAD(bit = 4, vector = 0x60u),
    }

    /** IF レジスタ (0xFF0F) - 割り込み要求フラグ。 */
    private var ifReg: UByte = 0u

    /** IE レジスタ (0xFFFF) - 割り込み許可フラグ。 */
    private var ieReg: UByte = 0u

    fun readIf(): UByte = ifReg

    fun writeIf(value: UByte) {
        // 上位 3bit は未使用なのでマスクしておく（将来のために下位 5bit のみ保持）
        ifReg = (value and 0x1Fu)
    }

    fun readIe(): UByte = ieReg

    fun writeIe(value: UByte) {
        ieReg = (value and 0x1Fu)
    }

    /**
     * 指定種類の割り込み要求をセットする（IF の該当ビットを 1 にする）。
     */
    fun request(type: Type) {
        val mask = (1 shl type.bit).toUByte()
        ifReg = ifReg or mask
    }

    /**
     * 現在ペンディングしている割り込みのうち、IME と IE/IF の組み合わせから
     * 実際にサービスすべきものを 1 つ返す。
     *
     * - IME が false の場合は null。
     * - 優先順位は VBLANK -> LCD_STAT -> TIMER -> SERIAL -> JOYPAD。
     * - サービスする割り込みが決まった場合、IF の該当ビットはクリアされる。
     */
    fun nextPending(imeEnabled: Boolean): Type? {
        val pending =
            if (imeEnabled) {
                ifReg and ieReg
            } else {
                0u
            }

        if (pending == 0u.toUByte()) {
            return null
        }

        val typesInPriority =
            listOf(
                Type.VBLANK,
                Type.LCD_STAT,
                Type.TIMER,
                Type.SERIAL,
                Type.JOYPAD,
            )

        var selected: Type? = null
        for (type in typesInPriority) {
            val mask = (1 shl type.bit).toUByte()
            if ((pending and mask) != 0u.toUByte()) {
                selected = type
                // 優先度順で最初に見つかったものを採用
                ifReg = ifReg and mask.inv()
                break
            }
        }

        return selected
    }
}
