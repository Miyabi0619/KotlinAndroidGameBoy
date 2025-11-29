package gb.core.impl.cpu

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.ExperimentalUnsignedTypes
import kotlin.OptIn

@OptIn(ExperimentalUnsignedTypes::class)
class PpuTest {
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
        val frame = ppu.renderFrame()

        // 左上 8x8 ピクセルはすべてタイル 0（黒）になっているはず
        val black = 0xFF000000.toInt()
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                val idx = y * Ppu.SCREEN_WIDTH + x
                assertEquals(black, frame[idx])
            }
        }
    }
}
