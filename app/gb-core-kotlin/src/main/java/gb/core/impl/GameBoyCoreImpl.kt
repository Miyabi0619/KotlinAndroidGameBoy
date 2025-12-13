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

        // Machine（CPU + SystemBus + Timer + Interrupts + MBC1）を初期化
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

                // HALT状態の場合、PPUを進めるために追加サイクルを渡す
                // 実機では、HALT状態でもPPUは動作し続ける
                // ただし、効率化のため、HALT状態が続く場合は一気にフレーム終了まで進める
                if (m.cpu.isHalted() && accumulatedCycles < targetCyclesPerFrame) {
                    val remainingCycles = targetCyclesPerFrame - accumulatedCycles
                    // 残りのサイクルを一気にPPUに渡す（割り込み待ちのため）
                    m.ppu.step(remainingCycles)
                    accumulatedCycles = targetCyclesPerFrame
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
                                        "Attempting to fix by injecting RETI (0xD9) at 0x38."
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
                                        "Stack top (return address): 0x${stackValue.toString(16)}"
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
                                "IF=0x$ifReg, IE=0x$ieReg",
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
            // 最適化: ログ出力をさらに削減（3000フレームごと = 約50秒ごと）
            if (frameIndex % 3000 == 1L) {
                // PCの値を確認（CPUが進んでいるか）
                val pc = m.cpu.registers.pc
                val ifReg = m.readIf()
                val ieReg = m.readIe()
                android.util.Log.d(
                    "GameBoyCore",
                    "Frame $frameIndex: cycles=$accumulatedCycles, instructions=$instructionCount, " +
                        "PC=0x${pc.toString(16)}, A=0x${m.cpu.registers.a.toString(16)}, " +
                        "IF=0x${ifReg.toString(16)}, IE=0x${ieReg.toString(16)}, " +
                        "IME=${m.cpu.isInterruptsEnabled()}, halted=${m.cpu.isHalted()}",
                )
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
