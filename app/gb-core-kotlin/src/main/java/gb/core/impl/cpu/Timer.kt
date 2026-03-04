package gb.core.impl.cpu

/**
 * Game Boy のタイマ／DIV レジスタの実装。
 *
 * - DIV  (0xFF04): 内部16bitカウンタの上位8bit（常にインクリメント）
 * - TIMA (0xFF05): タイマカウンタ（オーバーフローで割り込み）
 * - TMA  (0xFF06): TIMA リロード値
 * - TAC  (0xFF07): タイマ制御（有効・無効＋クロック選択）
 *
 * 実機ではDIVの内部16bitカウンタの特定ビットの立ち下がりエッジでTIMAを更新する。
 * TAC変更やDIVリセット時にもエッジ検出が発生するため、グリッチが起こりうる。
 */
class Timer(
    private val interruptController: InterruptController,
) {
    // DIVは内部16bitカウンタの上位8bitを公開する
    val div: UByte
        get() = ((internalCounter shr 8) and 0xFF).toUByte()

    var tima: UByte = 0u
        private set

    var tma: UByte = 0u
        private set

    var tac: UByte = 0u
        private set

    // DIV の内部16bitカウンタ（T-cycle単位でインクリメント）
    // 実機ではこのカウンタが常にインクリメントされ、上位8bitがDIVレジスタとなる
    private var internalCounter: Int = 0

    // TIMA オーバーフロー後の「遅延リロード」用カウンタ（T-cycle数）
    private var timaOverflowDelayCycles: Int = -1
    private var timaOverflowPending: Boolean = false

    /**
     * DIVの内部16bitカウンタを取得（APUのフレームシーケンサとの同期用）
     * 実機では、このカウンタのbit 12がフレームシーケンサのクロックソース
     */
    fun getDivInternalCounter(): Int = internalCounter

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
                // DIV に書き込むと内部16bitカウンタが 0 にクリアされる（値は無視）
                // クリア前にエッジ検出を行う（グリッチの再現）
                val oldBit = getTimaBit()
                internalCounter = 0
                val newBit = getTimaBit() // クリア後は必ず0
                // 1→0の立ち下がりエッジでTIMAインクリメント
                if (oldBit && !newBit) {
                    incrementTima()
                }
            }
            1 -> {
                // 実機では、オーバーフロー遅延中に TIMA へ書き込むとリロードがキャンセルされる
                tima = value
                timaOverflowPending = false
                timaOverflowDelayCycles = -1
            }
            2 -> {
                // TMA 書き込み。オーバーフロー遅延中に書き込まれた場合は、
                // リロード時に新しい値が反映される
                tma = value
            }
            3 -> {
                // 下位 3bit のみ有効
                val oldTac = tac
                tac = (value and 0x07u)

                // TAC変更時のエッジ検出（グリッチの再現）
                // 旧TAC設定でのビット状態と新TAC設定でのビット状態を比較
                val oldEnabled = (oldTac.toInt() and 0x04) != 0
                val oldBitPos = tacToBitPosition(oldTac.toInt() and 0x03)
                val oldBitValue = oldEnabled && ((internalCounter and (1 shl oldBitPos)) != 0)

                val newEnabled = (tac.toInt() and 0x04) != 0
                val newBitPos = tacToBitPosition(tac.toInt() and 0x03)
                val newBitValue = newEnabled && ((internalCounter and (1 shl newBitPos)) != 0)

                // 立ち下がりエッジ: 旧ビットが1で新ビットが0の場合
                if (oldBitValue && !newBitValue) {
                    incrementTima()
                }
            }
            else -> error("Invalid timer register offset: $offset")
        }
    }

    /**
     * CPU サイクル数を進め、DIV/TIMA を更新する。
     *
     * 実機ではDIVの内部16bitカウンタの特定ビットの立ち下がりエッジでTIMAを更新する。
     */
    fun step(cycles: Int) {
        for (i in 0 until cycles) {
            stepOneTCycle()
        }
    }

    /**
     * 1 T-cycle分の更新を行う。
     * DIVの内部カウンタをインクリメントし、エッジ検出でTIMAを更新する。
     */
    private fun stepOneTCycle() {
        // TIMA オーバーフロー後の「遅延リロード」を処理
        if (timaOverflowPending) {
            timaOverflowDelayCycles--
            if (timaOverflowDelayCycles <= 0) {
                timaOverflowPending = false
                timaOverflowDelayCycles = -1
                tima = tma
                interruptController.request(InterruptController.Type.TIMER)
            }
        }

        // TAC bit2 が 0 のときはTIMA更新なし（DIVカウンタは常に進む）
        val timaEnabled = (tac.toInt() and 0x04) != 0
        val oldBit = timaEnabled && getTimaBit()

        // 内部16bitカウンタをインクリメント（0xFFFFでラップアラウンド）
        internalCounter = (internalCounter + 1) and 0xFFFF

        val newBit = timaEnabled && getTimaBit()

        // 立ち下がりエッジ検出: 1→0 でTIMAインクリメント
        if (oldBit && !newBit) {
            incrementTima()
        }
    }

    /**
     * 現在のTAC設定に基づき、内部カウンタの監視ビットの状態を返す。
     */
    private fun getTimaBit(): Boolean {
        val bitPos = tacToBitPosition(tac.toInt() and 0x03)
        return (internalCounter and (1 shl bitPos)) != 0
    }

    /**
     * TIMAをインクリメントし、オーバーフロー時に遅延リロードを開始する。
     */
    private fun incrementTima() {
        if (tima == 0xFFu.toUByte()) {
            tima = 0u
            if (!timaOverflowPending) {
                timaOverflowPending = true
                timaOverflowDelayCycles = 4
            }
        } else {
            tima = (tima + 1u).toUByte()
        }
    }

    companion object {
        /**
         * TACクロック選択値からDIV内部カウンタの監視ビット位置を返す。
         *
         * TAC clock select → DIVカウンタのビット位置:
         * - 00: bit 9  (1024 T-cycle周期 = CPU/1024)
         * - 01: bit 3  (16 T-cycle周期 = CPU/16)
         * - 10: bit 5  (64 T-cycle周期 = CPU/64)
         * - 11: bit 7  (256 T-cycle周期 = CPU/256)
         */
        private fun tacToBitPosition(clockSelect: Int): Int =
            when (clockSelect) {
                0 -> 9
                1 -> 3
                2 -> 5
                3 -> 7
                else -> 9
            }
    }
}
