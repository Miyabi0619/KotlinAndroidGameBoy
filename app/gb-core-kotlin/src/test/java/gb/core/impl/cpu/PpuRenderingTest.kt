package gb.core.impl.cpu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.ExperimentalUnsignedTypes
import kotlin.OptIn

/**
 * PPU 画面描画ユニットテスト。
 *
 * GB 実機仕様（Pan Docs: https://gbdev.io/pandocs/Rendering.html）に基づき、
 * タイルデータ描画・パレット変換・BG マップ選択・スクロール・
 * ウィンドウ・スプライトの各動作を検証する。
 *
 * タイルデータフォーマット（GB 仕様）:
 *   各タイル 16 バイト（8 行 × 2 バイト）
 *   colorId = ((high >> (7-x)) & 1) << 1 | ((low >> (7-x)) & 1)
 *   bit7 = 最左端ピクセル、bit0 = 最右端ピクセル
 *
 * BGP 恒等パレット（0xE4 = 11_10_01_00）:
 *   colorId 0 → 白 (WHITE)    colorId 1 → 薄灰 (LIGHT_GRAY)
 *   colorId 2 → 濃灰 (DARK_GRAY)    colorId 3 → 黒 (BLACK)
 *
 * LCDC レジスタ主要ビット:
 *   bit7 = LCD 有効
 *   bit6 = ウィンドウマップ選択（0=0x9800, 1=0x9C00）
 *   bit5 = ウィンドウ有効
 *   bit4 = タイルデータモード（1=0x8000, 0=0x8800）
 *   bit3 = BG マップ選択（0=0x9800, 1=0x9C00）
 *   bit2 = スプライトサイズ（0=8x8, 1=8x16）
 *   bit1 = スプライト有効
 *   bit0 = BG 有効
 */
@OptIn(ExperimentalUnsignedTypes::class)
class PpuRenderingTest {
    companion object {
        const val WHITE = 0xFFFFFFFF.toInt()
        const val LIGHT_GRAY = 0xFFAAAAAA.toInt()
        const val DARK_GRAY = 0xFF555555.toInt()
        const val BLACK = 0xFF000000.toInt()

        /** 恒等パレット: colorId → 対応グレーレベル（GB 仕様の標準マッピング） */
        const val BGP_IDENTITY = 0xE4 // 11_10_01_00

        /** デフォルト LCDC: LCD on, 0x8000 モード, BG on, スプライト・ウィンドウ off */
        const val LCDC_DEFAULT = 0x91

        const val VRAM_SIZE = 0x2000
        const val OAM_SIZE = 0xA0
    }

    // ────────────────────────────────────────────────────────────────
    // ヘルパー
    // ────────────────────────────────────────────────────────────────

    /**
     * 指定した VRAM オフセットに 8x8 タイルデータを書き込む。
     * 全 8 行に同じ low/high バイトを書き込む。
     */
    private fun setTileAt(
        vram: UByteArray,
        vramOffset: Int,
        low: Int,
        high: Int,
    ) {
        for (row in 0 until 8) {
            vram[vramOffset + row * 2] = low.toUByte()
            vram[vramOffset + row * 2 + 1] = high.toUByte()
        }
    }

    /** 0x8000 モードでタイルインデックス [tileIndex] に 8x8 タイルを設定する。 */
    private fun setTile(
        vram: UByteArray,
        tileIndex: Int,
        low: Int,
        high: Int,
    ) = setTileAt(vram, tileIndex * 16, low, high)

    /**
     * OAM にスプライトエントリを設定する。
     *
     * @param screenY 画面座標 Y（OAM バイト0 = screenY + 16）
     * @param screenX 画面座標 X（OAM バイト1 = screenX + 8）
     */
    private fun setSprite(
        oam: UByteArray,
        index: Int,
        screenY: Int,
        screenX: Int,
        tileIdx: Int,
        attrs: Int,
    ) {
        val base = index * 4
        oam[base] = (screenY + 16).toUByte()
        oam[base + 1] = (screenX + 8).toUByte()
        oam[base + 2] = tileIdx.toUByte()
        oam[base + 3] = attrs.toUByte()
    }

