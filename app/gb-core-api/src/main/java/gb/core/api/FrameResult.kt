package gb.core.api

/**
 * 1 フレーム分のエミュレーション結果。
 *
 * 描画用のフレームバッファと、任意のデバッグ情報を含む。
 */
data class FrameResult(
    /**
     * 画面フレームバッファ。
     *
     * - 160x144 ピクセルを ARGB8888（1 ピクセル 1 Int）として保持することを想定する。
     * - インデックス [0] が左上、以降は行優先（row-major）で格納する。
     */
    val frameBuffer: IntArray,
    /**
     * オーディオサンプル。
     *
     * - 1フレーム分のオーディオサンプル（ステレオ形式、左右交互、約735サンプル×2、44.1kHzの場合）
     * - 16bit PCM形式（Short配列、ステレオ形式：左、右、左、右...）
     * - nullの場合はオーディオ出力なし
     */
    val audioSamples: ShortArray? = null,
    /**
     * オプションのデバッグ情報。
     */
    val stats: FrameStats? = null,
)

/**
 * デバッグやメトリクス収集向けのフレーム統計情報。
 */
data class FrameStats(
    val frameIndex: Long,
    val cpuCycles: Long,
    val fpsEstimate: Double? = null,
)
