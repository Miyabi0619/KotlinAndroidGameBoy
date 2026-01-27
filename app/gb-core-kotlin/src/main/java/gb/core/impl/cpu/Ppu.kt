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
    private val oam: UByteArray,
    private val interruptController: InterruptController,
) {
    companion object {
        const val SCREEN_WIDTH = 160
        const val SCREEN_HEIGHT = 144

        // VRAM 内オフセット（SystemBus 側では 0x8000 を 0 始まりにマップしている）
        private const val TILE_DATA_BASE = 0x0000 // 0x8000–0x97FF
        private const val BG_MAP0_BASE = 0x1800 // 0x9800–0x9BFF
        private const val BG_MAP1_BASE = 0x1C00 // 0x9C00–0x9FFF
        private const val BG_MAP_WIDTH = 32
        private const val TILE_SIZE_BYTES = 16 // 8x8, 2bytes/line * 8

        // PPU タイミング定数
        const val CYCLES_PER_SCANLINE = 456 // 1スキャンライン = 456 CPUサイクル
        const val TOTAL_SCANLINES = 154 // 144行の描画 + 10行のVBlank = 154行（0-153）

        // PPUモードのタイミング定数
        const val CYCLES_MODE_2 = 80 // OAM Search
        const val CYCLES_MODE_3_MIN = 172 // Pixel Transfer（最小）
        const val CYCLES_MODE_3_MAX = 289 // Pixel Transfer（最大）
    }

    /**
     * PPUモード（STATレジスタのbit 0-1）
     */
    private enum class PpuMode(
        val value: Int,
    ) {
        HBLANK(0), // Mode 0: 水平ブランク期間
        VBLANK(1), // Mode 1: 垂直ブランク期間
        OAM_SEARCH(2), // Mode 2: OAM検索期間
        PIXEL_TRANSFER(3), // Mode 3: ピクセル転送期間
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
    private var currentMode: PpuMode = PpuMode.OAM_SEARCH // 現在のPPUモード（初期状態はMode 2）
    private var modeCycles: Int = 0 // 現在のモード内の累積サイクル数

    // DMA転送状態（SystemBusからアクセス可能にするため、publicにする）
    var dmaActive: Boolean = false
        private set
    var dmaCyclesRemaining: Int = 0
        private set
    var dmaSourceBase: UShort = 0u
        private set

    /**
     * 現在のモードにおいて、CPU から VRAM へアクセス可能かどうか。
     *
     * - 実機では Mode 3（PIXEL_TRANSFER）中は VRAM 読み書き不可。
     */
    fun isVramAccessible(): Boolean = currentMode != PpuMode.PIXEL_TRANSFER

    /**
     * 現在のモードにおいて、CPU から OAM へアクセス可能かどうか。
     *
     * - 実機では Mode 2（OAM_SEARCH）および Mode 3 中は OAM 読み書き不可。
     */
    fun isOamAccessible(): Boolean =
        currentMode != PpuMode.OAM_SEARCH && currentMode != PpuMode.PIXEL_TRANSFER

    /**
     * PPU I/O レジスタの読み取り。
     *
     * @param offset 0xFF40 からのオフセット（0-11）
     */
    fun readRegister(offset: Int): UByte =
        when (offset) {
            0x00 -> {
                lcdc
            }

            // 0xFF40
            0x01 -> {
                // 0xFF41 (STAT): 現在のモードとLYC=LYフラグを反映
                val currentModeBits = currentMode.value.toUByte()
                val lycMatchBit = if (ly == lyc) 0x04u.toUByte() else 0x00u.toUByte()
                // 下位3bit（モード、LYC=LY）は読み取り専用、上位5bitは書き込み可能
                val statUpper = stat and 0xF8u.toUByte()
                val result = (statUpper.toInt() or currentModeBits.toInt() or lycMatchBit.toInt()) and 0xFF
                result.toUByte()
            }

            0x02 -> {
                scy
            }

            // 0xFF42
            0x03 -> {
                scx
            }

            // 0xFF43
            0x04 -> {
                ly
            }

            // 0xFF44 (読み取り専用)
            0x05 -> {
                lyc
            }

            // 0xFF45
            0x06 -> {
                dma
            }

            // 0xFF46 (読み取りは意味がないが、値を返す)
            0x07 -> {
                bgp
            }

            // 0xFF47
            0x08 -> {
                obp0
            }

            // 0xFF48
            0x09 -> {
                obp1
            }

            // 0xFF49
            0x0A -> {
                wy
            }

            // 0xFF4A
            0x0B -> {
                wx
            }

            // 0xFF4B
            else -> {
                0xFFu
            }
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
            0x00 -> {
                lcdc = value
            }

            // 0xFF40
            0x01 -> {
                stat = value and 0xF8u
            }

            // 0xFF41 (下位3bitは読み取り専用)
            0x02 -> {
                scy = value
            }

            // 0xFF42
            0x03 -> {
                scx = value
            }

            // 0xFF43
            0x04 -> {
                // 0xFF44 (LY) は読み取り専用、書き込みは無視
            }

            0x05 -> {
                lyc = value
            }

            // 0xFF45
            0x06 -> {
                // 0xFF46 (DMA) - OAM DMA転送を開始
                // 値は転送元アドレスの上位バイト（例: 0xC0 → 0xC000）
                dma = value
                dmaSourceBase = (value.toUShort().toInt() shl 8).toUShort()
                dmaActive = true
                dmaCyclesRemaining = 160 // 160サイクルで転送完了
            }

            0x07 -> {
                bgp = value
            }

            // 0xFF47
            0x08 -> {
                obp0 = value
            }

            // 0xFF48
            0x09 -> {
                obp1 = value
            }

            // 0xFF49
            0x0A -> {
                wy = value
            }

            // 0xFF4A
            0x0B -> {
                wx = value
            } // 0xFF4B
        }
    }

    /**
     * CPU サイクルに応じて PPU 内部状態を進める。
     *
     * - スキャンライン（LYレジスタ）を更新する。
     * - PPUモード遷移を実装（Mode 0-3）
     * - LCD_STAT割り込みを実装
     * - LYC比較割り込みを実装
     * - 1スキャンライン = 456 CPUサイクル
     * - 144行の描画（0-143）+ 10行のVBlank（144-153）= 154行（0-153）
     */
    fun step(cycles: Int) {
        if (cycles <= 0) {
            return
        }

        // DMA転送の進行
        if (dmaActive) {
            dmaCyclesRemaining -= cycles
            if (dmaCyclesRemaining <= 0) {
                dmaActive = false
                dmaCyclesRemaining = 0
            }
        }

        // LCDが無効な場合は、PPUをリセット状態に保つ
        val lcdEnabled = (lcdc.toInt() and 0x80) != 0
        if (!lcdEnabled) {
            // 実機では LCD を OFF にすると LY は 0 にリセットされ、PPU は停止する
            currentMode = PpuMode.HBLANK
            modeCycles = 0
            scanlineCycles = 0
            previousLy = 0u
            ly = 0u
            return
        }

        var remainingCycles = cycles
        while (remainingCycles > 0) {
            // スキャンラインが終了している場合は、次のスキャンラインに進む
            if (scanlineCycles >= CYCLES_PER_SCANLINE) {
                scanlineCycles = 0
                modeCycles = 0
                previousLy = ly
                ly = ((ly.toInt() + 1) % TOTAL_SCANLINES).toUByte()

                // LYC比較のチェック
                checkLycMatch()

                if (ly.toInt() < SCREEN_HEIGHT) {
                    // 次のスキャンライン開始（Mode 2）
                    setMode(PpuMode.OAM_SEARCH)
                } else if (ly == 144u.toUByte()) {
                    // VBlank開始（Mode 1）
                    setMode(PpuMode.VBLANK)
                    interruptController.request(InterruptController.Type.VBLANK)
                    checkStatInterrupt(PpuMode.VBLANK)
                    checkLycMatch()
                } else if (ly == 0u.toUByte()) {
                    // VBlank終了、次のフレーム開始（Mode 2）
                    setMode(PpuMode.OAM_SEARCH)
                }
            }

            val cyclesToProcess = minOf(remainingCycles, CYCLES_PER_SCANLINE - scanlineCycles)
            if (cyclesToProcess <= 0) {
                // サイクルが処理できない場合は終了（安全装置）
                break
            }

            remainingCycles -= cyclesToProcess
            scanlineCycles += cyclesToProcess
            modeCycles += cyclesToProcess

            // モード遷移の処理
            when (currentMode) {
                PpuMode.OAM_SEARCH -> {
                    // Mode 2: OAM Search（80サイクル）
                    if (modeCycles >= CYCLES_MODE_2) {
                        setMode(PpuMode.PIXEL_TRANSFER)
                    }
                }

                PpuMode.PIXEL_TRANSFER -> {
                    // Mode 3: Pixel Transfer（172-289サイクル、簡易実装として200サイクル）
                    if (modeCycles >= 200) {
                        setMode(PpuMode.HBLANK)
                        // HBlank割り込みのチェック
                        checkStatInterrupt(PpuMode.HBLANK)
                    }
                }

                PpuMode.HBLANK -> {
                    // Mode 0: HBlank（残りのサイクル）
                    // スキャンライン終了処理はループの先頭で行う
                }

                PpuMode.VBLANK -> {
                    // Mode 1: VBlank（10スキャンライン）
                    // スキャンライン終了処理はループの先頭で行う
                }
            }
        }
    }

    /**
     * PPUモードを設定し、必要に応じて割り込みを発生させる。
     */
    private fun setMode(newMode: PpuMode) {
        if (currentMode != newMode) {
            currentMode = newMode
            modeCycles = 0

            // Mode 2（OAM Search）割り込みのチェック
            if (newMode == PpuMode.OAM_SEARCH) {
                checkStatInterrupt(PpuMode.OAM_SEARCH)
            }
        }
    }

    /**
     * STAT割り込みをチェックし、条件が満たされていれば発生させる。
     */
    private fun checkStatInterrupt(mode: PpuMode) {
        val modeBit =
            when (mode) {
                PpuMode.HBLANK -> 3
                PpuMode.VBLANK -> 4
                PpuMode.OAM_SEARCH -> 5
                PpuMode.PIXEL_TRANSFER -> return // Mode 3では割り込みは発生しない
            }

        val interruptEnabled = (stat.toInt() and (1 shl modeBit)) != 0
        if (interruptEnabled) {
            interruptController.request(InterruptController.Type.LCD_STAT)
        }
    }

    /**
     * LYC=LY比較をチェックし、条件が満たされていれば割り込みを発生させる。
     */
    private fun checkLycMatch() {
        if (ly == lyc) {
            // LYC=LY割り込みのチェック（STAT bit 6）
            val interruptEnabled = (stat.toInt() and 0x40) != 0
            if (interruptEnabled) {
                interruptController.request(InterruptController.Type.LCD_STAT)
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

        // LCDC.7 (LCD Enable) が 0 の場合は、画面を白で塗りつぶす
        val lcdEnabled = (lcdc.toInt() and 0x80) != 0
        if (!lcdEnabled) {
            // LCD が無効な場合は、すべて白で塗りつぶす
            pixels.fill(0xFFFFFFFF.toInt())
            return pixels
        }

        // VRAM の内容確認を削除（パフォーマンス向上のため）
        // デバッグが必要な場合は、条件付きコンパイルやフラグで制御する

        // 背景/ウィンドウのカラーIDを保持する配列（スプライトの優先度処理用）
        val bgColorIds = UByteArray(SCREEN_WIDTH * SCREEN_HEIGHT) { 0u }

        // 背景描画（カラーIDを保持）
        // レジスタ値を事前に計算（パフォーマンス向上）
        val lcdcInt = lcdc.toInt()
        val bgEnabled = (lcdcInt and 0x01) != 0
        val scyInt = scy.toInt()
        val scxInt = scx.toInt()
        val bgMapBase = if (((lcdcInt shr 3) and 0x1) == 0) BG_MAP0_BASE else BG_MAP1_BASE
        val tileDataMode = (lcdcInt shr 4) and 0x1

        // パレットマッピングを事前計算（4色分のARGB値を事前計算）
        val bgpInt = bgp.toInt()
        val paletteColors =
            IntArray(4) { colorId ->
                val paletteEntry = (bgpInt shr (colorId * 2)) and 0x03
                when (paletteEntry) {
                    0 -> 0xFFFFFFFF.toInt()

                    // 白
                    1 -> 0xFFAAAAAA.toInt()

                    // 薄いグレー
                    2 -> 0xFF555555.toInt()

                    // 濃いグレー
                    else -> 0xFF000000.toInt() // 黒
                }
            }

        // 最適化: 型変換と計算を削減（スコープ外で定義）
        val vramSize = vram.size
        val whiteColor = 0xFFFFFFFF.toInt()

        if (bgEnabled) {
            for (y in 0 until SCREEN_HEIGHT) {
                // スクロールYを考慮した背景Y座標
                val bgY = (y + scyInt) and 0xFF
                val tileRow = bgY shr 3 // / 8 をビットシフトに置き換え
                val rowInTile = bgY and 0x07 // % 8 をビット演算に置き換え
                val rowInTile2 = rowInTile shl 1 // rowInTile * 2

                for (x in 0 until SCREEN_WIDTH) {
                    // スクロールXを考慮した背景X座標
                    val bgX = (x + scxInt) and 0xFF
                    val tileCol = bgX shr 3 // / 8 をビットシフトに置き換え
                    val colInTile = bgX and 0x07 // % 8 をビット演算に置き換え

                    // 32x32タイルマップなので、モジュロ演算でラップアラウンド
                    val bgIndex = bgMapBase + (tileRow and 0x1F) * BG_MAP_WIDTH + (tileCol and 0x1F)
                    val tileIndexByte = if (bgIndex < vramSize) vram[bgIndex] else 0u

                    // タイルインデックスの計算
                    val tileIndex =
                        if (tileDataMode == 0) {
                            // 符号付き: 0x80-0xFF は -128〜-1 として扱う
                            val signed = tileIndexByte.toInt().toByte().toInt()
                            256 + signed // 0x8800 ベースに変換（0x8800 = 0x8000 + 0x800）
                        } else {
                            // 符号なし: 0x8000 ベース
                            tileIndexByte.toInt()
                        }

                    // タイルデータは 0x8000 から、1 タイル 16 バイト
                    val lineAddr = TILE_DATA_BASE + tileIndex * TILE_SIZE_BYTES + rowInTile2

                    // 範囲チェックを事前に行う（getOrElseのオーバーヘッドを削減）
                    val low = if (lineAddr < vramSize) vram[lineAddr].toInt() else 0
                    val high = if (lineAddr + 1 < vramSize) vram[lineAddr + 1].toInt() else 0

                    val bit = 7 - colInTile
                    val colorId =
                        (((high shr bit) and 0x1) shl 1) or
                            ((low shr bit) and 0x1)

                    val pixelIndex = y * SCREEN_WIDTH + x
                    // ルックアップテーブルを使用（関数呼び出しのオーバーヘッドを削減）
                    pixels[pixelIndex] = paletteColors[colorId]
                    bgColorIds[pixelIndex] = colorId.toUByte()
                }
            }
        } else {
            // 背景が無効な場合は、すべて白で塗りつぶし、カラーIDは0
            // 最適化: fillを使用して高速化
            pixels.fill(whiteColor)
            bgColorIds.fill(0u)
        }

        // ウィンドウ描画（背景の上、スプライトの下に描画）
        // LCDC.5 (Window Enable) が 1 の場合のみ描画
        val windowEnabled = (lcdcInt and 0x20) != 0
        if (windowEnabled) {
            renderWindow(pixels, bgColorIds, lcdcInt, paletteColors)
        }

        // スプライトの描画（LCDC.1が1の場合のみ）
        val spriteEnabled = (lcdcInt and 0x02) != 0
        if (spriteEnabled) {
            renderSprites(pixels, bgColorIds, obp0, obp1, lcdcInt)
        }

        return pixels
    }

    /**
     * ウィンドウを描画する。
     *
     * - ウィンドウは背景の上に描画される
     * - ウィンドウレジスタ（WX/WY）を使用
     *   - WX (0xFF4B): ウィンドウの左端のX座標（実際のX座標 = WX - 7）
     *   - WY (0xFF4A): ウィンドウの上端のY座標
     * - LCDC.5 (Window Enable) が 1 の場合のみ描画
     * - LCDC.6 (Window Tile Map) でウィンドウマップを選択
     * - ウィンドウ描画時もカラーIDを保持する
     */
    private fun renderWindow(
        pixels: IntArray,
        bgColorIds: UByteArray,
        lcdcInt: Int,
        paletteColors: IntArray,
    ) {
        val wyInt = wy.toInt()
        val wxInt = wx.toInt()

        // WYが144以上の場合、ウィンドウは表示されない（画面外）
        if (wyInt >= SCREEN_HEIGHT) return

        // WXが7未満の場合、ウィンドウは表示されない（実機の仕様）
        if (wxInt < 7) return

        // WXが166以上の場合、ウィンドウは表示されない（画面外）
        // 実際のX座標 = WX - 7 なので、WX >= 166 の場合は画面外
        if (wxInt >= 166) return

        // ウィンドウの左上座標（画面座標系）
        // WXレジスタの値から7を引いた値が実際のX座標
        val windowStartX = wxInt - 7
        val windowStartY = wyInt

        // LCDC.6 でウィンドウマップを選択
        // 0: ウィンドウマップ0（0x9800–0x9BFF）
        // 1: ウィンドウマップ1（0x9C00–0x9FFF）
        val windowMapBase = if (((lcdcInt shr 6) and 0x1) == 0) BG_MAP0_BASE else BG_MAP1_BASE
        val tileDataMode = (lcdcInt shr 4) and 0x1

        // ウィンドウ内の各ピクセルを描画
        // ウィンドウは画面の特定の領域にのみ描画される
        // 最適化: 型変換と計算を削減
        val vramSize = vram.size

        for (y in windowStartY until SCREEN_HEIGHT) {
            // ウィンドウ内のY座標（ウィンドウのタイルマップ内での位置）
            val windowY = y - windowStartY
            val tileRow = windowY shr 3 // / 8 をビットシフトに置き換え
            val rowInTile = windowY and 0x07 // % 8 をビット演算に置き換え
            val rowInTile2 = rowInTile shl 1 // rowInTile * 2

            for (x in windowStartX until SCREEN_WIDTH) {
                // ウィンドウ内のX座標（ウィンドウのタイルマップ内での位置）
                val windowX = x - windowStartX
                val tileCol = windowX shr 3 // / 8 をビットシフトに置き換え
                val colInTile = windowX and 0x07 // % 8 をビット演算に置き換え

                // 32x32タイルマップなので、モジュロ演算でラップアラウンド
                val windowIndex = windowMapBase + (tileRow and 0x1F) * BG_MAP_WIDTH + (tileCol and 0x1F)
                val tileIndexByte = if (windowIndex < vramSize) vram[windowIndex] else 0u

                // タイルインデックスの計算
                val tileIndex =
                    if (tileDataMode == 0) {
                        // 符号付き: 0x80-0xFF は -128〜-1 として扱う
                        val signed = tileIndexByte.toInt().toByte().toInt()
                        256 + signed // 0x8800 ベースに変換
                    } else {
                        // 符号なし: 0x8000 ベース
                        tileIndexByte.toInt()
                    }

                // タイルデータは 0x8000 から、1 タイル 16 バイト
                val lineAddr = TILE_DATA_BASE + tileIndex * TILE_SIZE_BYTES + rowInTile2

                // 範囲チェックを事前に行う（getOrElseのオーバーヘッドを削減）
                val low = if (lineAddr < vramSize) vram[lineAddr].toInt() else 0
                val high = if (lineAddr + 1 < vramSize) vram[lineAddr + 1].toInt() else 0

                val bit = 7 - colInTile
                val colorId =
                    (((high shr bit) and 0x1) shl 1) or
                        ((low shr bit) and 0x1)

                val pixelIndex = y * SCREEN_WIDTH + x
                // ルックアップテーブルを使用（関数呼び出しのオーバーヘッドを削減）
                pixels[pixelIndex] = paletteColors[colorId]
                bgColorIds[pixelIndex] = colorId.toUByte()
            }
        }
    }

    /**
     * スプライトを描画する。
     *
     * - OAM（0xFE00-0xFE9F）からスプライトデータを読み取る
     * - 各スプライトは4バイト：Y, X, タイルインデックス, 属性
     * - 最大10個のスプライトを描画（実機の制限）
     * - LCDC.2でスプライトサイズを決定（0=8x8、1=8x16）
     * - 優先度処理：優先度が高い（bit7=1）場合、背景/ウィンドウのカラーID 0以外の上には描画しない
     *
     * @param pixels ピクセル配列（ARGB形式）
     * @param bgColorIds 背景/ウィンドウのカラーID配列（優先度処理用）
     */
    private fun renderSprites(
        pixels: IntArray,
        bgColorIds: UByteArray,
        obp0: UByte,
        obp1: UByte,
        lcdcInt: Int,
    ) {
        // スプライトサイズを事前に計算
        val spriteSize = if (((lcdcInt shr 2) and 0x1) == 0) 8 else 16

        // パレットマッピングを事前計算（OBP0/OBP1用）
        val obp0Int = obp0.toInt()
        val obp1Int = obp1.toInt()
        val obp0Colors =
            IntArray(4) { colorId ->
                val paletteEntry = (obp0Int shr (colorId * 2)) and 0x03
                when (paletteEntry) {
                    0 -> 0xFFFFFFFF.toInt()

                    // 白（透明）
                    1 -> 0xFFAAAAAA.toInt()

                    // 薄いグレー
                    2 -> 0xFF555555.toInt()

                    // 濃いグレー
                    else -> 0xFF000000.toInt() // 黒
                }
            }
        val obp1Colors =
            IntArray(4) { colorId ->
                val paletteEntry = (obp1Int shr (colorId * 2)) and 0x03
                when (paletteEntry) {
                    0 -> 0xFFFFFFFF.toInt()

                    // 白（透明）
                    1 -> 0xFFAAAAAA.toInt()

                    // 薄いグレー
                    2 -> 0xFF555555.toInt()

                    // 濃いグレー
                    else -> 0xFF000000.toInt() // 黒
                }
            }

        // 各スキャンラインで、そのラインに表示されるスプライトを検索して描画
        // 最適化: 型変換と計算を削減
        val vramSize = vram.size
        val oamSize = oam.size

        for (y in 0 until SCREEN_HEIGHT) {
            val spritesOnLine = mutableListOf<Int>()

            // OAMからスプライトを検索（最大10個）
            for (i in 0 until 40) {
                if (spritesOnLine.size >= 10) break

                val oamIndex = i shl 2 // i * 4 をビットシフトに置き換え
                if (oamIndex + 1 >= oamSize) continue
                val spriteY = oam[oamIndex].toInt() - 16
                val spriteX = oam[oamIndex + 1].toInt() - 8

                // このスキャンラインに表示されるかチェック
                if (y >= spriteY && y < spriteY + spriteSize) {
                    spritesOnLine.add(i)
                }
            }

            // スプライトを描画（OAMの順序（インデックス昇順）で描画：実機の仕様に準拠）
            // 後から描画されたスプライトが前に描画されたスプライトの上に描画される
            for (i in spritesOnLine) {
                val oamIndex = i shl 2 // i * 4 をビットシフトに置き換え
                if (oamIndex + 3 >= oamSize) continue
                val spriteY = oam[oamIndex].toInt() - 16
                val spriteX = oam[oamIndex + 1].toInt() - 8
                val tileIndex = oam[oamIndex + 2].toInt()
                val attributes = oam[oamIndex + 3].toInt()

                val paletteColors = if ((attributes and 0x10) != 0) obp1Colors else obp0Colors
                val xFlip = (attributes and 0x20) != 0
                val yFlip = (attributes and 0x40) != 0
                val priority = (attributes and 0x80) != 0

                val rowInSprite = y - spriteY
                val actualRow = if (yFlip) spriteSize - 1 - rowInSprite else rowInSprite

                // スプライトサイズが16の場合、タイルインデックスは下位ビットを無視
                val actualTileIndex =
                    if (spriteSize == 16) {
                        (tileIndex and 0xFE) + (if (actualRow >= 8) 1 else 0)
                    } else {
                        tileIndex
                    }
                val tileRow = actualRow and 0x07 // % 8 をビット演算に置き換え

                val lineAddr = TILE_DATA_BASE + actualTileIndex * TILE_SIZE_BYTES + (tileRow shl 1) // tileRow * 2

                // 範囲チェックを事前に行う（getOrElseのオーバーヘッドを削減）
                val low = if (lineAddr < vramSize) vram[lineAddr].toInt() else 0
                val high = if (lineAddr + 1 < vramSize) vram[lineAddr + 1].toInt() else 0

                for (x in 0 until 8) {
                    val screenX = spriteX + x
                    if (screenX < 0 || screenX >= SCREEN_WIDTH) continue

                    val bit = if (xFlip) x else (7 - x)
                    val colorId =
                        (((high shr bit) and 0x1) shl 1) or
                            ((low shr bit) and 0x1)

                    // カラーID 0は透明（スプライトは描画しない）
                    if (colorId == 0) continue

                    val pixelIndex = y * SCREEN_WIDTH + screenX

                    // 優先度チェック：優先度が高い（bit7=1）場合、背景/ウィンドウのカラーID 0以外の上には描画しない
                    // 実機の仕様：優先度が高いスプライトは、背景/ウィンドウの透明部分（カラーID 0）の上にのみ描画される
                    if (priority && bgColorIds[pixelIndex] != 0u.toUByte()) {
                        continue
                    }

                    // ルックアップテーブルを使用（関数呼び出しのオーバーヘッドを削減）
                    pixels[pixelIndex] = paletteColors[colorId]
                }
            }
        }
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
            0 -> 0xFFFFFFFF.toInt()

            // 白
            1 -> 0xFFAAAAAA.toInt()

            // 薄いグレー
            2 -> 0xFF555555.toInt()

            // 濃いグレー
            else -> 0xFF000000.toInt() // 黒
        }
    }
}