    /** テスト用 PPU を生成する。 */
    private fun makePpu(
        vram: UByteArray = UByteArray(VRAM_SIZE) { 0u },
        oam: UByteArray = UByteArray(OAM_SIZE) { 0u },
    ): Ppu = Ppu(vram, oam, InterruptController())

    /** ピクセル (x, y) の ARGB 値を取得する。 */
    private fun IntArray.pixel(
        x: Int,
        y: Int,
    ): Int = this[y * Ppu.SCREEN_WIDTH + x]

    // ────────────────────────────────────────────────────────────────
    // 基本
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `renderFrame returns 160x144 pixel buffer`() {
        val frame = makePpu().renderFrame()
        assertEquals(Ppu.SCREEN_WIDTH * Ppu.SCREEN_HEIGHT, frame.size)
    }

    @Test
    fun `LCD disabled renders all white pixels`() {
        val vram = UByteArray(VRAM_SIZE) { 0u }
        setTile(vram, 0, 0xFF, 0xFF) // 黒タイルを仕込む（LCD 無効なら無視されるはず）
        vram[0x1800] = 0x00u

        val ppu = makePpu(vram)
        ppu.writeRegister(0x00, 0x11u) // LCDC bit7=0: LCD off
        val frame = ppu.renderFrame()

        assertTrue("LCD 無効時は全ピクセル白のはず", frame.all { it == WHITE })
    }

    // ────────────────────────────────────────────────────────────────
    // タイルデータ・カラーID（GB 仕様のビット平面構造）
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `low byte set high byte clear produces colorId 1 light gray`() {
        // low=0xFF, high=0x00 → colorId = (0<<1)|1 = 1 → LIGHT_GRAY
        val vram = UByteArray(VRAM_SIZE) { 0u }
        setTile(vram, 0, 0xFF, 0x00)
        vram[0x1800] = 0x00u

        val ppu = makePpu(vram)
        ppu.writeRegister(0x00, LCDC_DEFAULT.toUByte())
        ppu.writeRegister(0x07, BGP_IDENTITY.toUByte())

        assertEquals("colorId=1 → LIGHT_GRAY のはず", LIGHT_GRAY, ppu.renderFrame().pixel(0, 0))
    }

    @Test
    fun `low byte clear high byte set produces colorId 2 dark gray`() {
        // low=0x00, high=0xFF → colorId = (1<<1)|0 = 2 → DARK_GRAY
        val vram = UByteArray(VRAM_SIZE) { 0u }
        setTile(vram, 0, 0x00, 0xFF)
        vram[0x1800] = 0x00u

        val ppu = makePpu(vram)
        ppu.writeRegister(0x00, LCDC_DEFAULT.toUByte())
        ppu.writeRegister(0x07, BGP_IDENTITY.toUByte())

        assertEquals("colorId=2 → DARK_GRAY のはず", DARK_GRAY, ppu.renderFrame().pixel(0, 0))
    }

    @Test
    fun `both bytes set produces colorId 3 black`() {
        // low=0xFF, high=0xFF → colorId = (1<<1)|1 = 3 → BLACK
        val vram = UByteArray(VRAM_SIZE) { 0u }
        setTile(vram, 0, 0xFF, 0xFF)
        vram[0x1800] = 0x00u

        val ppu = makePpu(vram)
        ppu.writeRegister(0x00, LCDC_DEFAULT.toUByte())
        ppu.writeRegister(0x07, BGP_IDENTITY.toUByte())

        assertEquals("colorId=3 → BLACK のはず", BLACK, ppu.renderFrame().pixel(0, 0))
    }

    @Test
    fun `both bytes clear produces colorId 0 white`() {
        // VRAM はゼロ → colorId=0 → WHITE
        val ppu = makePpu()
        ppu.writeRegister(0x00, LCDC_DEFAULT.toUByte())
        ppu.writeRegister(0x07, BGP_IDENTITY.toUByte())

        assertEquals("colorId=0 → WHITE のはず", WHITE, ppu.renderFrame().pixel(0, 0))
    }

