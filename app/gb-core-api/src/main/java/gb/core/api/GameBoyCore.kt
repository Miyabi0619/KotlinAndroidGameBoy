package gb.core.api

/**
 * Game Boy エミュレータコアの公開インターフェース。
 *
 * このインターフェースは Android / UI に依存しない純粋な論理モデルを提供する。
 * 呼び出し元は、ROM 読み込みや画面描画、入力状態の更新などをこの API 越しに行う。
 */
interface GameBoyCore {
    /**
     * ROM データをロードする。
     *
     * @param rom 生の ROM バイト列（`.gb` ファイルの内容）。
     * @return 成否を表す [CoreResult]。ROM が不正な場合などは [CoreError.InvalidRom] を返す。
     */
    fun loadRom(rom: ByteArray): CoreResult<Unit>

    /**
     * コア内部状態を初期化する（電源 ON / リセット相当）。
     *
     * ROM 未ロードの状態で呼び出された場合の挙動は実装に委ねるが、
     * 少なくともクラッシュせず [CoreError.IllegalState] で通知されるべきである。
     */
    fun reset(): CoreResult<Unit>

    /**
     * 1 フレーム分エミュレーションを進める。
     *
     * 実装側は約 70224 サイクル（1 フレーム相当）を進めることを想定する。
     *
     * @param input 現在の入力状態（ボタンの押下情報）。
     * @return フレームバッファやデバッグ情報を含む [FrameResult]。
     */
    fun runFrame(input: InputState): CoreResult<FrameResult>

    /**
     * セーブステートを作成する。
     *
     * 初期実装では未サポートでもよく、その場合は [CoreError.IllegalState] を返す。
     */
    fun saveState(): CoreResult<SaveState>

    /**
     * セーブステートから状態を復元する。
     */
    fun loadState(state: SaveState): CoreResult<Unit>
}
