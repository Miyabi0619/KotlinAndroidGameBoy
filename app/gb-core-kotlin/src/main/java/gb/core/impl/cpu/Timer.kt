package gb.core.impl.cpu

/**
 * Game Boy のタイマ／DIV レジスタの実装。
 *
 * - DIV  (0xFF04): 内部クロック分周カウンタ（常にインクリメント）
 * - TIMA (0xFF05): タイマカウンタ（オーバーフローで割り込み）
 * - TMA  (0xFF06): TIMA リロード値
 * - TAC  (0xFF07): タイマ制御（有効・無効＋クロック選択）
 *
 * CPU サイクル数を `step(cycles)` で受け取り、内部カウンタから
 * DIV/TIMA を更新する。TIMA オーバーフロー時には割り込みを発生させる。
 */
class Timer(
    private val interruptController: InterruptController,
) {
    var div: UByte = 0u
        private set

    var tima: UByte = 0u
        private set

    var tma: UByte = 0u
        private set

    var tac: UByte = 0u
        private set

    // DIV 用の内部カウンタ（CPU サイクル数）
    private var divCounter: Int = 0

    // TIMA 用の内部カウンタ（CPU サイクル数）
    private var timaCounter: Int = 0

    fun readRegister(offset: Int): UByte =
        when (offset) {
            0 -> div
            1 -> tima
            2 -> tma
            3 -> tac
            else -> error("Invalid timer register offset: $offset")
        }

    fun writeRegister(
        offset: Int,
        value: UByte,
    ) {
        when (offset) {
            0 -> {
                // DIV に書き込むと 0 にクリアされる（値は無視）
                div = 0u
                divCounter = 0
            }
            1 -> tima = value
            2 -> tma = value
            3 -> {
                // 下位 3bit のみ有効
                tac = (value and 0x07u)
            }
            else -> error("Invalid timer register offset: $offset")
        }
    }

    /**
     * CPU サイクル数を進め、DIV/TIMA を更新する。
     *
     * - DIV は常に 16384Hz（CPU クロック / 256）でインクリメント。
     * - TIMA は TAC の設定に応じた周波数でインクリメントし、オーバーフロー時に
     *   TIMA ← TMA、かつ Timer 割り込みを要求する。
     */
    fun step(cycles: Int) {
        // DIV の更新（256 サイクルごとに 1 インクリメント）
        divCounter += cycles
        while (divCounter >= 256) {
            divCounter -= 256
            div = (div + 1u).toUByte()
        }

        // TAC bit2 が 1 のときだけ TIMA 有効
        if ((tac.toInt() and 0b100) == 0) {
            return
        }

        val period =
            when (tac.toInt() and 0b11) {
                0 -> 1024 // CPU クロック / 1024
                1 -> 16 // /16
                2 -> 64 // /64
                3 -> 256 // /256
                else -> error("Invalid TAC value")
            }

        timaCounter += cycles
        while (timaCounter >= period) {
            timaCounter -= period
            if (tima == 0xFFu.toUByte()) {
                tima = tma
                interruptController.request(InterruptController.Type.TIMER)
            } else {
                tima = (tima + 1u).toUByte()
            }
        }
    }
}
