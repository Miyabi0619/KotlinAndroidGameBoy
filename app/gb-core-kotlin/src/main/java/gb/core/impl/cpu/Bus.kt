package gb.core.impl.cpu

/**
 * CPU がメモリにアクセスするためのインターフェース。
 *
 * 実際の実装では、この Bus を通じて ROM / RAM / I/O レジスタなどにアクセスする。
 * 現時点では CPU のテスト用にシンプルなスタブ実装から始める予定。
 */
interface Bus {
    fun readByte(address: UShort): UByte

    fun writeByte(
        address: UShort,
        value: UByte,
    )
}
