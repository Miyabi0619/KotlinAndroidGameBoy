package gb.core.impl.cpu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Mbc1Test {
    @Test
    fun `ROM bank mapping switches banks correctly`() {
        // 4 バンクぶんのダミー ROM（各バンク先頭にバンク番号を入れる）
        val bankSize = 0x4000
        val romSize = bankSize * 4
        val rom = UByteArray(romSize) { 0x00u }
        repeat(4) { bank ->
            rom[bank * bankSize] = bank.toUByte()
        }

        val mbc = Mbc1(romSize = romSize, ramSize = 0)

        // 初期状態: ROM バンク 1 が 4000–7FFF にマップされる
        val idx0 = mbc.mapRom0(0x0000)
        val idxX = mbc.mapRomX(0x4000)
        assertEquals(0x0000, idx0)
        assertEquals(1.toUByte(), rom[idxX])

        // ROM バンク番号を 2 に変更
        mbc.writeControl(0x2000, 0x02u)
        val idxX2 = mbc.mapRomX(0x4000)
        assertEquals(2.toUByte(), rom[idxX2])
    }

    @Test
    fun `RAM enable and banking works`() {
        val ramBankSize = 0x2000
        val ramSize = ramBankSize * 4
        val mbc = Mbc1(romSize = 0x8000, ramSize = ramSize)

        // 無効状態では null
        assertNull(mbc.mapRam(0xA000))

        // RAM Enable
        mbc.writeControl(0x0000, 0x0Au)
        val baseIndex = mbc.mapRam(0xA000)
        assertEquals(0, baseIndex)

        // バンキングモード RAM + バンク番号 2
        mbc.writeControl(0x6000, 0x01u) // RAM モード
        mbc.writeControl(0x4000, 0x02u) // RAM バンク 2
        val bank2Index = mbc.mapRam(0xA000)
        assertEquals(ramBankSize * 2, bank2Index)
    }
}