    // ────────────────────────────────────────────────────────────────
    // タイルピクセルのビット順序（GB 仕様: bit7 = 最左端）
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `tile bit 7 is leftmost pixel x equals 0`() {
        // low=0x80（1000_0000）: bit7 のみセット → x=0 だけ colorId=1
        val vram = UByteArray(VRAM_SIZE) { 0u }
        setTile(vram, 0, 0x80, 0x00)
        vram[0x1800] = 0x00u

        val ppu = makePpu(vram)
        ppu.writeRegister(0x00, LCDC_DEFAULT.toUByte())
        ppu.writeRegister(0x07, BGP_IDENTITY.toUByte())

        val frame = ppu.renderFrame()
        assertEquals("bit7 → x=0 は LIGHT_GRAY のはず", LIGHT_GRAY, frame.pixel(0, 0))
        assertEquals("bit7 のみセット → x=1 は WHITE のはず", WHITE, frame.pixel(1, 0))
    }

    @Test
    fun `tile bit 0 is rightmost pixel x equals 7`() {
        // low=0x01（0000_0001）: bit0 のみセット → x=7 だけ colorId=1
        val vram = UByteArray(VRAM_SIZE) { 0u }
        setTile(vram, 0, 0x01, 0x00)
        vram[0x1800] = 0x00u

        val ppu = makePpu(vram)
        ppu.writeRegister(0x00, LCDC_DEFAULT.toUByte())
        ppu.writeRegister(0x07, BGP_IDENTITY.toUByte())

        val frame = ppu.renderFrame()
        assertEquals("bit0 → x=7 は LIGHT_GRAY のはず", LIGHT_GRAY, frame.pixel(7, 0))
        assertEquals("bit0 のみセット → x=6 は WHITE のはず", WHITE, frame.pixel(6, 0))
    }

    // ────────────────────────────────────────────────────────────────
    // BG タイルデータモード（LCDC bit4）
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `tile data mode 0x8000 reads tile N from vram at N times 16`() {
        // LCDC bit4=1: タイルインデックス N → vram[N*16]
        val vram = UByteArray(VRAM_SIZE) { 0u }
        setTile(vram, 1, 0xFF, 0xFF) // タイル 1 を BLACK に
        vram[0x1800] = 0x01u // BG マップにタイル 1 を配置

        val ppu = makePpu(vram)
        ppu.writeRegister(0x00, LCDC_DEFAULT.toUByte()) // bit4=1: 0x8000 モード
        ppu.writeRegister(0x07, BGP_IDENTITY.toUByte())

        assertEquals("0x8000 モード: タイル 1 → BLACK のはず", BLACK, ppu.renderFrame().pixel(0, 0))
    }

    @Test
    fun `tile data mode 0x8800 reads tile index 0 from vram offset 0x1000`() {
        // LCDC bit4=0: タイルインデックス 0（符号付き）→ 256+0=256 → vram[256*16]=vram[0x1000]
        val vram = UByteArray(VRAM_SIZE) { 0u }
        setTileAt(vram, 0x1000, 0xFF, 0xFF) // vram[0x1000] に BLACK タイル
        vram[0x1800] = 0x00u // BG マップ: インデックス 0

        val ppu = makePpu(vram)
        ppu.writeRegister(0x00, 0x81u) // LCDC: LCD on, bit4=0 (0x8800 モード), BG on
        ppu.writeRegister(0x07, BGP_IDENTITY.toUByte())

        assertEquals(
            "0x8800 モード: インデックス 0 → vram[0x1000] の BLACK のはず",
            BLACK,
            ppu.renderFrame().pixel(0, 0),
        )
    }

