package miyabi.kotlinandroidgameboy.emulator

import gb.core.api.GameBoyCore
import gb.core.impl.GameBoyCoreFactory

/**
 * GameBoy コアインスタンスを提供するエントリポイント。
 *
 * 現時点では単純に [GameBoyCoreFactory] から新しいインスタンスを生成するだけだが、
 * 将来的に DI フレームワークを導入する場合も、このクラスを差し替えることで対応する。
 */
object CoreProvider {
    fun provideCore(): GameBoyCore = GameBoyCoreFactory.create()
}
