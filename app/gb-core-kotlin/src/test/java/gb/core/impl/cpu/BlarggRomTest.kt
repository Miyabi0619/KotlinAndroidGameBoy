package gb.core.impl.cpu

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Ignore
import org.junit.Test
import java.io.File
import kotlin.ExperimentalUnsignedTypes

/**
 * blargg テストROM(`test_file/`)をヘッドレスで実行し、実機なしで合否を判定するハーネス。
 *
 * 判定方式は2種類:
 * - シリアル方式(cpu_instrs / instr_timing): SB に文字を書いて SC=0x81 で送信する
 *   古い世代のシェルフレームワークが対象。"Passed"/"Failed" 文字列で判定する。
 * - メモリ方式(dmg_sound / halt_bug / oam_bug): 新しいシェルフレームワークが
 *   $A000(結果コード, $80=実行中/$00=合格/その他=失敗) と $A001-$A003($DE $B0 $61 の
 *   シグネチャ)、$A004以降(NUL終端の失敗メッセージ)を書き込む規約に基づいて判定する。
 *   カートリッジRAMが必須のため `Machine(rom, forceCartridgeRam = true)` を使用する。
 *
 * 両方式とも一次資料は各ROMディレクトリ配下の `source/common/shell.s` 等のblarggソース自体。
 */
@OptIn(ExperimentalUnsignedTypes::class)
class BlarggRomTest {
    companion object {
        private const val CYCLES_PER_FRAME = 70_224

        // 実機120秒相当(59.7Hz換算)。dmg_sound本体のように全12サブテストを直列実行する
        // ROMでも十分な余裕を持たせる。
        private const val MAX_FRAMES = 7200

        // $A000 が 0x80(実行中)以外になってから、値が安定するまで待つフレーム数。
        private const val SETTLE_FRAMES = 5

        private val ADDR_FINAL_RESULT = 0xA000u.toUShort()
        private val ADDR_SIG_1 = 0xA001u.toUShort()
        private val ADDR_SIG_2 = 0xA002u.toUShort()
        private val ADDR_SIG_3 = 0xA003u.toUShort()
        private const val ADDR_TEXT_OUT_BASE = 0xA004

        /**
         * カレントディレクトリから上位に辿って `test_file/` ディレクトリを探す。
         * Gradle のユニットテストは通常モジュールディレクトリ(`app/gb-core-kotlin`)を
         * カレントディレクトリとして実行するため、リポジトリルートまで数階層遡る必要がある。
         */
        private fun findTestFileDir(): File? {
            var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
            repeat(8) {
                val current = dir ?: return null
                val candidate = File(current, "test_file")
                if (candidate.isDirectory) return candidate
                dir = current.parentFile
            }
            return null
        }

        private fun loadRom(relativePath: String): UByteArray? {
            val testFileDir = findTestFileDir() ?: return null
            val romFile = File(testFileDir, relativePath)
            if (!romFile.isFile) return null
            val bytes = romFile.readBytes()
            return UByteArray(bytes.size) { i -> bytes[i].toUByte() }
        }
    }

    private class MemoryResult(
        val resultCode: Int,
        val text: String,
    ) {
        val passed: Boolean get() = resultCode == 0
    }

    /** 1フレーム(70224 T-cycle)ぶん実行する。APUのフレーム内イベントログをリセットするため generateSamples() も呼ぶ。 */
    private fun runOneFrame(machine: Machine) {
        var accumulated = 0
        while (accumulated < CYCLES_PER_FRAME) {
            accumulated += machine.stepInstruction()
        }
        machine.sound.generateSamples()
    }

    private fun readNulTerminatedText(machine: Machine): String {
        val sb = StringBuilder()
        var addr = ADDR_TEXT_OUT_BASE
        val limit = ADDR_TEXT_OUT_BASE + 0x400
        while (addr < limit) {
            val b = machine.bus.readByte(addr.toUShort()).toInt() and 0xFF
            if (b == 0) break
            sb.append(b.toChar())
            addr++
        }
        return sb.toString()
    }