    // ────────────────────────────────────────────────────────────────
    // BG マップ選択（LCDC bit3）
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `BG map 0 at vram 0x1800 used when LCDC bit3 is 0`() {
        // マップ 0 → タイル 0（BLACK）、マップ 1 → タイル 1（WHITE）
        val vram = UByteArray(VRAM_SIZE) { 0u }
        setTile(vram, 0, 0xFF, 0xFF) // タイル 0 → BLACK
        // タイル 1 はゼロデータ（WHITE）
        vram[0x1800] = 0x00u // マップ 0 位置 (0,0): タイル 0（BLACK）
        vram[0x1C00] = 0x01u // マップ 1 位置 (0,0): タイル 1（WHITE）

        val ppu = makePpu(vram)
        ppu.writeRegister(0x00, LCDC_DEFAULT.toUByte()) // bit3=0: マップ 0 使用
        ppu.writeRegister(0x07, BGP_IDENTITY.toUByte())

        assertEquals("LCDC bit3=0: マップ 0 → タイル 0 = BLACK のはず", BLACK, ppu.renderFrame().pixel(0, 0))
    }

    @Test
    fun `BG map 1 at vram 0x1C00 used when LCDC bit3 is 1`() {
        // マップ 0 → タイル 1（WHITE）、マップ 1 → タイル 0（BLACK）
        val vram = UByteArray(VRAM_SIZE) { 0u }
        setTile(vram, 0, 0xFF, 0xFF) // タイル 0 → BLACK
        vram[0x1800] = 0x01u // マップ 0: タイル 1（WHITE）
        vram[0x1C00] = 0x00u // マップ 1: タイル 0（BLACK）

        val ppu = makePpu(vram)
        ppu.writeRegister(0x00, 0x99u) // LCDC: LCD on, 0x8000, bit3=1（マップ 1）, BG on
        ppu.writeRegister(0x07, BGP_IDENTITY.toUByte())

        assertEquals("LCDC bit3=1: マップ 1 → タイル 0 = BLACK のはず", BLACK, ppu.renderFrame().pixel(0, 0))
    }

    // ────────────────────────────────────────────────────────────────
    // SCY / SCX スクロール
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `SCY scroll shifts background vertically`() {
        val vram = UByteArray(VRAM_SIZE) { 0u }
        setTile(vram, 0, 0xFF, 0xFF) // タイル 0 → BLACK
        // タイル 1 はゼロデータ（WHITE）
        vram[0x1800] = 0x00u // マップ 行0 列0: タイル 0（BLACK）
        vram[0x1800 + 32] = 0x01u // マップ 行1 列0（オフセット=32）: タイル 1（WHITE）

        val ppu = makePpu(vram)
        ppu.writeRegister(0x00, LCDC_DEFAULT.toUByte())
        ppu.writeRegister(0x07, BGP_IDENTITY.toUByte())

        ppu.writeRegister(0x02, 0x00u) // SCY=0: 画面 y=0 → BG y=0 → タイル行 0 → BLACK
        assertEquals("SCY=0: 画面 (0,0) は BLACK のはず", BLACK, ppu.renderFrame().pixel(0, 0))

        ppu.writeRegister(0x02, 0x08u) // SCY=8: 画面 y=0 → BG y=8 → タイル行 1 → WHITE
        assertEquals("SCY=8: 画面 (0,0) は WHITE のはず（タイル行 1 に移動）", WHITE, ppu.renderFrame().pixel(0, 0))
    }

    @Test
    fun `SCX scroll shifts background horizontally`() {
        val vram = UByteArray(VRAM_SIZE) { 0u }
        setTile(vram, 0, 0xFF, 0xFF) // タイル 0 → BLACK
        // タイル 1 はゼロデータ（WHITE）
        vram[0x1800] = 0x00u // マップ 列0: タイル 0（BLACK）
        vram[0x1801] = 0x01u // マップ 列1: タイル 1（WHITE）

        val ppu = makePpu(vram)
        ppu.writeRegister(0x00, LCDC_DEFAULT.toUByte())
        ppu.writeRegister(0x07, BGP_IDENTITY.toUByte())

        ppu.writeRegister(0x03, 0x00u) // SCX=0: 画面 x=0 → BG x=0 → 列0 → BLACK
        assertEquals("SCX=0: 画面 (0,0) は BLACK のはず", BLACK, ppu.renderFrame().pixel(0, 0))

        ppu.writeRegister(0x03, 0x08u) // SCX=8: 画面 x=0 → BG x=8 → 列1 → WHITE
        assertEquals("SCX=8: 画面 (0,0) は WHITE のはず", WHITE, ppu.renderFrame().pixel(0, 0))
    }

