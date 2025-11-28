package miyabi.kotlinandroidgameboy.emulator

import gb.core.api.CoreResult
import gb.core.api.FrameResult
import gb.core.api.GameBoyCore
import gb.core.api.InputState
import gb.core.api.SaveState

/**
 * GameBoy エミュレーションを 1 フレーム単位で制御するシンプルなループクラス。
 *
 * 現時点ではコルーチンやスレッド制御は行わず、呼び出し元が明示的に
 * [runSingleFrame] を呼び出すことで 1 フレームだけ進める。
 * 実際のゲームループは後続タスクで ViewModel / Coroutine と組み合わせて実装する。
 */
class GameLoop(
    private val core: GameBoyCore,
) {
    /**
     * ROM をロードしてコアをリセットするヘルパー。
     */
    fun loadRomAndReset(rom: ByteArray): CoreResult<Unit> {
        val loadResult = core.loadRom(rom)
        if (loadResult is CoreResult.Error) {
            return loadResult
        }
        return core.reset()
    }

    /**
     * 入力状態を渡して 1 フレームだけエミュレーションを進める。
     */
    fun runSingleFrame(input: InputState): CoreResult<FrameResult> {
        return core.runFrame(input)
    }

    /**
     * セーブステートを作成する。
     */
    fun saveState(): CoreResult<SaveState> = core.saveState()

    /**
     * セーブステートから状態を復元する。
     */
    fun loadState(state: SaveState): CoreResult<Unit> = core.loadState(state)
}