    /** シリアル方式(cpu_instrs / instr_timing)で ROM を実行し、蓄積された出力文字列を返す。 */
    private fun runSerialProtocolRom(relativePath: String): String {
        val rom = loadRom(relativePath)
        assumeTrue("ROM not found: $relativePath (test_file/ の配置を確認してください)", rom != null)

        val machine = Machine(rom!!, forceCartridgeRam = true)
        val output = StringBuilder()
        machine.bus.serialOutListener = { byte -> output.append((byte.toInt() and 0xFF).toChar()) }

        var frame = 0
        var settledFrames = 0
        while (frame < MAX_FRAMES) {
            runOneFrame(machine)
            frame++
            val text = output.toString()
            if (!text.contains("Passed") && !text.contains("Failed")) continue

            // "Passed"/"Failed" は検出したが、後続の文字(" all tests" 等)が
            // 次フレームにまたがって送信される場合があるため、数フレーム待って確定させる
            settledFrames++
            if (settledFrames >= SETTLE_FRAMES) {
                return text
            }
        }
        fail("Timed out after $MAX_FRAMES frames without result. Output so far:\n$output")
        error("unreachable")
    }

    /** メモリ方式($A000-$A004規約)で ROM を実行し、結果コードとメッセージを返す。 */
    private fun runMemoryProtocolRom(relativePath: String): MemoryResult {
        val rom = loadRom(relativePath)
        assumeTrue("ROM not found: $relativePath (test_file/ の配置を確認してください)", rom != null)

        val machine = Machine(rom!!, forceCartridgeRam = true)

        var frame = 0
        var settledFrames = 0
        while (frame < MAX_FRAMES) {
            runOneFrame(machine)
            frame++

            val sig1 = machine.bus.readByte(ADDR_SIG_1)
            val sig2 = machine.bus.readByte(ADDR_SIG_2)
            val sig3 = machine.bus.readByte(ADDR_SIG_3)
            val signatureValid =
                sig1 == 0xDEu.toUByte() && sig2 == 0xB0u.toUByte() && sig3 == 0x61u.toUByte()
            if (!signatureValid) continue

            val result = machine.bus.readByte(ADDR_FINAL_RESULT)
            if (result == 0x80u.toUByte()) {
                settledFrames = 0
                continue
            }

            // 0x80(実行中)以外になったら数フレーム待って値を確定させる
            settledFrames++
            if (settledFrames >= SETTLE_FRAMES) {
                return MemoryResult(resultCode = result.toInt() and 0xFF, text = readNulTerminatedText(machine))
            }
        }
        fail("Timed out after $MAX_FRAMES frames without result (\$A000 stayed at 0x80, or signature never appeared)")
        error("unreachable")
    }

    private fun assertMemoryProtocolPassed(relativePath: String) {
        val result = runMemoryProtocolRom(relativePath)
        assertTrue(
            "$relativePath failed (code=${result.resultCode}):\n${result.text}",
            result.passed,
        )
    }

    // ────────────────────────────────────────────────────────────────
    // シリアル方式: cpu_instrs / instr_timing
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `cpu_instrs passes all tests`() {
        val output = runSerialProtocolRom("cpu_instrs/cpu_instrs.gb")
        assertTrue("cpu_instrs failed:\n$output", output.contains("Passed all tests"))
    }

    @Test
    fun `instr_timing passes`() {
        val output = runSerialProtocolRom("instr_timing/instr_timing.gb")
        assertTrue("instr_timing failed:\n$output", output.contains("Passed"))
    }

    // ────────────────────────────────────────────────────────────────
    // メモリ方式: dmg_sound(rom_singles 単体 + 本体)
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `dmg_sound 01 registers passes`() {
        assertMemoryProtocolPassed("dmg_sound/rom_singles/01-registers.gb")
    }