    @Test
    fun `SCX wraps background at 256 pixel boundary`() {
        // タイル 1 → BLACK。マップ 列0 のみタイル 1、他はタイル 0（WHITE）
        val vram = UByteArray(VRAM_SIZE) { 0u }
        setTile(vram, 1, 0xFF, 0xFF) // タイル 1 → BLACK
        vram[0x1800] = 0x01u // 列0: タイル 1（BLACK）。列1-31 はゼロ（タイル 0 = WHITE）

        val ppu = makePpu(vram)
        ppu.writeRegister(0x00, LCDC_DEFAULT.toUByte())
        ppu.writeRegister(0x07, BGP_IDENTITY.toUByte())
        ppu.writeRegister(0x03, 248.toUByte()) // SCX=248

        val frame = ppu.renderFrame()
        // 画面 x=7: BG x=(7+248)&0xFF=255 → 列31 → タイル 0 → WHITE
        assertEquals("SCX=248: 画面 x=7（BG x=255）は WHITE のはず", WHITE, frame.pixel(7, 0))
        // 画面 x=8: BG x=(8+248)&0xFF=0 → 列0 → タイル 1 → BLACK（ラップアラウンド）
        assertEquals("SCX=248: 画面 x=8（BG x=0 ラップ）は BLACK のはず", BLACK, frame.pixel(8, 0))
    }

    // ────────────────────────────────────────────────────────────────
    // ウィンドウ描画
    // ────────────────────────────────────────────────────────────────

    /**
     * ウィンドウテスト共通セットアップ。
     * BG マップ 0 → タイル 1（WHITE）、ウィンドウマップ 1 → タイル 0（BLACK）
     */
    private fun makeWindowPpu(): Ppu {
        val vram = UByteArray(VRAM_SIZE) { 0u }
        setTile(vram, 0, 0xFF, 0xFF) // タイル 0 → BLACK
        // タイル 1 はゼロデータ（WHITE）
        vram[0x1800] = 0x01u // BG マップ 0 位置 (0,0): タイル 1（WHITE）
        vram[0x1C00] = 0x00u // ウィンドウマップ 1 位置 (0,0): タイル 0（BLACK）

        val ppu = makePpu(vram)
        ppu.writeRegister(0x07, BGP_IDENTITY.toUByte())
        return ppu
    }

    @Test
    fun `window renders over background when LCDC bit5 is enabled`() {
        val ppu = makeWindowPpu()
        // LCDC=0xF1: LCD on, ウィンドウマップ 1（bit6=1）, ウィンドウ on（bit5=1）, 0x8000, BG on
        ppu.writeRegister(0x00, 0xF1u)
        ppu.writeRegister(0x0A, 0x00u) // WY=0
        ppu.writeRegister(0x0B, 7u) // WX=7 → windowStartX = 7-7 = 0

        assertEquals(
            "ウィンドウ有効: 画面 (0,0) は BLACK（ウィンドウから）のはず",
            BLACK,
            ppu.renderFrame().pixel(0, 0),
        )
    }

    @Test
    fun `window does not render when LCDC bit5 is disabled`() {
        val ppu = makeWindowPpu()
        // LCDC=0xD1: bit5=0（ウィンドウ無効）
        ppu.writeRegister(0x00, 0xD1u)
        ppu.writeRegister(0x0A, 0x00u)
        ppu.writeRegister(0x0B, 7u)

        assertEquals(
            "ウィンドウ無効: 画面 (0,0) は WHITE（BG から）のはず",
            WHITE,
            ppu.renderFrame().pixel(0, 0),
        )
    }

