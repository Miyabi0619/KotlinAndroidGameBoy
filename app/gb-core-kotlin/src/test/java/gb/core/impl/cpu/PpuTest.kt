package gb.core.impl.cpu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.ExperimentalUnsignedTypes
import kotlin.OptIn

/**
 * PPU のユニットテスト。
 *
 * GB仕様（Pan Docs: https://gbdev.io/pandocs/Rendering.html）に基づき、
 * スキャンラインタイミング・モード遷移・割り込み・パレット・描画を検証する。
 *
 * PPU モードタイミング（1スキャンライン = 456 T-cycles）:
 *   Mode 2 (OAM_SEARCH)   : 80 T-cycles
 *   Mode 3 (PIXEL_TRANSFER): 172 T-cycles（最小）
 *   Mode 0 (HBLANK)        : 残り 204 T-cycles（最小構成）
 *   Mode 1 (VBLANK)        : LY 144-153（10スキャンライン）
 *
 * LY 更新のタイミング: step() の内部では「前のスキャンライン分が蓄積された後、
 * 次のサイクルが来たときに LY が進む」実装のため、
 * LY=N にするには step(456*N + 1) 以上のサイクルが必要。
 */
@OptIn(ExperimentalUnsignedTypes::class)
class PpuTest {
    // ────────────────────────────────────────────────────────────────
    // ヘルパー
    // ────────────────────────────────────────────────────────────────

    /** テスト用の Ppu + InterruptController ペアを生成する */
    private fun makePpu(
        vram: UByteArray = UByteArray(0x2000) { 0u },
        oam: UByteArray = UByteArray(0xA0) { 0u },
    ): Pair<Ppu, InterruptController> {
        val ic = InterruptController()
        ic.writeIe(0x1Fu)
        return Pair(Ppu(vram, oam, ic), ic)
    }

    /**
     * PPU を1スキャンライン分ステップする（LY++ をトリガーする実装細部を考慮）。
     *
     * Mode 遷移:
     *   step(80)  → OAM_SEARCH → PIXEL_TRANSFER
     *   step(172) → PIXEL_TRANSFER → HBLANK（HBLANK STAT 割り込みチェック）
     *   step(204) → HBLANK 完了（scanlineCycles=456）
     *   step(1)   → LY++、新ライン OAM_SEARCH 開始（OAM STAT 割り込みチェック）
     */
    private fun Ppu.stepOneScanline() {
        step(80) // Mode 2: OAM_SEARCH → PIXEL_TRANSFER
        step(172) // Mode 3: PIXEL_TRANSFER → HBLANK
        step(204) // Mode 0: HBlank 完了
        step(1) // LY++ トリガー
    }

    /**
     * テスト用: step() なしで即時キャプチャしてフレームを返す。
     * 実機では VBlank 開始時に自動キャプチャされるが、ユニットテストでは
     * step() を 65,664 サイクル回す必要があるため、このヘルパーを使う。
     */
    private fun Ppu.renderForTest(): IntArray {
        captureFrameInternal()
        return renderFrame()
    }

