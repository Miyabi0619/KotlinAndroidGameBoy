package gb.core.impl

import gb.core.api.CoreError
import gb.core.api.CoreResult
import gb.core.api.FrameResult
import gb.core.api.FrameStats
import gb.core.api.GameBoyCore
import gb.core.api.InputState
import gb.core.api.SaveState

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
class GameBoyCoreImpl : GameBoyCore {
    private var rom: ByteArray? = null
    private var frameIndex: Long = 0

    override fun loadRom(rom: ByteArray): CoreResult<Unit> {
        if (rom.isEmpty()) {
            return CoreResult.error(
                CoreError.InvalidRom(reason = "ROM data is empty."),
            )
        }

        this.rom = rom.copyOf()
        frameIndex = 0
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
        // 実際の実装では CPU レジスタやメモリなどを電源 ON 状態に初期化する。
        return CoreResult.success(Unit)
    }

    override fun runFrame(input: InputState): CoreResult<FrameResult> {
        val currentRom = rom
        if (currentRom == null) {
            return CoreResult.error(
                CoreError.RomNotLoaded,
            )
        }

        frameIndex += 1

        val pixels = createStubFrameBuffer()
        val stats =
            FrameStats(
                frameIndex = frameIndex,
                cpuCycles = 0L,
                fpsEstimate = null,
            )

        return CoreResult.success(
            FrameResult(
                frameBuffer = pixels,
                stats = stats,
            ),
        )
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

    /**
     * 仮のフレームバッファを生成する。
     *
     * 現状は単色（黒）の画面を返すのみで、PPU の実装が整い次第置き換える。
     */
    private fun createStubFrameBuffer(): IntArray {
        val width = 160
        val height = 144
        val pixelCount = width * height
        val blackArgb = 0xFF000000.toInt()

        return IntArray(pixelCount) { blackArgb }
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
