package gb.core.api

/**
 * コア API から返される結果型。
 *
 * Kotlin 標準の [Result] と似ているが、エラー側に [CoreError] を保持する。
 */
sealed class CoreResult<out T> {
    data class Success<T>(
        val value: T,
    ) : CoreResult<T>()

    data class Error(
        val error: CoreError,
    ) : CoreResult<Nothing>()

    companion object {
        fun <T> success(value: T): CoreResult<T> = Success(value)

        fun error(error: CoreError): CoreResult<Nothing> = Error(error)
    }
}

/**
 * コア内部で発生しうるエラー種別。
 */
sealed class CoreError {
    /**
     * ROM がまだロードされていない状態で `runFrame` などが呼ばれた場合。
     */
    data object RomNotLoaded : CoreError()

    /**
     * ROM ヘッダが不正、サイズが異常など、ROM 自体が無効な場合。
     */
    data class InvalidRom(
        val reason: String,
    ) : CoreError()

    /**
     * API の使用順序が不正な場合など、論理的な状態不整合。
     */
    data class IllegalState(
        val message: String,
    ) : CoreError()

    /**
     * 実装側で捕捉された予期しない例外。
     *
     * この型を UI にそのまま露出させるのではなく、ログ出力とユーザ向けエラー表示を行う。
     */
    data class InternalError(
        val cause: Throwable,
    ) : CoreError()
}