    // ────────────────────────────────────────────────────────────────
    // LY（スキャンライン）更新
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `renderFrame draws tiles from VRAM background map`() {
        // 8x8 の単色タイルを 1 枚だけ定義し、BG マップの左上に配置する
        val vram = UByteArray(0x2000) { 0u }

        // タイル 0: すべて colorId = 3（黒）になるように、low=1, high=1 を全ビットに立てる
        // 1 ライン 2 バイト * 8 ライン = 16 バイト
        for (row in 0 until 8) {
            val base = row * 2
            vram[base] = 0xFFu // low
            vram[base + 1] = 0xFFu // high
        }

        // BG マップ 0 の先頭にタイル 0 を配置（0x9800 -> vram[0x1800]）
        val bgMapBase = 0x1800
        vram[bgMapBase] = 0x00u

        val interruptController = InterruptController()
        val oam = UByteArray(0xA0) { 0u }
        val ppu = Ppu(vram, oam, interruptController)
        val frame = ppu.renderForTest()

        // 左上 8x8 ピクセルはすべてタイル 0（黒）になっているはず
        val black = 0xFF000000.toInt()
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                val idx = y * Ppu.SCREEN_WIDTH + x
                assertEquals(black, frame[idx])
            }
        }
    }

    @Test
    fun `LY starts at 0 on fresh PPU`() {
        val (ppu, _) = makePpu()
        assertEquals(0u.toUByte(), ppu.readRegister(0x04))
    }

    @Test
    fun `LY advances to 1 after processing one full scanline`() {
        val (ppu, _) = makePpu()
        ppu.stepOneScanline() // LY 0 → 1
        assertEquals(1u.toUByte(), ppu.readRegister(0x04))
    }

    @Test
    fun `LY advances through multiple scanlines correctly`() {
        val (ppu, _) = makePpu()
        repeat(10) { ppu.stepOneScanline() }
        assertEquals(10u.toUByte(), ppu.readRegister(0x04))
    }

    @Test
    fun `LY write is ignored (read-only register)`() {
        val (ppu, _) = makePpu()
        ppu.stepOneScanline() // LY = 1
        ppu.writeRegister(0x04, 0x00u) // 書き込みは無視されるはず
        assertEquals(1u.toUByte(), ppu.readRegister(0x04))
    }

    // ────────────────────────────────────────────────────────────────
    // VBlank 割り込み
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `VBlank interrupt is requested when LY reaches 144`() {
        val (ppu, ic) = makePpu()

        // 144 スキャンライン処理して LY=144 を迎える
        repeat(144) { ppu.stepOneScanline() }

        assertEquals(144u.toUByte(), ppu.readRegister(0x04))
        val pending = ic.nextPending(imeEnabled = true)
        assertEquals(
            "LY=144 で VBLANK 割り込みが要求されるはず",
            InterruptController.Type.VBLANK,
            pending,
        )
    }

    @Test
    fun `LY wraps back to 0 after 154 scanlines`() {
        val (ppu, _) = makePpu()
        // VBlank 期間（LY 144-153）を含む 154 ライン全て処理
        repeat(154) { ppu.stepOneScanline() }
        assertEquals(0u.toUByte(), ppu.readRegister(0x04))
    }

    // ────────────────────────────────────────────────────────────────
    // LCD 有効/無効（LCDC bit7）
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `LCD disable via LCDC bit7 resets LY to 0`() {
        val (ppu, _) = makePpu()
        ppu.stepOneScanline() // LY = 1
        ppu.stepOneScanline() // LY = 2

        // LCDC bit7 = 0 → LCD 無効
        ppu.writeRegister(0x00, 0x11u) // 0x11 = bit4+bit0（bit7=0）
        ppu.step(456) // 無効状態でステップしても LY はリセット

        assertEquals(0u.toUByte(), ppu.readRegister(0x04))
    }

    // ────────────────────────────────────────────────────────────────
    // STAT 割り込み
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `STAT HBlank interrupt fires when bit3 is set`() {
        val (ppu, ic) = makePpu()
        // STAT bit3 = Mode 0 (HBlank) 割り込み許可
        ppu.writeRegister(0x01, 0x08u)

        // OAM_SEARCH(80) → PIXEL_TRANSFER(172) → HBLANK 遷移時に割り込みチェック
        ppu.step(80) // Mode 2 → 3
        ppu.step(172) // Mode 3 → 0（ここで HBLANK STAT 割り込みが発火）

        val pending = ic.nextPending(imeEnabled = true)
        assertEquals(
            "STAT bit3=1 のとき HBlank 遷移で LCD_STAT 割り込みが発生するはず",
            InterruptController.Type.LCD_STAT,
            pending,
        )
    }

    @Test
    fun `STAT HBlank interrupt does not fire when bit3 is cleared`() {
        val (ppu, ic) = makePpu()
        // STAT bit3 = 0（HBlank 割り込み無効）
        ppu.writeRegister(0x01, 0x00u)

        ppu.step(80)
        ppu.step(172)

        val pending = ic.nextPending(imeEnabled = true)
        assertTrue(
            "STAT bit3=0 のとき HBlank 遷移で LCD_STAT 割り込みは発生しないはず",
            pending != InterruptController.Type.LCD_STAT,
        )
    }

    @Test
    fun `STAT OAM interrupt fires when bit5 is set at start of new scanline`() {
        val (ppu, ic) = makePpu()
        // STAT bit5 = Mode 2 (OAM) 割り込み許可
        ppu.writeRegister(0x01, 0x20u)

        // 1スキャンライン完了 → LY++ + 新ライン OAM_SEARCH 遷移 → STAT 割り込み
        ppu.step(80)
        ppu.step(172)
        ppu.step(204)
        ppu.step(1) // LY++ + setMode(OAM_SEARCH) → checkStatInterrupt(OAM_SEARCH)

        val pending = ic.nextPending(imeEnabled = true)
        assertEquals(
            "STAT bit5=1 のとき新スキャンライン開始（Mode 2 遷移）で LCD_STAT 割り込みが発生するはず",
            InterruptController.Type.LCD_STAT,
            pending,
        )
    }

    @Test
    fun `STAT LYC coincidence interrupt fires when bit6 is set and LY equals LYC`() {
        val (ppu, ic) = makePpu()
        // LYC = 3 を設定
        ppu.writeRegister(0x05, 3u)
        // STAT bit6 = LYC=LY 割り込み許可
        ppu.writeRegister(0x01, 0x40u)

        // LY が 3 になるまでスキャンライン処理
        repeat(3) { ppu.stepOneScanline() }
        // この時点で LY=3 = LYC → checkLycMatch() により LCD_STAT 発火

        val pending = ic.nextPending(imeEnabled = true)
        assertEquals(
            "LY=LYC のとき STAT bit6=1 なら LCD_STAT 割り込みが発生するはず",
            InterruptController.Type.LCD_STAT,
            pending,
        )
    }

    // ────────────────────────────────────────────────────────────────
    // STAT レジスタのモードビット
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `STAT register lower 2 bits reflect current PPU mode`() {
        val (ppu, _) = makePpu()

        // 初期モードは OAM_SEARCH (Mode 2)
        val statInitial = ppu.readRegister(0x01).toInt() and 0x03
        assertEquals("初期モードは Mode 2 (OAM_SEARCH) のはず", 2, statInitial)

        // step(80) で PIXEL_TRANSFER (Mode 3) へ
        ppu.step(80)
        val statAfterOam = ppu.readRegister(0x01).toInt() and 0x03
        assertEquals("OAM_SEARCH 完了後は Mode 3 (PIXEL_TRANSFER) のはず", 3, statAfterOam)

        // step(172) で HBLANK (Mode 0) へ
        ppu.step(172)
        val statAfterPixel = ppu.readRegister(0x01).toInt() and 0x03
        assertEquals("PIXEL_TRANSFER 完了後は Mode 0 (HBLANK) のはず", 0, statAfterPixel)
    }

    // ────────────────────────────────────────────────────────────────
    // BGP パレット
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `BGP all zero maps all tile colors to white`() {
        // タイル 0: colorId=3（両ビット=1）
        val vram = UByteArray(0x2000) { 0u }
        for (row in 0 until 8) {
            vram[row * 2] = 0xFFu
            vram[row * 2 + 1] = 0xFFu
        }
        vram[0x1800] = 0x00u // BG マップにタイル 0

        val (ppu, _) = makePpu(vram = vram)
        // BGP = 0x00: 全カラーIDをエントリ0（白）にマップ
        ppu.writeRegister(0x07, 0x00u)

        val frame = ppu.renderForTest()
        val white = 0xFFFFFFFF.toInt()
        // タイル 0 の左上 8x8 はすべて白のはず
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                val idx = y * Ppu.SCREEN_WIDTH + x
                assertEquals("BGP=0x00 のとき colorId=3 でも白が表示されるはず", white, frame[idx])
            }
        }
    }

    @Test
    fun `BGP default 0xFC maps color 0 to white and color 3 to black`() {
        val vram = UByteArray(0x2000) { 0u }
        // タイル 0: colorId=3（黒のはず）
        for (row in 0 until 8) {
            vram[row * 2] = 0xFFu
            vram[row * 2 + 1] = 0xFFu
        }
        // タイル 1: colorId=0（白のはず）（データは 0 のままで OK）
        vram[0x1800] = 0x00u // 左タイル = タイル0（colorId=3）
        vram[0x1801] = 0x01u // 右タイル = タイル1（colorId=0）

        val (ppu, _) = makePpu(vram = vram)
        // BGP = 0xFC: colorId 0→0(白), 1→3(黒), 2→3(黒), 3→3(黒)
        // 実際: 0xFC = 11111100 → entry0=0b00=白, entry1=0b11=黒, ...
        ppu.writeRegister(0x07, 0xFCu)

        val frame = ppu.renderForTest()
        val white = 0xFFFFFFFF.toInt()
        val black = 0xFF000000.toInt()

        // タイル 0（colorId=3）→ 黒
        assertEquals("BGP=0xFC のとき colorId=3 は黒のはず", black, frame[0])
        // タイル 1（colorId=0）→ 白
        assertEquals("BGP=0xFC のとき colorId=0 は白のはず", white, frame[8])
    }

    // ────────────────────────────────────────────────────────────────
    // BG 無効
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `BG disabled via LCDC bit0 renders all white pixels`() {
        val vram = UByteArray(0x2000) { 0u }
        // 真っ黒タイルをデータとして入れておく
        for (row in 0 until 8) {
            vram[row * 2] = 0xFFu
            vram[row * 2 + 1] = 0xFFu
        }
        vram[0x1800] = 0x00u

        val (ppu, _) = makePpu(vram = vram)
        // LCDC bit0 = 0: BG 無効（bit7=1 で LCD は有効のまま）
        ppu.writeRegister(0x00, 0x90u) // 0x90 = 1001_0000 (bit7=1, bit4=1, bit0=0)

        val frame = ppu.renderForTest()
        val white = 0xFFFFFFFF.toInt()
        // BG が無効なので画面全体が白のはず
        assertTrue("BG 無効時は全ピクセルが白のはず", frame.all { it == white })
    }

    // ────────────────────────────────────────────────────────────────
    // SCX スクロール
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `SCX scroll shifts background rendering`() {
        // タイル 0: 黒（colorId=3）
        val vram = UByteArray(0x2000) { 0u }
        for (row in 0 until 8) {
            vram[row * 2] = 0xFFu
            vram[row * 2 + 1] = 0xFFu
        }
        // BG マップ: タイル 0 を左端に、タイル 1（白）を右に置く
        vram[0x1800] = 0x00u // X=0 タイル0（黒）
        vram[0x1801] = 0x01u // X=1 タイル1（白）

        val (ppu, _) = makePpu(vram = vram)

        // SCX = 0: 左端 (x=0) はタイル 0（黒）
        ppu.writeRegister(0x03, 0x00u) // SCX = 0
        val frameNoScroll = ppu.renderForTest()
        val firstPixelNoScroll = frameNoScroll[0]

        // SCX = 8: 左端 (x=0) がタイル 1（白）にシフト
        ppu.writeRegister(0x03, 0x08u) // SCX = 8
        val frameScrolled = ppu.renderForTest()
        val firstPixelScrolled = frameScrolled[0]

        assertNotEquals(
            "SCX=8 のとき左端ピクセルがシフトして異なる色になるはず",
            firstPixelNoScroll,
            firstPixelScrolled,
        )
    }

    // ────────────────────────────────────────────────────────────────
    // STAT 割り込みエッジ検出（Pan Docs: STAT Blocking Glitch / Signal combiner）
    //
    // 実機 GB の STAT 割り込みは複数ソース（HBlank/OAM/VBlank/LYC=LY）を OR 合成した
    // 1 本の信号線の "立ち上がりエッジ" でのみ発火する。
    // HBlank → OAM 遷移時に、信号線が High のままなら追加割り込みは発生しない。
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `STAT fires only once per scanline when both HBlank and OAM interrupts are enabled`() {
        // bit3（HBlank）+ bit5（OAM）の両方を有効化。
        //
        // 実機仕様: STAT 信号は各ソースを OR 合成した 1 本の線の "立ち上がりエッジ" でのみ発火。
        // - OAM_SEARCH: 信号 high（bit5=1, mode=OAM）
        // - PIXEL_TRANSFER: 信号 low（mode 対応ソースなし、LYC≠LY）
        // - HBLANK: 信号 high（bit3=1, mode=HBlank）← 立ち上がり → 割り込み発火
        // - LY++ → 次の OAM_SEARCH: 信号 high のまま（HBLANK が high → OAM も high）→ 追加割り込みなし
        //
        // → 1 スキャンラインにつき HBLANK での 1 回のみ発火。
        val (ppu, ic) = makePpu()
        ppu.writeRegister(0x01, 0x28u) // STAT: bit3=HBlank, bit5=OAM

        // OAM_SEARCH → PIXEL_TRANSFER（信号 low）→ HBLANK 遷移で割り込み発火
        ppu.step(80)
        ppu.step(172)

        val firstInterrupt = ic.nextPending(imeEnabled = true)
        assertEquals(
            "PIXEL_TRANSFER→HBLANK 遷移で LCD_STAT 割り込みが1回発生するはず",
            InterruptController.Type.LCD_STAT,
            firstInterrupt,
        )

        // HBLANK → 次スキャンラインの OAM_SEARCH へ遷移
        // 信号は HBLANK(high) → OAM(high) のまま → 立ち上がりなし → 追加割り込みなし
        ppu.step(204)
        ppu.step(1) // LY++ + OAM_SEARCH 開始

        val noSecondInterrupt = ic.nextPending(imeEnabled = true)
        assertEquals(
            "HBLANK→OAM 遷移は信号が high のまま（立ち上がりなし）なので追加 STAT は発生しないはず",
            null,
            noSecondInterrupt,
        )

        // 次のスキャンラインでもう一度 PIXEL_TRANSFER → HBLANK で発火することを確認
        ppu.step(80) // OAM→PIXEL_TRANSFER（信号 low）
        ppu.step(172) // PIXEL_TRANSFER→HBLANK（信号 0→1 → 発火）

        val thirdInterrupt = ic.nextPending(imeEnabled = true)
        assertEquals(
            "次のスキャンラインでも HBLANK 遷移で LCD_STAT が発火するはず",
            InterruptController.Type.LCD_STAT,
            thirdInterrupt,
        )
    }

    @Test
    fun `STAT does not fire again when line stays high from HBlank directly to VBlank`() {
        // LY=143 のスキャンラインで HBlank（bit3）と VBlank（bit4）の両方が有効な場合
        // HBlank で signal=1、その後 VBlank（signal=1のまま）→ 追加割り込みなし
        val (ppu, ic) = makePpu()
        ppu.writeRegister(0x01, 0x18u) // STAT: bit3=HBlank + bit4=VBlank

        // LY=143 まで進める（HBlank 割り込みは1スキャンごとに発火）
        repeat(143) {
            ppu.step(80)
            ppu.step(172) // HBlank 割り込み発火（各スキャンライン）
            ic.nextPending(imeEnabled = true) // 割り込みを消費
            ppu.step(204)
            ppu.step(1) // LY++
        }

        // LY=143 のスキャンライン: HBlank→VBlank の遷移
        ppu.step(80)
        ppu.step(172) // HBlank 割り込み発火
        val hblankInterrupt = ic.nextPending(imeEnabled = true)
        assertEquals(
            "LY=143 の HBlank で LCD_STAT 割り込みが発生するはず",
            InterruptController.Type.LCD_STAT,
            hblankInterrupt,
        )

        // HBlank→VBlank 遷移
        ppu.step(204)
        ppu.step(1) // LY=144 → VBlank 開始

        // VBLANK 割り込みは発火するはず（VBlank 自体は別の割り込みライン）
        val vblankInterrupt = ic.nextPending(imeEnabled = true)
        assertEquals(
            "LY=144 で VBLANK 割り込みが発生するはず",
            InterruptController.Type.VBLANK,
            vblankInterrupt,
        )

        // STAT bit4=VBlank が有効: HBlank（high）→ VBlank（high）で信号がhighのまま→ 追加 STAT なし
        val extraStat = ic.nextPending(imeEnabled = true)
        assertEquals(
            "HBlank→VBlank の連続 high では STAT 割り込みが重複発火しないはず",
            null,
            extraStat,
        )
    }

    @Test
    fun `STAT LYC interrupt fires again on next scanline when LYC matches new LY`() {
        // LYC=2 を設定。LY=2 で1回発火後、LY=3 では発火しない（LYC≠LY）
        // LY が再び 2 に来ると再発火するが、それは 154 スキャンライン後
        val (ppu, ic) = makePpu()
        ppu.writeRegister(0x05, 2u) // LYC = 2
        ppu.writeRegister(0x01, 0x40u) // STAT bit6: LYC=LY 割り込み許可

        // LY=2 になるまで進める
        repeat(2) { ppu.stepOneScanline() }

        val interrupt = ic.nextPending(imeEnabled = true)
        assertEquals(InterruptController.Type.LCD_STAT, interrupt)

        // LY=3 になっても LYC=2 なので割り込みなし
        ppu.stepOneScanline()
        val noInterrupt = ic.nextPending(imeEnabled = true)
        assertEquals(
            "LY=3 のとき LYC=2 なので STAT 割り込みは発生しないはず",
            null,
            noInterrupt,
        )
    }
}