    @Test
    @Ignore("Phase 1 で修正予定: Length 追加クロック条件の反転バグ")
    fun `dmg_sound 02 len ctr passes`() {
        assertMemoryProtocolPassed("dmg_sound/rom_singles/02-len ctr.gb")
    }

    @Test
    @Ignore("Phase 1 で修正予定: トリガー時 Length リロード quirk")
    fun `dmg_sound 03 trigger passes`() {
        assertMemoryProtocolPassed("dmg_sound/rom_singles/03-trigger.gb")
    }

    @Test
    @Ignore("Phase 1 で修正予定: スイープ全般")
    fun `dmg_sound 04 sweep passes`() {
        assertMemoryProtocolPassed("dmg_sound/rom_singles/04-sweep.gb")
    }

    @Test
    @Ignore("Phase 1 で修正予定: スイープ negate quirk")
    fun `dmg_sound 05 sweep details passes`() {
        assertMemoryProtocolPassed("dmg_sound/rom_singles/05-sweep details.gb")
    }

    @Test
    @Ignore("Phase 1 で修正予定: スイープトリガー時の即時オーバーフローチェック")
    fun `dmg_sound 06 overflow on trigger passes`() {
        assertMemoryProtocolPassed("dmg_sound/rom_singles/06-overflow on trigger.gb")
    }

    @Test
    @Ignore("Phase 1 で修正予定: 電源ON時フレームシーケンサ位相リセット")
    fun `dmg_sound 07 len sweep period sync passes`() {
        assertMemoryProtocolPassed("dmg_sound/rom_singles/07-len sweep period sync.gb")
    }

    @Test
    @Ignore("Phase 1 で修正予定: 電源OFF中の Length 書き込み許可")
    fun `dmg_sound 08 len ctr during power passes`() {
        assertMemoryProtocolPassed("dmg_sound/rom_singles/08-len ctr during power.gb")
    }

    @Test
    @Ignore("Phase 2 で修正予定: Wave RAM アクセス窓")
    fun `dmg_sound 09 wave read while on passes`() {
        assertMemoryProtocolPassed("dmg_sound/rom_singles/09-wave read while on.gb")
    }

    @Test
    @Ignore("Phase 2 で修正予定: Wave RAM トリガー破壊パターン")
    fun `dmg_sound 10 wave trigger while on passes`() {
        assertMemoryProtocolPassed("dmg_sound/rom_singles/10-wave trigger while on.gb")
    }

    @Test
    @Ignore("Phase 1 で修正予定: 電源OFF中の Length 書き込み許可")
    fun `dmg_sound 11 regs after power passes`() {
        assertMemoryProtocolPassed("dmg_sound/rom_singles/11-regs after power.gb")
    }

    @Test
    @Ignore("Phase 2 で修正予定: Wave RAM アクセス窓")
    fun `dmg_sound 12 wave write while on passes`() {
        assertMemoryProtocolPassed("dmg_sound/rom_singles/12-wave write while on.gb")
    }

    @Test
    @Ignore("Phase 1/2 完了後に全12テスト合格を確認")
    fun `dmg_sound full ROM passes all 12 tests`() {
        assertMemoryProtocolPassed("dmg_sound/dmg_sound.gb")
    }

    // ────────────────────────────────────────────────────────────────
    // メモリ方式: halt_bug
    // ────────────────────────────────────────────────────────────────

    @Test
    @Ignore("Phase 3 で修正予定: HALTバグ判定の 0x1F マスク漏れ")
    fun `halt_bug passes`() {
        assertMemoryProtocolPassed("halt_bug.gb")
    }

    // ────────────────────────────────────────────────────────────────
    // メモリ方式: oam_bug(挑戦枠、Phase 4)
    // ────────────────────────────────────────────────────────────────

    @Test
    @Ignore("Phase 4(挑戦枠): OAM破壊バグ未実装")
    fun `oam_bug full ROM passes all 8 tests`() {
        assertMemoryProtocolPassed("oam_bug/oam_bug.gb")
    }
}
