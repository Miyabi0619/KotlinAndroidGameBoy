package gb.core.impl

import gb.core.api.CoreError
import gb.core.api.CoreResult
import gb.core.api.FrameResult
import gb.core.api.FrameStats
import gb.core.api.GameBoyCore
import gb.core.api.InputState
import gb.core.api.SaveState
import gb.core.impl.cpu.Machine
import kotlin.ExperimentalUnsignedTypes

/**
 * [GameBoyCore] の最低限の骨格実装。
 *
 * 現時点では Game Boy の実際の動作は一切再現せず、
 * - ROM のロード状態の管理
 * - フレームインデックスのインクリメント
 * - 固定色のフレームバッファ生成
 *
 * のみを行うスタブ実装である。
 * 後続タスクで CPU / PPU / メモリなどの詳細ロジックを追加していく。
 */
@OptIn(ExperimentalUnsignedTypes::class)
class GameBoyCoreImpl : GameBoyCore {
    private var rom: ByteArray? = null
    private var frameIndex: Long = 0
    private var machine: Machine? = null

    override fun loadRom(rom: ByteArray): CoreResult<Unit> {
        if (rom.isEmpty()) {
            return CoreResult.error(
                CoreError.InvalidRom(reason = "ROM data is empty."),
            )
        }

        val romCopy = rom.copyOf()
        this.rom = romCopy
        frameIndex = 0

        // カートリッジヘッダをログ出力（診断用）
        val cartType = rom.getOrNull(0x0147)?.toInt() ?: 0
        val romSizeCode = rom.getOrNull(0x0148)?.toInt() ?: 0
        val ramSizeCode = rom.getOrNull(0x0149)?.toInt() ?: 0
        val title =
            buildString {
                for (i in 0x0134..0x0143) {
                    val c = rom.getOrNull(i)?.toInt()?.and(0xFF) ?: 0
                    if (c == 0) break
                    append(c.toChar())
                }
            }
        val supportedTypes = listOf(0x00, 0x01, 0x02, 0x03, 0x0F, 0x10, 0x11, 0x12, 0x13, 0x19, 0x1A, 0x1B, 0x1C, 0x1D, 0x1E)
        val supported = cartType in supportedTypes
        android.util.Log.w(
            "GameBoyCore",
            "ROM loaded: title='$title' size=${rom.size}B " +
                "cartType=0x${cartType.toString(16)} " +
                "romSizeCode=0x${romSizeCode.toString(16)} " +
                "ramSizeCode=0x${ramSizeCode.toString(16)} " +
                "supported=$supported",
        )

        // Machine（CPU + SystemBus + Timer + Interrupts + MBC）を初期化
        val uRom = romCopy.toUByteArray()
        machine = Machine(uRom)

        return CoreResult.success(Unit)
    }

    override fun reset(): CoreResult<Unit> {
        val currentRom = rom
        if (currentRom == null) {
            return CoreResult.error(
                CoreError.RomNotLoaded,
            )
        }

        frameIndex = 0

        // ROM から Machine を作り直す（電源 OFF→ON 相当）
        val uRom = currentRom.toUByteArray()
        machine = Machine(uRom)

        return CoreResult.success(Unit)
    }

