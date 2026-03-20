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
    private var mode3Duration: Int = 172 // 現在のスキャンラインのMode 3の長さ（可変）

    // STAT 割り込み信号線の前回状態（エッジ検出用）
    // 実機 GB では HBlank/OAM/VBlank/LYC=LY の各ソースを OR 合成した
    // 1 本の信号線の "立ち上がりエッジ" でのみ LCD_STAT 割り込みが発火する。
    private var statInterruptLine: Boolean = false

    // スキャンラインごとのレジスタスナップショット（ラスタースクロール・ラスターエフェクト対応）
    // 各スキャンラインのMode 2（OAM Search）開始時にキャプチャする。
    // -1 = 未設定（step() が呼ばれていない / 新フレーム開始後まだキャプチャされていない）を示すセンチネル値。
    // captureFrameInternal() では -1 のスキャンラインは現在のレジスタ値にフォールバックする。
    private val scanlineScx = IntArray(SCREEN_HEIGHT) { -1 }
    private val scanlineScy = IntArray(SCREEN_HEIGHT) { -1 }
    private val scanlineBgp = IntArray(SCREEN_HEIGHT) { -1 }   // BGパレット
    private val scanlineObp0 = IntArray(SCREEN_HEIGHT) { -1 }  // OBJ パレット0
    private val scanlineObp1 = IntArray(SCREEN_HEIGHT) { -1 }  // OBJ パレット1
    private val scanlineLcdc = IntArray(SCREEN_HEIGHT) { -1 }  // LCD制御レジスタ

    // ウィンドウ内部ラインカウンタ（実機仕様に準拠）
    // 実機ではウィンドウが実際に描画されたスキャンラインでのみインクリメントされる。
    // フレーム途中でウィンドウを無効→再有効にしてもカウンタは継続する。
    private var windowLineCounter: Int = 0
    // 各スキャンラインでのウィンドウ内部ラインカウンタ値（-1 = このスキャンラインにウィンドウなし）
    private val scanlineWindowLine = IntArray(SCREEN_HEIGHT) { -1 }

    // VBlank 開始時にキャプチャしたフレームバッファ（ちらつき防止）
    // VBlank 中にゲームが VRAM/OAM を書き換える前の「完成した1フレーム」を保持する。
    // renderFrame() はこのバッファのコピーを返すだけにする。
    private val frameBuffer = IntArray(SCREEN_WIDTH * SCREEN_HEIGHT) { 0xFFFFFFFF.toInt() }

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
    fun isOamAccessible(): Boolean = currentMode != PpuMode.OAM_SEARCH && currentMode != PpuMode.PIXEL_TRANSFER

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
                // LYC=LY フラグも updateStatInterruptLine() と同じラップアラウンド判定を使用
                val lycEffective = lyc.toInt().let { if (it >= TOTAL_SCANLINES) it % TOTAL_SCANLINES else it }
                val lycMatchBit = if (ly.toInt() == lycEffective) 0x04u.toUByte() else 0x00u.toUByte()
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
                // LYC 書き込み時に STAT 信号線を即時再評価する。
                // LYC が変わると LYC=LY 条件が成立/不成立に切り替わるため、
                // モード遷移を待たずに信号を更新しないと以下の問題が生じる:
                // ① 前のLYC=LY でHIGH→新LYCに書き換え→LY が新LYC に到達しても
                //    信号がすでにHIGHのためエッジ未検出→STAT が1フレーム遅れる
                // ② LYC=LY が成立した瞬間に STAT を即発火させる（一部ゲームの期待動作）
                updateStatInterruptLine()
            }

            // 0xFF45
            0x06 -> {
                // 0xFF46 (DMA) - OAM DMA転送を開始
                // 値は転送元アドレスの上位バイト（例: 0xC0 → 0xC000）
                dma = value
                dmaSourceBase = (value.toUShort().toInt() shl 8).toUShort()
                dmaActive = true
                // 実機では160バイト × 4 T-cycles/バイト = 640 T-cyclesかかる
                dmaCyclesRemaining = 640
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
            // LCD無効中は STAT 信号線も LOW にリセット
            // これにより LCD 再有効化後、最初の条件成立でエッジ検出が正しく動作する
            statInterruptLine = false
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

                if (ly.toInt() < SCREEN_HEIGHT) {
                    // 次のスキャンライン開始（Mode 2）- レジスタをキャプチャ（ラスター効果対応）
                    val lyInt = ly.toInt()
                    scanlineScx[lyInt] = scx.toInt()
                    scanlineScy[lyInt] = scy.toInt()
                    scanlineBgp[lyInt] = bgp.toInt()
                    scanlineObp0[lyInt] = obp0.toInt()
                    scanlineObp1[lyInt] = obp1.toInt()
                    scanlineLcdc[lyInt] = lcdc.toInt()
                    setMode(PpuMode.OAM_SEARCH)
                    // LYC=LY チェック（新しい LY 値で判定）
                    updateStatInterruptLine()
                } else if (ly == 144u.toUByte()) {
                    // VBlank開始（Mode 1）
                    setMode(PpuMode.VBLANK)
                    interruptController.request(InterruptController.Type.VBLANK)
                    // VBlank 開始直前（スキャンライン 143 完了直後）にフレームをキャプチャする。
                    // ゲームは VBlank 中に VRAM/OAM を次フレーム用に書き換えるため、
                    // その前にキャプチャしないと現フレームと次フレームが混在したちらつきが発生する。
                    captureFrameInternal()
                    // LYC=LY チェックを含む STAT 信号更新
                    updateStatInterruptLine()
                } else if (ly == 0u.toUByte()) {
                    // VBlank終了、次のフレーム開始（Mode 2）
                    // 新フレーム開始時に配列を -1（未設定）にリセットしてから、スキャンライン0をキャプチャ
                    scanlineScx.fill(-1)
                    scanlineScy.fill(-1)
                    scanlineBgp.fill(-1)
                    scanlineObp0.fill(-1)
                    scanlineObp1.fill(-1)
                    scanlineLcdc.fill(-1)
                    windowLineCounter = 0
                    scanlineWindowLine.fill(-1)
                    scanlineScx[0] = scx.toInt()
                    scanlineScy[0] = scy.toInt()
                    scanlineBgp[0] = bgp.toInt()
                    scanlineObp0[0] = obp0.toInt()
                    scanlineObp1[0] = obp1.toInt()
                    scanlineLcdc[0] = lcdc.toInt()
                    setMode(PpuMode.OAM_SEARCH)
                    updateStatInterruptLine()
                } else {
                    // VBlank 中の残りスキャンライン（LY=145〜153）の LYC=LY チェック
                    updateStatInterruptLine()
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
                        // Mode 3に遷移する前に、このスキャンラインのMode 3の長さを計算
                        mode3Duration = calculateMode3Duration()
                        setMode(PpuMode.PIXEL_TRANSFER)
                    }
                }

                PpuMode.PIXEL_TRANSFER -> {
                    // Mode 3: Pixel Transfer（172-289サイクル、スプライト数/SCX/ウィンドウにより可変）
                    if (modeCycles >= mode3Duration) {
                        setMode(PpuMode.HBLANK)
                        // Mode 3 → HBlank 遷移: 信号線更新（HBlank 条件が新たに真になれば発火）
                        updateStatInterruptLine()
                        // ウィンドウ内部ラインカウンタの更新
                        // 実機ではウィンドウが実際に描画されたスキャンラインでのみインクリメント
                        val currentLy = ly.toInt()
                        if (currentLy < SCREEN_HEIGHT) {
                            val lcdcSnap = if (scanlineLcdc[currentLy] >= 0) scanlineLcdc[currentLy] else lcdc.toInt()
                            val windowEnabledForLine = (lcdcSnap and 0x20) != 0
                            if (windowEnabledForLine && currentLy >= wy.toInt() && wx.toInt() in 0..166) {
                                scanlineWindowLine[currentLy] = windowLineCounter
                                windowLineCounter++
                            }
                        }
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
     * PPUモードを設定する。
     *
     * STAT 割り込みのチェックは呼び出し側で updateStatInterruptLine() を呼ぶことで行う。
     * OAM_SEARCH/HBLANK/VBLANK への遷移時は setMode() の後に updateStatInterruptLine() を呼ぶこと。
     * PIXEL_TRANSFER への遷移時は setMode() の中で updateStatInterruptLine() を呼ぶ。
     * （PIXEL_TRANSFER 中も LYC=LY が成立すれば信号は high のまま維持される）
     */
    private fun setMode(newMode: PpuMode) {
        if (currentMode != newMode) {
            currentMode = newMode
            modeCycles = 0
            // PIXEL_TRANSFER 遷移時: LYC=LY 以外のソースが全てなくなるので信号を再評価。
            // LYC=LY が成立していれば信号は high のまま（reset しない）。
            if (newMode == PpuMode.PIXEL_TRANSFER) {
                updateStatInterruptLine()
            }
        }
    }

    /**
     * STAT 割り込み信号線の現在値を計算し、立ち上がりエッジがあれば LCD_STAT 割り込みを発火する。
     *
     * 実機 GB では、以下の 4 ソースを OR 合成した 1 本の信号線の
     * "立ち上がりエッジ（0→1）" でのみ割り込みが発生する:
     *   - bit3: HBlank（Mode 0）中かつ STAT bit3 = 1
     *   - bit4: VBlank（Mode 1）中かつ STAT bit4 = 1
     *   - bit5: OAM Search（Mode 2）中かつ STAT bit5 = 1
     *   - bit6: LYC=LY かつ STAT bit6 = 1
     */
    private fun updateStatInterruptLine() {
        val s = stat.toInt()
        // LYC=LY 比較: LYC が有効範囲（0-153）を超えた場合は TOTAL_SCANLINES でラップする。
        // BAD APPLE!! など LYC を 0-153 を超えてインクリメントしてフレームをまたぐ場合、
        // 実機相当の挙動として LYC mod 154 == LY で判定する。
        val lycEffective = lyc.toInt().let { if (it >= TOTAL_SCANLINES) it % TOTAL_SCANLINES else it }
        val newLine =
            ((s and 0x08) != 0 && currentMode == PpuMode.HBLANK) ||
                ((s and 0x10) != 0 && currentMode == PpuMode.VBLANK) ||
                ((s and 0x20) != 0 && currentMode == PpuMode.OAM_SEARCH) ||
                ((s and 0x40) != 0 && ly.toInt() == lycEffective)

        if (newLine && !statInterruptLine) {
            interruptController.request(InterruptController.Type.LCD_STAT)
        }
        statInterruptLine = newLine
    }

    /**
     * パレットレジスタ値からARGBカラー配列を計算する。
     *
     * Game Boy の 4段階グレースケールを ARGB 値にマッピングする。
     */
    private fun computePaletteColors(paletteReg: Int): IntArray =
        IntArray(4) { colorId ->
            when ((paletteReg shr (colorId * 2)) and 0x03) {
                0 -> 0xFFFFFFFF.toInt() // 白
                1 -> 0xFFAAAAAA.toInt() // 薄いグレー
                2 -> 0xFF555555.toInt() // 濃いグレー
                else -> 0xFF000000.toInt() // 黒
            }
        }

    /**
     * 現在のスキャンラインでのMode 3（Pixel Transfer）の長さを計算する。
     *
     * 実機ではMode 3の長さは以下の要素で変動する:
     * - 基本: 172 T-cycles
     * - SCX mod 8 による遅延: 0-7 T-cycles
     * - スプライト数: 各スプライト最大6-11 T-cycles（平均約6）
     * - ウィンドウの有無: 約6 T-cycles
     */
    private fun calculateMode3Duration(): Int {
        val currentLy = ly.toInt()
        if (currentLy >= SCREEN_HEIGHT) return CYCLES_MODE_3_MIN

        var duration = CYCLES_MODE_3_MIN // 172 base

        // SCX mod 8 によるペナルティ（スキャンライン固有のキャプチャ値を使用）
        // -1（未設定センチネル）の場合は現在の scx レジスタにフォールバックする
        val rawScx = if (currentLy < scanlineScx.size) scanlineScx[currentLy] else -1
        val scxForLine = if (rawScx >= 0) rawScx else scx.toInt()
        duration += scxForLine and 0x07

        // このスキャンラインのスプライトペナルティを計算
        // 実機: スプライトのX座標と SCX の位置関係により 6-11 サイクルのペナルティが発生する
        // 近似式: penalty = 11 - min(5, (sprite_raw_x + scx_initial) mod 8)
        // sprite_raw_x はOAMの生X値（-8補正前）
        val lcdcInt = lcdc.toInt()
        val spriteEnabled = (lcdcInt and 0x02) != 0
        if (spriteEnabled) {
            val spriteSize = if (((lcdcInt shr 2) and 0x1) == 0) 8 else 16
            val oamSize = oam.size
            val scxMod8 = scxForLine and 0x07
            for (i in 0 until 40) {
                if (oam.size <= i shl 2) break
                val oamIndex = i shl 2
                if (oamIndex + 1 >= oamSize) continue
                val spriteY = oam[oamIndex].toInt() - 16
                if (currentLy >= spriteY && currentLy < spriteY + spriteSize) {
                    // スプライトの生X値（OAM値、-8補正前）とSCXのmod 8から実際のペナルティを計算
                    val rawSpriteX = oam[oamIndex + 1].toInt()
                    val alignmentPenalty = minOf(5, (rawSpriteX + scxMod8) and 0x07)
                    duration += 11 - alignmentPenalty
                }
                if (duration >= CYCLES_MODE_3_MAX) break // 上限に達したら打ち切り
            }
        }

        // ウィンドウが有効で、このスキャンラインで描画される場合
        val windowEnabled = (lcdcInt and 0x20) != 0
        if (windowEnabled && currentLy >= wy.toInt() && wx.toInt() in 0..166) {
            duration += 6
        }

        // 最大289 T-cyclesを超えないようにクランプ
        return duration.coerceIn(CYCLES_MODE_3_MIN, CYCLES_MODE_3_MAX)
    }

    /**
     * VBlank 開始時にキャプチャ済みのフレームバッファを返す。
     *
     * フレームバッファは [step] 内で LY=144（VBlank 開始）になった瞬間に
     * [captureFrameInternal] によってキャプチャされる。
     * これにより、ゲームが VBlank 中に VRAM/OAM を次フレーム用に書き換えても
     * 現フレームと次フレームが混在するちらつきが発生しない。
     *
     * - デバッグモード: テスト描画を有効にする場合は [debugMode] を true にする
     */
    fun renderFrame(debugMode: Boolean = false): IntArray {
        if (debugMode) return renderTestPattern()
        return frameBuffer.copyOf()
    }

    /**
     * VRAM/OAM/レジスタの現在状態から [frameBuffer] へ描画する（VBlank 開始時に呼ばれる）。
     *
     * scanlineScx/Scy は step() の各スキャンライン開始時にキャプチャされた値を使用する。
     * step() を経由せずに呼ばれた場合（ユニットテスト等）は IntArray デフォルト値の 0 が使われる
     * （スクロールなし = 0 はデフォルトとして正しい動作）。
     */
    internal fun captureFrameInternal() {
        // LCDC.7 (LCD Enable) が 0 の場合は、画面を白で塗りつぶす
        val lcdEnabled = (lcdc.toInt() and 0x80) != 0
        if (!lcdEnabled) {
            frameBuffer.fill(0xFFFFFFFF.toInt())
            return
        }

        // 背景/ウィンドウのカラーIDを保持する配列（スプライトの優先度処理用）
        val bgColorIds = UByteArray(SCREEN_WIDTH * SCREEN_HEIGHT) { 0u }

        // フォールバック値（スキャンラインスナップショットが未設定の場合に使用）
        val fallbackLcdc = lcdc.toInt()
        val fallbackBgp = bgp.toInt()
        val vramSize = vram.size

        // 背景描画（スキャンラインごとにキャプチャしたレジスタ値を使用）
        for (y in 0 until SCREEN_HEIGHT) {
            val lcdcSnap = if (scanlineLcdc[y] >= 0) scanlineLcdc[y] else fallbackLcdc
            val bgEnabled = (lcdcSnap and 0x01) != 0

            if (!bgEnabled) {
                // 背景が無効な場合は、このスキャンラインを白で塗りつぶし
                val base = y * SCREEN_WIDTH
                for (x in 0 until SCREEN_WIDTH) {
                    frameBuffer[base + x] = 0xFFFFFFFF.toInt()
                }
                continue
            }

            val bgMapBase = if (((lcdcSnap shr 3) and 0x1) == 0) BG_MAP0_BASE else BG_MAP1_BASE
            val tileDataMode = (lcdcSnap shr 4) and 0x1
            val bgpSnap = if (scanlineBgp[y] >= 0) scanlineBgp[y] else fallbackBgp
            val paletteColors = computePaletteColors(bgpSnap)

            val scxInt = if (scanlineScx[y] >= 0) scanlineScx[y] else scx.toInt()
            val scyInt = if (scanlineScy[y] >= 0) scanlineScy[y] else scy.toInt()

            val bgY = (y + scyInt) and 0xFF
            val tileRow = bgY shr 3
            val rowInTile = bgY and 0x07
            val rowInTile2 = rowInTile shl 1

            for (x in 0 until SCREEN_WIDTH) {
                val bgX = (x + scxInt) and 0xFF
                val tileCol = bgX shr 3
                val colInTile = bgX and 0x07

                // 32x32タイルマップなので、モジュロ演算でラップアラウンド
                val bgIndex = bgMapBase + (tileRow and 0x1F) * BG_MAP_WIDTH + (tileCol and 0x1F)
                val tileIndexByte = if (bgIndex < vramSize) vram[bgIndex] else 0u

                val tileIndex =
                    if (tileDataMode == 0) {
                        // 符号付き: 0x80-0xFF は -128〜-1 として扱う
                        val signed = tileIndexByte.toInt().toByte().toInt()
                        256 + signed // 0x8800 ベースに変換（0x8800 = 0x8000 + 0x800）
                    } else {
                        // 符号なし: 0x8000 ベース
                        tileIndexByte.toInt()
                    }

                val lineAddr = TILE_DATA_BASE + tileIndex * TILE_SIZE_BYTES + rowInTile2
                val low = if (lineAddr < vramSize) vram[lineAddr].toInt() else 0
                val high = if (lineAddr + 1 < vramSize) vram[lineAddr + 1].toInt() else 0

                val bit = 7 - colInTile
                val colorId =
                    (((high shr bit) and 0x1) shl 1) or
                        ((low shr bit) and 0x1)

                val pixelIndex = y * SCREEN_WIDTH + x
                frameBuffer[pixelIndex] = paletteColors[colorId]
                bgColorIds[pixelIndex] = colorId.toUByte()
            }
        }

        // ウィンドウ描画（背景の上、スプライトの下に描画）
        // scanlineWindowLine[] に記録されたスキャンラインのみ描画する
        renderWindow(frameBuffer, bgColorIds, fallbackLcdc)

        // スプライトの描画（スキャンラインごとのLCDC/OBPスナップショットを使用）
        renderSprites(frameBuffer, bgColorIds, fallbackLcdc)
    }

    /**
     * ウィンドウを描画する（スキャンライン単位、ウィンドウ内部ラインカウンタ使用）。
     *
     * - [scanlineWindowLine] に記録されたスキャンラインのみ描画する
     * - 実機仕様: ウィンドウが描画された行でのみ内部カウンタがインクリメントされる
     *   （フレーム途中でウィンドウ無効→再有効にしてもカウンタは継続する）
     * - WX < 7 対応: 実機では WX=0-6 の場合も描画される（左端からの表示）
     *   WX=7: ウィンドウがx=0から開始、WX=0: 最初の7ピクセルがクリップされる
     */
    private fun renderWindow(
        pixels: IntArray,
        bgColorIds: UByteArray,
        fallbackLcdc: Int,
    ) {
        val wyInt = wy.toInt()
        val wxInt = wx.toInt()

        // WYが144以上の場合、ウィンドウは表示されない（画面外）
        if (wyInt >= SCREEN_HEIGHT) return

        // WXが167以上の場合、ウィンドウは表示されない（画面外右端）
        if (wxInt >= 167) return

        // ウィンドウのX開始座標（WX - 7、WX < 7 の場合は 0 にクランプ）
        // windowTileStartX が負の場合、ウィンドウの左端が画面左端より外にある
        val windowTileStartX = wxInt - 7 // ウィンドウタイル内X=0 に対応する画面X座標
        val windowScreenStartX = maxOf(0, windowTileStartX)

        val vramSize = vram.size

        for (y in 0 until SCREEN_HEIGHT) {
            // step() でウィンドウが描画されたスキャンラインのみ処理する
            val windowLine = scanlineWindowLine[y]
            if (windowLine < 0) continue

            // スキャンライン固有のレジスタスナップショットを使用（ラスター効果対応）
            val lcdcSnap = if (scanlineLcdc[y] >= 0) scanlineLcdc[y] else fallbackLcdc
            // LCDC.6 でウィンドウマップを選択
            val windowMapBase = if (((lcdcSnap shr 6) and 0x1) == 0) BG_MAP0_BASE else BG_MAP1_BASE
            val tileDataMode = (lcdcSnap shr 4) and 0x1

            val bgpSnap = if (scanlineBgp[y] >= 0) scanlineBgp[y] else bgp.toInt()
            val paletteColors = computePaletteColors(bgpSnap)

            // ウィンドウ内部ラインカウンタによるタイル行計算（実機仕様に準拠）
            val tileRow = windowLine shr 3
            val rowInTile = windowLine and 0x07
            val rowInTile2 = rowInTile shl 1

            for (x in windowScreenStartX until SCREEN_WIDTH) {
                // ウィンドウタイルマップ内X座標（windowTileStartX が負でも正しく計算される）
                val windowX = x - windowTileStartX
                val tileCol = windowX shr 3
                val colInTile = windowX and 0x07

                // 32x32タイルマップなので、モジュロ演算でラップアラウンド
                val windowIndex = windowMapBase + (tileRow and 0x1F) * BG_MAP_WIDTH + (tileCol and 0x1F)
                val tileIndexByte = if (windowIndex < vramSize) vram[windowIndex] else 0u

                val tileIndex =
                    if (tileDataMode == 0) {
                        // 符号付き: 0x80-0xFF は -128〜-1 として扱う
                        val signed = tileIndexByte.toInt().toByte().toInt()
                        256 + signed // 0x8800 ベースに変換
                    } else {
                        // 符号なし: 0x8000 ベース
                        tileIndexByte.toInt()
                    }

                val lineAddr = TILE_DATA_BASE + tileIndex * TILE_SIZE_BYTES + rowInTile2
                val low = if (lineAddr < vramSize) vram[lineAddr].toInt() else 0
                val high = if (lineAddr + 1 < vramSize) vram[lineAddr + 1].toInt() else 0

                val bit = 7 - colInTile
                val colorId =
                    (((high shr bit) and 0x1) shl 1) or
                        ((low shr bit) and 0x1)

                val pixelIndex = y * SCREEN_WIDTH + x
                pixels[pixelIndex] = paletteColors[colorId]
                bgColorIds[pixelIndex] = colorId.toUByte()
            }
        }
    }

    /**
     * スプライトを描画する（スキャンライン単位、スキャンライン固有のOBP/LCDCを使用）。
     *
     * - OAM（0xFE00-0xFE9F）からスプライトデータを読み取る
     * - 各スプライトは4バイト：Y, X, タイルインデックス, 属性
     * - 最大10個のスプライトを描画（実機の制限）
     * - LCDC.2でスプライトサイズを決定（0=8x8、1=8x16）
     * - OBP0/OBP1はスキャンライン固有のスナップショット値を使用（ラスター効果対応）
     * - 優先度処理：優先度が高い（bit7=1）場合、背景/ウィンドウのカラーID 0以外の上には描画しない
     *
     * @param pixels ピクセル配列（ARGB形式）
     * @param bgColorIds 背景/ウィンドウのカラーID配列（優先度処理用）
     * @param fallbackLcdc スキャンラインスナップショット未設定時のフォールバック LCDC 値
     */
    private fun renderSprites(
        pixels: IntArray,
        bgColorIds: UByteArray,
        fallbackLcdc: Int,
    ) {
        val vramSize = vram.size
        val oamSize = oam.size
        val fallbackObp0 = obp0.toInt()
        val fallbackObp1 = obp1.toInt()

        for (y in 0 until SCREEN_HEIGHT) {
            // スキャンライン固有のレジスタスナップショットを使用
            val lcdcSnap = if (scanlineLcdc[y] >= 0) scanlineLcdc[y] else fallbackLcdc
            val spriteEnabled = (lcdcSnap and 0x02) != 0
            if (!spriteEnabled) continue

            val spriteSize = if (((lcdcSnap shr 2) and 0x1) == 0) 8 else 16
            val obp0Snap = if (scanlineObp0[y] >= 0) scanlineObp0[y] else fallbackObp0
            val obp1Snap = if (scanlineObp1[y] >= 0) scanlineObp1[y] else fallbackObp1
            val obp0Colors = computePaletteColors(obp0Snap)
            val obp1Colors = computePaletteColors(obp1Snap)

            val spritesOnLine = mutableListOf<Int>()

            // OAMからスプライトを検索（最大10個）
            for (i in 0 until 40) {
                if (spritesOnLine.size >= 10) break

                val oamIndex = i shl 2
                if (oamIndex + 1 >= oamSize) continue
                val spriteY = oam[oamIndex].toInt() - 16

                if (y >= spriteY && y < spriteY + spriteSize) {
                    spritesOnLine.add(i)
                }
            }

            // スプライトを描画（DMG実機の優先度仕様に準拠）
            // 実機 DMG: X座標が小さいスプライトが優先（上に表示される）
            // X座標が同じ場合はOAMインデックスが小さいスプライトが優先
            // → X座標昇順にソート（安定ソートでOAMインデックス順を保持）し逆順で描画
            val sortedSprites = spritesOnLine.sortedBy { oam[(it shl 2) + 1].toInt() }
            for (i in sortedSprites.reversed()) {
                val oamIndex = i shl 2
                if (oamIndex + 3 >= oamSize) continue
                val spriteY = oam[oamIndex].toInt() - 16
                val spriteX = oam[oamIndex + 1].toInt() - 8
                val tileIndex = oam[oamIndex + 2].toInt()
                val attributes = oam[oamIndex + 3].toInt()

                val spriteColors = if ((attributes and 0x10) != 0) obp1Colors else obp0Colors
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
                val tileRow = actualRow and 0x07

                val lineAddr = TILE_DATA_BASE + actualTileIndex * TILE_SIZE_BYTES + (tileRow shl 1)
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

                    // 優先度チェック：bit7=1 の場合、背景/ウィンドウのカラーID 0以外の上には描画しない
                    if (priority && bgColorIds[pixelIndex] != 0u.toUByte()) {
                        continue
                    }

                    pixels[pixelIndex] = spriteColors[colorId]
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
}
