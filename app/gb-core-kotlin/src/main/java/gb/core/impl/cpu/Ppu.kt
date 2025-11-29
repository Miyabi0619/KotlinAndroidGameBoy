package gb.core.impl.cpu

import kotlin.ExperimentalUnsignedTypes

/**
 * PPU（Picture Processing Unit）の最小スケルトン。
 *
 * - まだタイミングや LCDC/STAT などは実装せず、
 *   「VRAM を元に 160x144 のフレームバッファを生成する器」を先に用意する。
 */
@OptIn(ExperimentalUnsignedTypes::class)
class Ppu(
    @Suppress("UnusedPrivateProperty")
    private val vram: UByteArray,
) {
    companion object {
        const val SCREEN_WIDTH = 160
        const val SCREEN_HEIGHT = 144
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
        val black = 0xFF000000.toInt()
        for (i in pixels.indices) {
            pixels[i] = black
        }
        return pixels
    }
}