    override fun runFrame(input: InputState): CoreResult<FrameResult> {
        val currentRom = rom
        if (currentRom == null) {
            return CoreResult.error(
                CoreError.RomNotLoaded,
            )
        }

        return try {
            // Machine がまだなければ ROM から初期化
            if (machine == null) {
                android.util.Log.d("GameBoyCore", "Creating new Machine")
                machine = Machine(currentRom.toUByteArray())
            }
            val m = machine!!

            // このフレームの入力状態を Joypad に反映
            m.updateInput(input)

            // 1 フレームぶんの CPU サイクルをざっくり回す
            // Game Boy は約 70224 サイクル / フレーム（59.7Hz）なので、それに近い値を使う。
            val targetCyclesPerFrame = 70_224
            var accumulatedCycles = 0
            var instructionCount = 0
            var lastPc = m.cpu.registers.pc
            var pcStuckCount = 0
            while (accumulatedCycles < targetCyclesPerFrame) {
                val cycles = m.stepInstruction()
                accumulatedCycles += cycles
                instructionCount++

                // HALT状態の場合、全コンポーネントを同期して進める
                // 実機では、HALT中もPPU/Timer/Soundは動作し続ける
                // 割り込み発生でHALTから復帰するため、小刻みにステップを進める
                if (m.cpu.isHalted() && accumulatedCycles < targetCyclesPerFrame) {
                    val remainingCycles = targetCyclesPerFrame - accumulatedCycles
                    // stepInstructionが全コンポーネントを4サイクルずつ進めるので、
                    // そのままループを継続する（HALT中はCPU NOP + 全コンポーネントstep）
                    // ただし効率化のため、大量の残りサイクルがある場合はチャンク単位で処理
                    if (remainingCycles > 456) {
                        // 1スキャンライン(456 T-cycles)ずつ処理して割り込みチェック
                        // これによりVBlank/Timer割り込みでHALTから復帰できる
                        val chunk = 456
                        val steps = remainingCycles / chunk
                        repeat(steps) {
                            if (!m.cpu.isHalted()) return@repeat
                            val c = m.stepInstruction()
                            accumulatedCycles += c
                            instructionCount++
                        }
                    }
                    // 残りが少ない場合は通常ループに任せる
                }

                // PCが進んでいるか確認（無限ループ検出）
                val currentPc = m.cpu.registers.pc
                if (currentPc == lastPc) {
                    pcStuckCount++

                    // 0x38（RST 38H）でスタックしている場合は、割り込みハンドラの問題の可能性
                    // この場合は、0x38のアドレスに何があるかを確認する必要がある
                    if (currentPc == 0x38u.toUShort() && pcStuckCount == 101) {
                        try {
                            // SystemBus経由でメモリを読み取る
                            val opcodeAt38 = m.bus.readByte(0x38u.toUShort())
                            val sp = m.cpu.registers.sp
                            val stackLow = m.bus.readByte(sp)
                            val stackHigh = m.bus.readByte((sp.toInt() + 1).toUShort())
                            val stackValue = (stackHigh.toInt() shl 8) or stackLow.toInt()

                            // 0x38に0xFF（RST 38H）がある場合は、無限ループになっている
                            // この場合は、0x38にRETI命令（0xD9）を配置する必要がある
                            if (opcodeAt38 == 0xFFu.toUByte()) {
                                android.util.Log.e(
                                    "GameBoyCore",
                                    "PC stuck at 0x38: Infinite loop detected! " +
                                        "Opcode at 0x38 is 0xFF (RST 38H), which causes infinite loop. " +
                                        "SP=0x${sp.toString(16)}, " +
                                        "Stack top (return address): 0x${stackValue.toString(16)}. " +
                                        "This ROM may have invalid interrupt handler at 0x38. " +
                                        "Attempting to fix by injecting RETI (0xD9) at 0x38.",
                                )
                                // 0x38にRETI命令（0xD9）を書き込む（一時的な修正）
                                // 注意: これはROM領域なので、実際には書き込めないはずだが、
                                // エミュレータの実装によっては可能な場合がある
                                // 実際のGame Boyでは、0x38のアドレスはROM領域なので書き込めない
                                // しかし、エミュレータでは、ROM領域への書き込みを無視するか、
                                // または特別な処理を行う必要がある
                            } else {
                                android.util.Log.e(
                                    "GameBoyCore",
                                    "PC stuck at 0x38 (RST 38H interrupt handler). " +
                                        "Opcode at 0x38: 0x${opcodeAt38.toString(16)}, " +
                                        "SP=0x${sp.toString(16)}, " +
                                        "Stack top (return address): 0x${stackValue.toString(16)}",
                                )
                            }
                        } catch (_: RuntimeException) {
                            // テスト環境では無視
                        }
                    }
                    if (pcStuckCount == 101) {
                        // 最初の検出時に詳細な状態をログ出力
                        // IF/IEレジスタの状態も確認
                        val ifReg = m.readIf().toString(16)
                        val ieReg = m.readIe().toString(16)
                        val statReg = m.ppu.readRegister(0x01).toString(16)
                        val lycReg = m.ppu.readRegister(0x05).toString(16)
                        val lyReg = m.ppu.readRegister(0x04).toString(16)
                        android.util.Log.e(
                            "GameBoyCore",
                            "PC stuck at 0x${currentPc.toString(16)} for $pcStuckCount instructions. " +
                                "A=0x${m.cpu.registers.a.toString(16)}, " +
                                "B=0x${m.cpu.registers.b.toString(16)}, " +
                                "C=0x${m.cpu.registers.c.toString(16)}, " +
                                "D=0x${m.cpu.registers.d.toString(16)}, " +
                                "E=0x${m.cpu.registers.e.toString(16)}, " +
                                "H=0x${m.cpu.registers.h.toString(16)}, " +
                                "L=0x${m.cpu.registers.l.toString(16)}, " +
                                "SP=0x${m.cpu.registers.sp.toString(16)}, " +
                                "Z=${m.cpu.registers.flagZ}, C=${m.cpu.registers.flagC}, " +
                                "halted=${m.cpu.isHalted()}, IME=${m.cpu.isInterruptsEnabled()}, " +
                                "IF=0x$ifReg, IE=0x$ieReg, " +
                                "STAT=0x$statReg, LYC=0x$lycReg, LY=0x$lyReg",
                        )
                    }
                    // HALT状態の場合は、割り込み待ちの可能性があるため、ループを続行
                    // ただし、HALT状態でない場合は無限ループの可能性があるため、警告のみ
                    if (pcStuckCount > 1000 && !m.cpu.isHalted()) {
                        android.util.Log.e(
                            "GameBoyCore",
                            "Breaking infinite loop: PC stuck for $pcStuckCount instructions (not HALT)",
                        )
                        break
                    }
                } else {
                    pcStuckCount = 0
                    lastPc = currentPc
                }
            }

            frameIndex += 1

            val pixels = m.ppu.renderFrame()
            val audioSamples = m.sound.generateSamples()
            // 診断ログ: 60フレームごと（約1秒ごと）
            if (frameIndex % 60 == 1L) {
                val pc = m.cpu.registers.pc
                val ifReg = m.readIf()
                val ieReg = m.readIe()
                val lcdc = m.ppu.readRegister(0x00)
                val ly = m.ppu.readRegister(0x04)
                val stat = m.ppu.readRegister(0x01)
                val lyc = m.ppu.readRegister(0x05)
                // フレームに非白・非黒以外のピクセルが含まれるか確認
                val nonTrivialPixels = pixels.count { it != 0xFFFFFFFF.toInt() && it != 0xFF000000.toInt() }
                // サウンド診断: NR52/NR50/NR51 とサンプル最大振幅
                val nr52 = m.sound.readRegister(0x16)
                val nr50 = m.sound.readRegister(0x14)
                val nr51 = m.sound.readRegister(0x15)
                val maxAmp = audioSamples.maxOfOrNull { kotlin.math.abs(it.toInt()) } ?: 0
                android.util.Log.d(
                    "GameBoyCore",
                    "Frame $frameIndex: cycles=$accumulatedCycles inst=$instructionCount " +
                        "PC=0x${pc.toString(16)} LCDC=0x${lcdc.toString(16)} LY=$ly " +
                        "STAT=0x${stat.toString(16)} LYC=$lyc " +
                        "IF=0x${ifReg.toString(16)} IE=0x${ieReg.toString(16)} " +
                        "IME=${m.cpu.isInterruptsEnabled()} halt=${m.cpu.isHalted()} " +
                        "grayPx=$nonTrivialPixels " +
                        "NR52=0x${nr52.toString(16)} NR50=0x${nr50.toString(16)} NR51=0x${nr51.toString(16)} maxAmp=$maxAmp",
                )
                android.util.Log.d("SoundDbg", m.sound.getDebugState())
            }

            val stats =
                FrameStats(
                    frameIndex = frameIndex,
                    cpuCycles = accumulatedCycles.toLong(),
                    fpsEstimate = null,
                )

            CoreResult.success(
                FrameResult(
                    frameBuffer = pixels,
                    audioSamples = audioSamples,
                    stats = stats,
                ),
            )
        } catch (e: Exception) {
            // デバッグ用: ログにスタックトレースを出力
            android.util.Log.e(
                "GameBoyCore",
                "Exception in runFrame: ${e.message}",
                e,
            )
            CoreResult.error(
                CoreError.InternalError(
                    cause = e,
                ),
            )
        }
    }

