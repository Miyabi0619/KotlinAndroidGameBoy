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
            while (accumulatedCycles < targetCyclesPerFrame) {
                accumulatedCycles += m.stepInstruction()
                instructionCount++
            }

            frameIndex += 1

            val pixels = m.ppu.renderFrame()
            if (frameIndex % 60 == 1L) {
                android.util.Log.d(
                    "GameBoyCore",
                    "Frame $frameIndex: cycles=$accumulatedCycles, instructions=$instructionCount, pixels size=${pixels.size}",
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
