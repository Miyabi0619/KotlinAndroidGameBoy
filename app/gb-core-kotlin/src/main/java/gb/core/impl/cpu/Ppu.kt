package gb.core.impl.cpu

import kotlin.ExperimentalUnsignedTypes

/**
 * PPU（Picture Processing Unit）の最小スケルトン。
 *
 * - まだタイミングや LCDC/STAT などは実装せず、
 *   「VRAM を元に 160x144 のフレームバッファを生成する器」を先に用意する。
 * - 現状は BG マップ 0（0x9800–0x9BFF）を元に、固定パレットで背景だけを描画する。
 */
@OptIn(ExperimentalUnsignedTypes::class)
class Ppu(
    @Suppress("UnusedPrivateProperty")
    private val vram: UByteArray,
) {
    companion object {
        const val SCREEN_WIDTH = 160
        const val SCREEN_HEIGHT = 144

        // VRAM 内オフセット（SystemBus 側では 0x8000 を 0 始まりにマップしている）
        private const val TILE_DATA_BASE = 0x0000 // 0x8000–0x97FF
        private const val BG_MAP0_BASE = 0x1800 // 0x9800–0x9BFF
        private const val BG_MAP_WIDTH = 32
        private const val TILE_SIZE_BYTES = 16 // 8x8, 2bytes/line * 8
    }

    /**
     * CPU サイクルに応じて PPU 内部状態を進める。
     *
     * - 現状は何もしないダミー実装。
     */
    fun step(cycles: Int) {
        // TODO(miyabi): LCDC/STAT, スキャンライン、モード遷移などを実装
        if (cycles <= 0) {
            return
        }
    }

    /**
     * 現在の VRAM 内容から 160x144 の ARGB フレームバッファを生成する。
     *
     * - 当面は「とりあえず画面が真っ黒 or 単色」でよいので、
     *   実際のタイル・背景スクロール処理は後続フェーズで実装する。
     */
    fun renderFrame(): IntArray {
        val pixels = IntArray(SCREEN_WIDTH * SCREEN_HEIGHT)

        for (y in 0 until SCREEN_HEIGHT) {
            val tileRow = y / 8
            val rowInTile = y % 8

            for (x in 0 until SCREEN_WIDTH) {
                val tileCol = x / 8
                val colInTile = x % 8

                // BG マップ 0 を使用（0x9800–0x9BFF）
                val bgIndex = BG_MAP0_BASE + tileRow * BG_MAP_WIDTH + tileCol
                val tileIndex = vram.getOrElse(bgIndex) { 0u }.toInt() and 0xFF

                // タイルデータは 0x8000 から、1 タイル 16 バイト
                val tileBase = TILE_DATA_BASE + tileIndex * TILE_SIZE_BYTES
                val lineAddr = tileBase + rowInTile * 2

                val low = vram.getOrElse(lineAddr) { 0u }.toInt()
                val high = vram.getOrElse(lineAddr + 1) { 0u }.toInt()

                val bit = 7 - colInTile
                val colorId =
                    (((high shr bit) and 0x1) shl 1) or
                        ((low shr bit) and 0x1)

                pixels[y * SCREEN_WIDTH + x] = mapColorIdToArgb(colorId)
            }
        }

        return pixels
    }

    private fun mapColorIdToArgb(colorId: Int): Int =
        when (colorId) {
            0 -> 0xFFFFFFFF.toInt() // 白
            1 -> 0xFFAAAAAA.toInt() // 薄いグレー
            2 -> 0xFF555555.toInt() // 濃いグレー
            else -> 0xFF000000.toInt() // 黒
        }
}