    /**
     * バッテリバックアップ付きカートリッジかどうかを返す。
     */
    fun hasBattery(): Boolean = machine?.hasBattery ?: false

    /**
     * カートリッジRAMの内容を取得する（.savファイル保存用）。
     * バッテリバックアップ付きカートリッジでない場合はnullを返す。
     */
    fun getCartridgeRam(): ByteArray? {
        val m = machine ?: return null
        return if (m.hasBattery) m.getCartridgeRam() else null
    }

    /**
     * カートリッジRAMにデータをロードする（.savファイル復元用）。
     */
    fun loadCartridgeRam(data: ByteArray) {
        machine?.loadCartridgeRam(data)
    }

    override fun saveState(): CoreResult<SaveState> {
        return CoreResult.error(
            CoreError.IllegalState(
                message = "saveState is not implemented yet.",
            ),
        )
    }

    override fun loadState(state: SaveState): CoreResult<Unit> {
        return CoreResult.error(
            CoreError.IllegalState(
                message = "loadState is not implemented yet.",
            ),
        )
    }
}

/**
 * 将来的な DI を見据えた簡易ファクトリ。
 *
 * app モジュールは基本的にこのファクトリ経由で [GameBoyCore] を取得する。
 */
object GameBoyCoreFactory {
    fun create(): GameBoyCore = GameBoyCoreImpl()
}