    @Test
    fun `window does not render above WY scanline`() {
        val ppu = makeWindowPpu()
        ppu.writeRegister(0x00, 0xF1u)
        ppu.writeRegister(0x0A, 8u) // WY=8: ウィンドウは行 8 以降のみ
        ppu.writeRegister(0x0B, 7u)

        val frame = ppu.renderFrame()
        assertEquals("WY=8: 画面 y=7 は WHITE（BG）のはず", WHITE, frame.pixel(0, 7))
        assertEquals("WY=8: 画面 y=8 は BLACK（ウィンドウ）のはず", BLACK, frame.pixel(0, 8))
    }

    @Test
    fun `window WX positions window start x at WX minus 7`() {
        val ppu = makeWindowPpu()
        ppu.writeRegister(0x00, 0xF1u)
        ppu.writeRegister(0x0A, 0x00u)
        ppu.writeRegister(0x0B, 14u) // WX=14 → windowStartX = 14-7 = 7

        val frame = ppu.renderFrame()
        assertEquals("WX=14: 画面 x=6 は WHITE（BG）のはず", WHITE, frame.pixel(6, 0))
        assertEquals("WX=14: 画面 x=7 は BLACK（ウィンドウ）のはず", BLACK, frame.pixel(7, 0))
    }

    // ────────────────────────────────────────────────────────────────
    // スプライト描画
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `sprite renders at screen position specified by OAM`() {
        // BG は白（ゼロデータ）。スプライトタイル 1 → colorId=3（OBP0=0xFF で BLACK）
        val vram = UByteArray(VRAM_SIZE) { 0u }
        setTile(vram, 1, 0xFF, 0xFF) // タイル 1: colorId=3

        val oam = UByteArray(OAM_SIZE) { 0u }
        setSprite(oam, 0, 0, 0, 1, 0) // 画面 (0,0)、タイル 1、属性なし

        val ppu = makePpu(vram, oam)
        ppu.writeRegister(0x00, 0x93u) // LCDC: LCD on, 0x8000, スプライト on, BG on
        // OBP0 デフォルト 0xFF: colorId=3 → entry3 → BLACK

        assertEquals("スプライト (0,0): BLACK のはず", BLACK, ppu.renderFrame().pixel(0, 0))
    }

    @Test
    fun `sprite colorId 0 is transparent and shows background`() {
        // BG タイル 0 → BLACK。スプライトタイル 2 → colorId=0（透明）
        val vram = UByteArray(VRAM_SIZE) { 0u }
        setTile(vram, 0, 0xFF, 0xFF) // BG → BLACK
        vram[0x1800] = 0x00u
        // タイル 2 はゼロデータ（colorId=0 = 透明）

        val oam = UByteArray(OAM_SIZE) { 0u }
        setSprite(oam, 0, 0, 0, 2, 0) // タイル 2（透明）を (0,0) に配置

        val ppu = makePpu(vram, oam)
        ppu.writeRegister(0x00, 0x93u)
        ppu.writeRegister(0x07, BGP_IDENTITY.toUByte())

        assertEquals(
            "colorId=0 のスプライト → BG の BLACK が透けて見えるはず",
            BLACK,
            ppu.renderFrame().pixel(0, 0),
        )
    }

    @Test
    fun `sprite X-flip reverses pixel order horizontally`() {
        // タイル 1: low=0x80（bit7 のみ）。xFlip なし → x=0 が colorId=1、x=1-7 は colorId=0
        //                                  xFlip あり → x=7 が colorId=1、x=0 は colorId=0（透明）
        val vram = UByteArray(VRAM_SIZE) { 0u }
        setTile(vram, 1, 0x80, 0x00)

        val oam = UByteArray(OAM_SIZE) { 0u }
        setSprite(oam, 0, 0, 0, 1, 0x20) // 属性 bit5=1: X フリップ

        val ppu = makePpu(vram, oam)
        ppu.writeRegister(0x00, 0x93u)
        ppu.writeRegister(0x07, BGP_IDENTITY.toUByte())
        ppu.writeRegister(0x08, BGP_IDENTITY.toUByte()) // OBP0 恒等: colorId=1 → LIGHT_GRAY

        val frame = ppu.renderFrame()
        // X フリップ後: x=0 は bit0 → colorId=0（透明）→ BG 白
        assertEquals("X フリップ: x=0 は WHITE（透明）のはず", WHITE, frame.pixel(0, 0))
        // X フリップ後: x=7 は bit7 → colorId=1 → LIGHT_GRAY
        assertEquals("X フリップ: x=7 は LIGHT_GRAY のはず", LIGHT_GRAY, frame.pixel(7, 0))
    }

