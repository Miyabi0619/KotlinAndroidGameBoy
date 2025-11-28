package gb.core.impl.cpu

/**
 * CPU がメモリにアクセスするためのインターフェース。
 *
 * 実際の実装では、この Bus を通じて ROM / RAM / I/O レジスタなどにアクセスする。
 */
interface Bus {
    fun readByte(address: UShort): UByte

    fun writeByte(
        address: UShort,
        value: UByte,
    )
}

/**
 * シンプルな 64KB メモリバス実装。
 *
 * - 0x0000 ～ 0xFFFF までを [memory] で表現するだけの実装
 * - ROM / RAM / I/O などの区別は行わない
 * - CPU や命令実装のテスト用に使用する
 */
class SimpleMemoryBus(
    private val memory: UByteArray,
) : Bus {
    override fun readByte(address: UShort): UByte {
        return memory[address.toInt()]
    }

    override fun writeByte(
        address: UShort,
        value: UByte,
    ) {
        memory[address.toInt()] = value
    }
}
