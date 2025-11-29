package gb.core.impl.cpu

/**
 * Game Boy CPU 本体。
 *
 * - [registers]: レジスタとフラグの状態
 * - [bus]: メモリアクセス用インターフェース
 *
 * 現時点では NOP / LD A, n / INC A のみをサポートし、
 * それ以外のオペコードは例外を投げる。後続タスクで命令セットを段階的に拡張していく。
 */
class Cpu(
    private val bus: Bus,
) {
    /**
     * 1 命令あたりのサイクル数を表す定数群。
     * 今後命令が増えた場合もここに追加していく。
     */
    private object Cycles {
        const val NOP: Int = 4
        const val LD_A_N: Int = 8
        const val INC_A: Int = 4
    }

    val registers = Registers()

    /**
     * 現在の PC が指す 1 命令を実行し、その命令に要したサイクル数を返す。
     */
    fun executeInstruction(): Int {
        val pcBefore = registers.pc
        val opcode = bus.readByte(pcBefore).toInt()

        // 次の命令に備えて PC を 1 バイト分進める。
        registers.pc = (pcBefore.toInt() + 1).toUShort()

        return when (opcode) {
            0x00 -> executeNop()
            0x3E -> executeLdAN()
            0x3C -> executeIncA()
            else -> error("Unknown opcode: 0x${opcode.toString(16)} at PC=0x${pcBefore.toString(16)}")
        }
    }

    /**
     * NOP 命令: 何もしないで 4 サイクル消費する。
     */
    private fun executeNop(): Int = Cycles.NOP

    /**
     * LD A, n 命令: 即値 n を A レジスタにロードする。
     *
     * - オペコード: 0x3E
     * - フォーマット: [0x3E][n]
     * - 動作: A ← n, PC は合計 2 バイト進む, 8 サイクル
     */
    private fun executeLdAN(): Int {
        val pc = registers.pc
        val value = bus.readByte(pc)
        registers.a = value
        // 即値 1 バイト分 PC を進める
        registers.pc = (pc.toInt() + 1).toUShort()
        return Cycles.LD_A_N
    }

    /**
     * INC A 命令: A レジスタを 1 増加させる。
     *
     * - オペコード: 0x3C
     * - フラグ:
     *   - Z: 結果が 0 のとき 1、それ以外は 0
     *   - N: 必ず 0（加算なので）
     *   - H: 下位 4bit に桁上がりがあった場合 1（0x0F -> 0x10 など）
     *   - C: 変化しない
     * - サイクル数: 4
     */
    private fun executeIncA(): Int {
        val before = registers.a
        val result = (before + 1u).toUByte()

        registers.a = result

        // フラグ更新
        registers.flagZ = result == 0u.toUByte()
        registers.flagN = false
        registers.flagH = (before and 0x0Fu) == 0x0Fu.toUByte()
        // flagC は変更しない

        return Cycles.INC_A
    }
}