    @Test
    fun `sprite Y-flip reverses pixel order vertically`() {
        // タイル 1: 行 0 のみ low=0xFF → colorId=1（LIGHT_GRAY）、行 1-7 は透明（colorId=0）
        val vram = UByteArray(VRAM_SIZE) { 0u }
        vram[1 * 16] = 0xFFu // 行 0: low=0xFF
        vram[1 * 16 + 1] = 0x00u // 行 0: high=0x00 → colorId=1

        val oam = UByteArray(OAM_SIZE) { 0u }
        setSprite(oam, 0, 0, 0, 1, 0x40) // 属性 bit6=1: Y フリップ

        val ppu = makePpu(vram, oam)
        ppu.writeRegister(0x00, 0x93u)
        ppu.writeRegister(0x07, BGP_IDENTITY.toUByte())
        ppu.writeRegister(0x08, BGP_IDENTITY.toUByte()) // OBP0 恒等

        val frame = ppu.renderFrame()
        // Y フリップ: 画面 y=0 → actualRow=7 → 行 7（colorId=0）→ 透明 → BG 白
        assertEquals("Y フリップ: 画面 y=0 は WHITE（行 7 = 透明）のはず", WHITE, frame.pixel(0, 0))
        // 画面 y=7 → actualRow=0 → 行 0（colorId=1）→ LIGHT_GRAY
        assertEquals("Y フリップ: 画面 y=7 は LIGHT_GRAY（行 0）のはず", LIGHT_GRAY, frame.pixel(0, 7))
    }

    @Test
    fun `OBP0 and OBP1 produce different colors for same tile colorId`() {
        // スプライト 0: OBP0 使用（bit4=0）、スプライト 1: OBP1 使用（bit4=1）
        // どちらも colorId=1 のタイル
        val vram = UByteArray(VRAM_SIZE) { 0u }
        setTile(vram, 1, 0xFF, 0x00) // colorId=1
        setTile(vram, 2, 0xFF, 0x00) // colorId=1

        val oam = UByteArray(OAM_SIZE) { 0u }
        setSprite(oam, 0, 0, 0, 1, 0x00) // OBP0 (bit4=0)
        setSprite(oam, 1, 0, 8, 2, 0x10) // OBP1 (bit4=1)

        val ppu = makePpu(vram, oam)
        ppu.writeRegister(0x00, 0x93u)
        ppu.writeRegister(0x07, BGP_IDENTITY.toUByte())
        ppu.writeRegister(0x08, 0x04u) // OBP0: colorId=1 → entry1=01 → LIGHT_GRAY
        ppu.writeRegister(0x09, 0x08u) // OBP1: colorId=1 → entry1=10 → DARK_GRAY

        val frame = ppu.renderFrame()
        assertEquals("OBP0(0x04): colorId=1 → LIGHT_GRAY のはず", LIGHT_GRAY, frame.pixel(0, 0))
        assertEquals("OBP1(0x08): colorId=1 → DARK_GRAY のはず", DARK_GRAY, frame.pixel(8, 0))
    }

