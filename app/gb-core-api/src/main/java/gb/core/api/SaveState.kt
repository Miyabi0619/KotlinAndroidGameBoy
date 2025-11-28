package gb.core.api

/**
 * エミュレータコアのセーブステート。
 *
 * 実装側は CPU レジスタ・メモリ内容・PPU 状態など、復元に必要な情報を
 * 任意のバイナリ形式で [data] にシリアライズする。
 */
@JvmInline
value class SaveState(
    val data: ByteArray,
)
