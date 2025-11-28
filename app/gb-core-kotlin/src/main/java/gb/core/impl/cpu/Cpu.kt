package gb.core.impl.cpu

/**
 * Game Boy CPU 本体。
 *
 * - [registers]: レジスタとフラグの状態
 * - [bus]: メモリアクセス用インターフェース
 *
 * 現時点では NOP 命令 (`0x00`) のみをサポートし、それ以外のオペコードは例外を投げる。
 * 後続タスクで命令セットを段階的に拡張していく。
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
            else -> error("Unknown opcode: 0x${opcode.toString(16)} at PC=0x${pcBefore.toString(16)}")
        }
    }

    /**
     * NOP 命令: 何もしないで 4 サイクル消費する。
     */
    private fun executeNop(): Int = Cycles.NOP
}