    @Test
    fun `sprite with priority flag hides behind non-zero BG color`() {
        // BG: colorId=3（BLACK）。スプライト: colorId=1（LIGHT_GRAY）、priority=1
        val vram = UByteArray(VRAM_SIZE) { 0u }
        setTile(vram, 0, 0xFF, 0xFF) // BG タイル → colorId=3
        vram[0x1800] = 0x00u
        setTile(vram, 1, 0xFF, 0x00) // スプライトタイル → colorId=1

        val oam = UByteArray(OAM_SIZE) { 0u }
        setSprite(oam, 0, 0, 0, 1, 0x80) // 属性 bit7=1: BG の後ろ（優先度低）

        val ppu = makePpu(vram, oam)
        ppu.writeRegister(0x00, 0x93u)
        ppu.writeRegister(0x07, BGP_IDENTITY.toUByte())
        ppu.writeRegister(0x08, BGP_IDENTITY.toUByte()) // OBP0 恒等

        // BG colorId=3 ≠ 0 → スプライトは BG の後ろ → BLACK（BG）が前面
        assertEquals(
            "優先度フラグ: BG colorId≠0 のとき BLACK（BG）が前面に来るはず",
            BLACK,
            ppu.renderFrame().pixel(0, 0),
        )
    }

    @Test
    fun `sprite with priority flag is visible over BG color 0`() {
        // BG: colorId=0（WHITE）。スプライト: colorId=1（LIGHT_GRAY）、priority=1
        val vram = UByteArray(VRAM_SIZE) { 0u }
        setTile(vram, 1, 0xFF, 0x00) // スプライトタイル → colorId=1

        val oam = UByteArray(OAM_SIZE) { 0u }
        setSprite(oam, 0, 0, 0, 1, 0x80) // 属性 bit7=1: BG 優先

        val ppu = makePpu(vram, oam)
        ppu.writeRegister(0x00, 0x93u)
        ppu.writeRegister(0x07, BGP_IDENTITY.toUByte())
        ppu.writeRegister(0x08, BGP_IDENTITY.toUByte()) // OBP0 恒等

        // BG colorId=0 → スプライトは前面に描画可能 → LIGHT_GRAY
        assertEquals(
            "優先度フラグ: BG colorId=0 のときスプライトが描画されるはず",
            LIGHT_GRAY,
            ppu.renderFrame().pixel(0, 0),
        )
    }

    @Test
    fun `sprite completely off left screen edge is not drawn`() {
        // OAM X=0 → screenX = 0-8 = -8 → 全 8 ピクセルが画面外
        val vram = UByteArray(VRAM_SIZE) { 0u }
        setTile(vram, 1, 0xFF, 0xFF)

        val oam = UByteArray(OAM_SIZE) { 0u }
        oam[0] = 16u // OAM Y=16 → screenY=0
        oam[1] = 0u // OAM X=0 → screenX=-8（画面外）
        oam[2] = 1u
        oam[3] = 0u

        val ppu = makePpu(vram, oam)
        ppu.writeRegister(0x00, 0x93u)

        // スプライトは全画面外 → 画面 (0,0) は BG（WHITE）
        assertEquals("X=-8 のスプライトは描画されず WHITE のはず", WHITE, ppu.renderFrame().pixel(0, 0))
    }

    @Test
    fun `8x16 sprite uses tile N for top half and tile N plus 1 for bottom half`() {
        // タイル 2: ゼロデータ（colorId=0 = 透明）
        // タイル 3 = (2 & 0xFE) + 1: colorId=3（BLACK）
        val vram = UByteArray(VRAM_SIZE) { 0u }
        setTile(vram, 3, 0xFF, 0xFF) // タイル 3 → colorId=3

        val oam = UByteArray(OAM_SIZE) { 0u }
        setSprite(oam, 0, 0, 0, 2, 0) // タイル番号=2（8x16: 上半部=タイル 2、下半部=タイル 3）

        val ppu = makePpu(vram, oam)
        ppu.writeRegister(0x00, 0x97u) // LCDC: LCD on, 0x8000, bit2=1（8x16）, スプライト on, BG on
        // OBP0 デフォルト 0xFF: colorId=3 → BLACK

        val frame = ppu.renderFrame()
        // 上半部（y=0-7）: タイル 2 → colorId=0（透明）→ BG WHITE
        assertEquals("8x16 上半部: BG の WHITE が透けて見えるはず", WHITE, frame.pixel(0, 0))
        // 下半部（y=8-15）: タイル 3 → colorId=3 → BLACK
        assertEquals("8x16 下半部: タイル 3 = BLACK のはず", BLACK, frame.pixel(0, 8))
    }
}
