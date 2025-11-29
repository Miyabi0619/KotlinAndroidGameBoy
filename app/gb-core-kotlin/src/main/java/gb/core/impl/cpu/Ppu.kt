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
    private val interruptController: InterruptController,
) {
    companion object {
        const val SCREEN_WIDTH = 160
        const val SCREEN_HEIGHT = 144

        // VRAM 内オフセット（SystemBus 側では 0x8000 を 0 始まりにマップしている）
        private const val TILE_DATA_BASE = 0x0000 // 0x8000–0x97FF
        private const val BG_MAP0_BASE = 0x1800 // 0x9800–0x9BFF
        private const val BG_MAP_WIDTH = 32
        private const val TILE_SIZE_BYTES = 16 // 8x8, 2bytes/line * 8

        // PPU タイミング定数
        const val CYCLES_PER_SCANLINE = 456 // 1スキャンライン = 456 CPUサイクル
        const val TOTAL_SCANLINES = 154 // 144行の描画 + 10行のVBlank = 154行（0-153）
    }

    // PPU I/O レジスタ（最小限の実装）
    private var lcdc: UByte = 0x91u // LCD Control (0xFF40) - デフォルト値
    private var stat: UByte = 0x85u // LCD Status (0xFF41) - デフォルト値
    private var scy: UByte = 0x00u // Scroll Y (0xFF42)
    private var scx: UByte = 0x00u // Scroll X (0xFF43)
    private var ly: UByte = 0x00u // Current Scanline (0xFF44) - 読み取り専用
    private var lyc: UByte = 0x00u // Line Y Compare (0xFF45)
    private var dma: UByte = 0x00u // DMA Transfer (0xFF46) - 書き込み専用
    private var bgp: UByte = 0xFCu // BG Palette (0xFF47) - デフォルト値
    private var obp0: UByte = 0xFFu // OBJ Palette 0 (0xFF48) - デフォルト値
    private var obp1: UByte = 0xFFu // OBJ Palette 1 (0xFF49) - デフォルト値
    private var wy: UByte = 0x00u // Window Y (0xFF4A)
    private var wx: UByte = 0x00u // Window X (0xFF4B)

    // PPU 内部状態
    private var scanlineCycles: Int = 0 // 現在のスキャンライン内の累積サイクル数
    private var previousLy: UByte = 0x00u // 前回のLY値（VBlank割り込み検出用）

    /**
     * PPU I/O レジスタの読み取り。
     *
     * @param offset 0xFF40 からのオフセット（0-11）
     */
    fun readRegister(offset: Int): UByte =
        when (offset) {
            0x00 -> lcdc // 0xFF40
            0x01 -> stat // 0xFF41
            0x02 -> scy // 0xFF42
            0x03 -> scx // 0xFF43
            0x04 -> ly // 0xFF44 (読み取り専用)
            0x05 -> lyc // 0xFF45
            0x06 -> dma // 0xFF46 (読み取りは意味がないが、値を返す)
            0x07 -> bgp // 0xFF47
            0x08 -> obp0 // 0xFF48
            0x09 -> obp1 // 0xFF49
            0x0A -> wy // 0xFF4A
            0x0B -> wx // 0xFF4B
            else -> 0xFFu
        }

    /**
     * PPU I/O レジスタの書き込み。
     *
     * @param offset 0xFF40 からのオフセット（0-11）
     * @param value 書き込む値
     */
    fun writeRegister(
        offset: Int,
        value: UByte,
    ) {
        when (offset) {
            0x00 -> lcdc = value // 0xFF40
            0x01 -> stat = value and 0xF8u // 0xFF41 (下位3bitは読み取り専用)
            0x02 -> scy = value // 0xFF42
            0x03 -> scx = value // 0xFF43
            0x04 -> {
                // 0xFF44 (LY) は読み取り専用、書き込みは無視
            }
            0x05 -> lyc = value // 0xFF45
            0x06 -> {
                // 0xFF46 (DMA) - OAM DMA転送を開始（未実装）
                dma = value
                // TODO(miyabi): DMA転送を実装
            }
            0x07 -> bgp = value // 0xFF47
            0x08 -> obp0 = value // 0xFF48
            0x09 -> obp1 = value // 0xFF49
            0x0A -> wy = value // 0xFF4A
            0x0B -> wx = value // 0xFF4B
        }
    }

    /**
     * CPU サイクルに応じて PPU 内部状態を進める。
     *
     * - スキャンライン（LYレジスタ）を更新する。
     * - 1スキャンライン = 456 CPUサイクル
     * - 144行の描画（0-143）+ 10行のVBlank（144-153）= 154行（0-153）
     */
    fun step(cycles: Int) {
        if (cycles <= 0) {
            return
        }

        scanlineCycles += cycles

        // 456サイクルごとにスキャンラインを進める
        while (scanlineCycles >= CYCLES_PER_SCANLINE) {
            scanlineCycles -= CYCLES_PER_SCANLINE
            previousLy = ly
            ly = ((ly.toInt() + 1) % TOTAL_SCANLINES).toUByte()

            // VBlank割り込み: LYが144（VBlank開始）になったときに発生
            if (previousLy == 143u.toUByte() && ly == 144u.toUByte()) {
                interruptController.request(InterruptController.Type.VBLANK)
            }
        }
    }

    /**
     * 現在の VRAM 内容から 160x144 の ARGB フレームバッファを生成する。
     *
     * - デバッグモード: テスト描画を有効にする場合は [debugMode] を true にする
     * - 通常モード: VRAM からタイルデータを読み取って描画
     */
    fun renderFrame(debugMode: Boolean = false): IntArray {
        val pixels = IntArray(SCREEN_WIDTH * SCREEN_HEIGHT)

        if (debugMode) {
            // テスト描画: チェッカーボードパターンで PPU が正しく動作しているか確認
            return renderTestPattern()
        }

        // VRAM の内容を確認（デバッグ用）
        val bgMapNonZero = (BG_MAP0_BASE until BG_MAP0_BASE + 0x400).count { vram.getOrElse(it) { 0u.toUByte() } != 0u.toUByte() }
        val tileDataNonZero = (TILE_DATA_BASE until TILE_DATA_BASE + 0x1800).count { vram.getOrElse(it) { 0u.toUByte() } != 0u.toUByte() }

        if (bgMapNonZero == 0 && tileDataNonZero == 0) {
            // VRAM がすべて 0 の場合は、テストパターンを描画して CPU が VRAM に書き込んでいないことを示す
            android.util.Log.w("PPU", "VRAM is empty (BG map: $bgMapNonZero non-zero, Tile data: $tileDataNonZero non-zero)")
            return renderTestPattern()
        }

        for (y in 0 until SCREEN_HEIGHT) {
            // スクロールYを考慮した背景Y座標
            val bgY = (y + scy.toInt()) and 0xFF
            val tileRow = bgY / 8
            val rowInTile = bgY % 8

            for (x in 0 until SCREEN_WIDTH) {
                // スクロールXを考慮した背景X座標
                val bgX = (x + scx.toInt()) and 0xFF
                val tileCol = bgX / 8
                val colInTile = bgX % 8

                // BG マップ 0 を使用（0x9800–0x9BFF）
                // 32x32タイルマップなので、モジュロ演算でラップアラウンド
                val bgIndex = BG_MAP0_BASE + (tileRow % 32) * BG_MAP_WIDTH + (tileCol % 32)
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

                pixels[y * SCREEN_WIDTH + x] = mapColorIdToArgb(colorId, bgp)
            }
        }

        return pixels
    }

    /**
     * テスト描画: チェッカーボードパターンを描画して PPU が正しく動作しているか確認
     */
    private fun renderTestPattern(): IntArray {
        val pixels = IntArray(SCREEN_WIDTH * SCREEN_HEIGHT)
        for (y in 0 until SCREEN_HEIGHT) {
            for (x in 0 until SCREEN_WIDTH) {
                // 8x8 のチェッカーボードパターン
                val tileX = x / 8
                val tileY = y / 8
                val color =
                    if ((tileX + tileY) % 2 == 0) {
                        0xFF000000.toInt() // 黒
                    } else {
                        0xFFFFFFFF.toInt() // 白
                    }
                pixels[y * SCREEN_WIDTH + x] = color
            }
        }
        return pixels
    }

    /**
     * カラーIDをARGBに変換する。
     *
     * @param colorId カラーID (0-3)
     * @param bgp BGパレットレジスタ (0xFF47)
     * @return ARGB値
     */
    private fun mapColorIdToArgb(
        colorId: Int,
        bgp: UByte,
    ): Int {
        // BGPレジスタからカラーIDに対応するパレットエントリを取得
        // BGPのビット構成: [color3][color2][color1][color0] (各2bit)
        val paletteEntry = (bgp.toInt() shr (colorId * 2)) and 0x03

        // Game Boyの4階調グレースケールパレット
        return when (paletteEntry) {
            0 -> 0xFFFFFFFF.toInt() // 白
            1 -> 0xFFAAAAAA.toInt() // 薄いグレー
            2 -> 0xFF555555.toInt() // 濃いグレー
            else -> 0xFF000000.toInt() // 黒
        }
    }
}
