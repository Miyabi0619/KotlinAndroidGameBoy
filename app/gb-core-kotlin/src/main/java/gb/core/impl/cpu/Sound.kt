package gb.core.impl.cpu

import kotlin.ExperimentalUnsignedTypes

/**
 * Game Boy サウンド処理（APU: Audio Processing Unit）
 *
 * - 4つのサウンドチャンネルを管理
 *   - Square 1 (0xFF10-0xFF14)
 *   - Square 2 (0xFF15-0xFF19)
 *   - Wave (0xFF1A-0xFF1E)
 *   - Noise (0xFF1F-0xFF23)
 * - サウンド制御レジスタ (0xFF24-0xFF26)
 * - 波形RAM (0xFF30-0xFF3F)
 */
@OptIn(ExperimentalUnsignedTypes::class)
class Sound {
    companion object {
        // サウンドレジスタのアドレス範囲
        const val SOUND_START = 0xFF10
        const val SOUND_END = 0xFF3F

        // チャンネル別のレジスタ範囲
        const val SQUARE1_START = 0xFF10
        const val SQUARE1_END = 0xFF14
        const val SQUARE2_START = 0xFF16
        const val SQUARE2_END = 0xFF19
        const val WAVE_START = 0xFF1A
        const val WAVE_END = 0xFF1E
        const val NOISE_START = 0xFF20
        const val NOISE_END = 0xFF23
        const val CONTROL_START = 0xFF24
        const val CONTROL_END = 0xFF26
        const val WAVE_RAM_START = 0xFF30
        const val WAVE_RAM_END = 0xFF3F

        // CPU周波数（4.194304 MHz）
        const val CPU_FREQUENCY = 4_194_304

        // サウンド周波数（CPU周波数 / 8）
        const val SOUND_FREQUENCY = CPU_FREQUENCY / 8

        // サンプリングレート（Game Boyは約44.1kHz相当）
        const val SAMPLE_RATE = 44100

        // Game Boyの実際のフレームレート（70224 CPUサイクル/フレーム）
        // フレームレート = CPU_FREQUENCY / 70224 ≈ 59.7275 Hz
        const val FRAME_RATE = CPU_FREQUENCY / 70224.0

        // 1フレームあたりのサンプル数（正確な計算）
        // 1フレーム = 70224 CPUサイクル = 70224 / 8 = 8778 サウンドサイクル
        // 1サンプル = 524288 / 44100 ≈ 11.89 サウンドサイクル
        // 1フレームあたりのサンプル数 = 8778 / 11.89 ≈ 738.2
        const val SAMPLES_PER_FRAME = ((SAMPLE_RATE / FRAME_RATE) + 0.5).toInt()

        // 各レジスタの読み取り時ORマスク（未使用ビットは1を返す）
        // Pan Docs: https://gbdev.io/pandocs/Audio_Registers.html
        // Index = offset from 0xFF10
        // NR10,NR11,NR12,NR13,NR14, unused, NR21,NR22,NR23,NR24,
        // NR30,NR31,NR32,NR33,NR34, unused, NR41,NR42,NR43,NR44, NR50,NR51
        // NR52 (offset 0x16) is handled separately in readRegister
        private val REGISTER_READ_MASKS =
            intArrayOf(
                0x80, 0x3F, 0x00, 0xFF, 0xBF,
                0xFF,
                0x3F, 0x00, 0xFF, 0xBF,
                0x7F, 0xFF, 0x9F, 0xFF, 0xBF,
                0xFF,
                0xFF, 0x00, 0x00, 0xBF,
                0x00, 0x00,
            )
    }

    // サウンドレジスタ（0xFF10-0xFF3F）
    // 初期値: DMG のブートROM 終了後のAPUレジスタ値（Pan Docs 参照）
    // NR10=$80, NR11=$BF, NR12=$F3, NR14=$BF,
    // NR21=$3F, NR24=$BF, NR30=$7F, NR31=$FF, NR32=$9F, NR34=$BF,
    // NR41=$FF, NR44=$BF, NR50=$77, NR51=$F3, NR52=$F1
    private val soundRegs: UByteArray =
        UByteArray(0x30).also { regs ->
            regs[0x00] = 0x80u // NR10
            regs[0x01] = 0xBFu // NR11
            regs[0x02] = 0xF3u // NR12
            regs[0x04] = 0xBFu // NR14
            regs[0x06] = 0x3Fu // NR21
            regs[0x09] = 0xBFu // NR24
            regs[0x0A] = 0x7Fu // NR30
            regs[0x0B] = 0xFFu // NR31
            regs[0x0C] = 0x9Fu // NR32
            regs[0x0D] = 0xFBu // NR33
            regs[0x0E] = 0xBFu // NR34
            regs[0x10] = 0xFFu // NR41
            regs[0x13] = 0xBFu // NR44
            regs[0x14] = 0x77u // NR50: 左右ともに最大音量7
            regs[0x15] = 0xF3u // NR51: 各チャンネルの出力ルーティング
            regs[0x16] = 0xF1u // NR52: サウンド有効、Square1 アクティブ
        }

    // 波形RAM（0xFF30-0xFF3F、Waveチャンネル用）
    private val waveRam: UByteArray = UByteArray(0x10) { 0u }

    // サウンド生成用の内部状態
    // 半サウンドサイクル単位（CPUサイクル/4）で整数管理
    // CPUサイクルは常に4の倍数なので、/4で常に整数になる
    // これにより浮動小数点の精度劣化を完全に回避
    private var halfSoundCycleCounter: Long = 0L
    private var frameStartHalfSoundCycle: Long = 0L

    // 1-bit PCM サポート: フレーム内の NR50/NR51 書き込みをタイムスタンプ付きで記録する。
    // ポケモンのピカチュウの鳴き声など、CPU がマスターボリューム(NR50)を高速切り替えして
    // デジタル音声を再現する手法に対応するため、サンプル単位で書き込みを適用する。
    //
    // タイムスタンプは「フレーム先頭からの経過半サウンドサイクル数」で記録する。
    // 絶対カウンタ (halfSoundCycleCounter) を使うと割り込みオーバーヘッド等で
    // フレームをまたぐ drift が蓄積し、書き込みが全サンプルで無視される不具合が生じる。
    private val pcmWriteRelHalfCycles = LongArray(512) // フレーム先頭からの経過半サウンドサイクル
    private val pcmWriteOffsets = IntArray(512) // レジスタオフセット (0x14=NR50 / 0x15=NR51)
    private val pcmWriteValues = UByteArray(512) // 書き込まれた値
    private var pcmWriteCount = 0 // 今フレームの書き込み数
    private var frameHalfCycles: Long = 0L // フレーム内経過半サウンドサイクル（毎フレームリセット）

    // スキャンラインPCM: CH1/CH2 のトリガーイベントをタイムスタンプ付きで記録する。
    // ゲームが毎スキャンライン NR12(音量) + NR14(トリガー) を書き込む手法で
    // 9.2kHz 相当の PCM オーディオを再現する。
    // フレーム終了時の単一音量値ではなく、サンプル単位で音量を更新することで正確な再生を実現する。
    private val sq1TriggerRelHalfCycles = LongArray(512)
    private val sq1TriggerVolumes = IntArray(512)
    private var sq1TriggerCount = 0
    private val sq2TriggerRelHalfCycles = LongArray(512)
    private val sq2TriggerVolumes = IntArray(512)
    private var sq2TriggerCount = 0

    // フレームシーケンサ（512Hz = SOUND_FREQUENCY / 1024 = 8192 Hz / 16）
    // 8ステップ（0-7）で Length / Sweep / Envelope を更新する
    // 実機ではDIVレジスタのbit 12（またはbit 5から見たbit 4）の立ち下がりエッジで更新
    private var frameSequencerStep: Int = 0
    private var lastDivBit12: Boolean = false // DIVのbit 12の前回の状態（エッジ検出用）

    // チャンネルの内部状態
    private val square1State = SquareChannelState()
    private val square2State = SquareChannelState()
    private val waveState = WaveChannelState()
    private val noiseState = NoiseChannelState()

    // Square 1のスイープ状態
    private var sweepEnabled = false
    private var sweepPeriod = 0
    private var sweepCounter = 0
    private var sweepNegate = false
    private var sweepShift = 0
    private var shadowFrequency = 0
    private var sweepInitialized = false // スイープの初期化フラグ（最初の更新を遅延させるため）

    /**
     * サウンドレジスタの読み取り。
     *
     * @param offset 0xFF10からのオフセット（0-0x2F）
     */
    fun readRegister(offset: Int): UByte {
        if (offset < 0 || offset >= 0x30) {
            return 0xFFu
        }

        // 波形RAM（0xFF30-0xFF3F）の読み取り
        if (offset >= 0x20) {
            // 実機の仕様: Waveチャンネルが有効で再生中の場合、波形RAMの読み取りは制限される
            // 実機では、Waveチャンネルが再生中の場合、波形RAMの読み取りは現在再生中のサンプル位置の値が返される
            // より正確には、波形RAMの読み取りは、現在再生中のサンプル位置に基づいて制限される
            if (waveState.enabled) {
                // Waveチャンネルが再生中の場合、波形RAMの読み取りは現在再生中のサンプル位置の値が返される
                // 実機の動作: 波形RAMの読み取りは、現在再生中のサンプル位置（32サンプル中）に基づいて制限される
                // 実機では、波形RAMの読み取りは、現在再生中のサンプル位置のバイトが返される
                // より正確な実装のため、現在再生中のサンプル位置を計算
                val nr33 = soundRegs[0x0D] // NR33 (Frequency Low)
                val nr34 = soundRegs[0x0E] // NR34 (Frequency High & Trigger)
                val frequency = ((nr34.toInt() and 0x07) shl 8) or nr33.toInt()

                if (frequency > 0 && frequency < 2048) {
                    // 周期を計算（Waveチャンネル本体と同じ *8.0 を使用）
                    val period = (2048.0 - frequency) * 8.0
                    if (period > 0) {
                        // 現在再生中のサンプル位置を計算（32サンプル中）
                        // 半サウンドサイクルカウンタから現在の位置を計算
                        val soundCycle = halfSoundCycleCounter / 2.0
                        val position = (soundCycle % period) * 32.0 / period
                        val sampleIndex = position.toInt().coerceIn(0, 31)

                        // 現在再生中のサンプル位置のバイトインデックス
                        val currentByteIndex = (sampleIndex / 2).coerceIn(0, 15)

                        // 実機の動作: 波形RAMの読み取りは、現在再生中のサンプル位置のバイトが返される
                        // より正確には、読み取りオフセットが現在再生中のサンプル位置と一致する場合、その値を返す
                        // 実機では、波形RAMの読み取りは、現在再生中のサンプル位置のバイトが返される
                        return waveRam[currentByteIndex]
                    }
                }
                // 周波数が無効な場合、通常の読み取りを返す
                return waveRam[offset - 0x20]
            } else {
                // Waveチャンネルが無効な場合、通常の読み取りを返す
                return waveRam[offset - 0x20]
            }
        }

        // NR52 (Sound Control) の読み取り時、各チャンネルの有効状態を反映
        if (offset == 0x16) {
            var result = 0x70 // bits 4-6 are always 1 on read
            if ((soundRegs[0x16].toInt() and 0x80) != 0) result = result or 0x80
            if (square1State.enabled) result = result or 0x01
            if (square2State.enabled) result = result or 0x02
            if (waveState.enabled) result = result or 0x04
            if (noiseState.enabled) result = result or 0x08
            return result.toUByte()
        }

        // 読み取り不可ビットは1を返す（実機仕様: Pan Docs準拠）
        // オフセット → ORマスク
        val readMask = REGISTER_READ_MASKS.getOrElse(offset) { 0xFF }
        return (soundRegs[offset].toInt() or readMask).toUByte()
    }

    /**
     * サウンドレジスタの書き込み。
     *
     * @param offset 0xFF10からのオフセット（0-0x2F）
     * @param value 書き込む値
     */
    fun writeRegister(
        offset: Int,
        value: UByte,
    ) {
        if (offset < 0 || offset >= 0x30) {
            return
        }

        // 波形RAM（0xFF30-0xFF3F）の書き込み
        if (offset >= 0x20) {
            // 実機の厳密な仕様では Wave チャンネル再生中は書き込み先バイトが限定されるが、
            // CPU が wave RAM を高速更新する 4-bit PCM ストリーミング（ポケモンの鳴き声等）の
            // 再現には常時書き込みを許可する必要がある。
            waveRam[offset - 0x20] = value
            return
        }

        // 読み取り専用レジスタのチェック
        // 0xFF26 (NR52) の bit 7 が 0 の場合、一部のレジスタは書き込み不可
        val nr52 = soundRegs[0x16] // 0xFF26 - 0xFF10 = 0x16
        val soundEnabled = (nr52.toInt() and 0x80) != 0

        // NR52[7]=0の場合、チャンネルレジスタへの書き込みを無視
        // ただし、NR52自体と波形RAMは書き込み可能
        if (!soundEnabled && offset != 0x16 && offset < 0x20) {
            // チャンネルレジスタへの書き込みを無視
            return
        }

        // 読み取り専用ビットの処理
        when (offset) {
            // NRx4レジスタ（NR14/NR24/NR34/NR44）は全ビット書き込み可能
            // bit 7 = trigger（書き込み専用、読み取り時は常に1）
            // bit 6 = length enable（読み書き可能）
            // bit 0-2 = frequency high / NR44では未使用
            // 読み取り時のマスクはREGISTER_READ_MASKSで処理する

            0x16, // NR52 (Sound Control)
            -> {
                // bit 0-6 は読み取り専用、bit 7 のみ書き込み可能
                val currentValue = soundRegs[offset]
                val newValue = (value and 0x80u.toUByte()) or (currentValue and 0x7Fu.toUByte())
                soundRegs[offset] = newValue

                // NR52[7]=0に書き込むと全チャンネルを無効化し、全レジスタをクリア（実機仕様）
                val soundEnabled = (newValue.toInt() and 0x80) != 0
                if (!soundEnabled) {
                    // チャンネルを無効化
                    square1State.enabled = false
                    square2State.enabled = false
                    waveState.enabled = false
                    noiseState.enabled = false
                    sweepEnabled = false

                    // 全サウンドレジスタをクリア（NR52以外）
                    for (i in 0x00..0x15) {
                        soundRegs[i] = 0u
                    }
                    // Wave RAMはクリアしない（実機仕様）
                }
            }

            else -> {
                soundRegs[offset] = value
            }
        }

        // レジスタ書き込み時のチャンネル状態更新
        when (offset) {
            0x01 -> { // NR11 (Square 1 Length & Duty)
                square1State.lengthCounter = 64 - (value.toInt() and 0x3F)
            }

            0x02 -> { // NR12 (Square 1 Volume & Envelope)
                square1State.envelopeVolume = (value.toInt() shr 4) and 0x0F
                square1State.envelopePeriod = value.toInt() and 0x07
                square1State.envelopeDirection = if ((value.toInt() and 0x08) != 0) 1 else -1
                square1State.envelopeCounter = 0
                // DAC無効化チェック: NR12の上位5ビットが全て0の場合、DACが無効化される（実機仕様）
                if ((value.toInt() and 0xF8) == 0) {
                    square1State.enabled = false
                }
            }

            0x04 -> { // NR14 (Square 1 Frequency High & Trigger)
                // Length enableはトリガーに関係なく常に更新（実機仕様）
                square1State.lengthEnabled = (value.toInt() and 0x40) != 0
                if ((value.toInt() and 0x80) != 0) {
                    // トリガー時、NR52[7]=0の場合はチャンネルを有効化しない（実機仕様）
                    val nr52 = soundRegs[0x16]
                    val soundEnabled = (nr52.toInt() and 0x80) != 0
                    if (!soundEnabled) {
                        return // サウンドが無効な場合、トリガーを無視
                    }

                    // DAC無効化チェック: NR12の上位5ビットが全て0の場合、トリガーを無視（実機仕様）
                    val nr12 = soundRegs[0x02]
                    if ((nr12.toInt() and 0xF8) == 0) {
                        return // DACが無効な場合、トリガーを無視
                    }

                    // チャンネルを有効化
                    square1State.enabled = true
                    // エンベロープの初期化（Trigger時にリセット）
                    square1State.envelopeVolume = (nr12.toInt() shr 4) and 0x0F
                    // エンベロープカウンタをピリオド値で初期化（実機仕様）
                    val envelopePeriod = nr12.toInt() and 0x07
                    square1State.envelopeCounter = if (envelopePeriod == 0) 8 else envelopePeriod
                    // 長さカウンタが0の場合、最大値（64）にリセット
                    if (square1State.lengthCounter == 0) {
                        square1State.lengthCounter = 64
                    }
                    // 長さカウンタの累積器をリセット
                    square1State.lengthCounterAccumulator = 0
                    // 位相をリセット（Trigger時は波形の先頭から再生）
                    square1State.phaseAccumulator = 0.0
                    // スキャンラインPCM: トリガーイベントをフレーム内タイムスタンプ付きで記録
                    if (sq1TriggerCount < sq1TriggerRelHalfCycles.size) {
                        sq1TriggerRelHalfCycles[sq1TriggerCount] = frameHalfCycles
                        sq1TriggerVolumes[sq1TriggerCount] = square1State.envelopeVolume
                        sq1TriggerCount++
                    }
                    // スイープの初期化
                    val nr10 = soundRegs[0x00]
                    sweepPeriod = ((nr10.toInt() shr 4) and 0x07)
                    sweepShift = nr10.toInt() and 0x07
                    sweepNegate = (nr10.toInt() and 0x08) != 0
                    sweepEnabled = sweepPeriod > 0 || sweepShift > 0
                    // トリガー時にsweepCounterをピリオド値で初期化（実機仕様）
                    sweepCounter = if (sweepPeriod == 0) 8 else sweepPeriod
                    val frequency = ((value.toInt() and 0x07) shl 8) or soundRegs[0x03].toInt()
                    shadowFrequency = frequency
                    sweepInitialized = false
                }
            }

            0x06 -> { // NR21 (Square 2 Length & Duty)
                square2State.lengthCounter = 64 - (value.toInt() and 0x3F)
            }

            0x07 -> { // NR22 (Square 2 Volume & Envelope)
                square2State.envelopeVolume = (value.toInt() shr 4) and 0x0F
                square2State.envelopePeriod = value.toInt() and 0x07
                square2State.envelopeDirection = if ((value.toInt() and 0x08) != 0) 1 else -1
                square2State.envelopeCounter = 0
                // DAC無効化チェック: NR22の上位5ビットが全て0の場合、DACが無効化される（実機仕様）
                if ((value.toInt() and 0xF8) == 0) {
                    square2State.enabled = false
                }
            }

            0x09 -> { // NR24 (Square 2 Frequency High & Trigger)
                // Length enableはトリガーに関係なく常に更新（実機仕様）
                square2State.lengthEnabled = (value.toInt() and 0x40) != 0
                if ((value.toInt() and 0x80) != 0) {
                    // トリガー時、NR52[7]=0の場合はチャンネルを有効化しない（実機仕様）
                    val nr52 = soundRegs[0x16]
                    val soundEnabled = (nr52.toInt() and 0x80) != 0
                    if (!soundEnabled) {
                        return // サウンドが無効な場合、トリガーを無視
                    }

                    // DAC無効化チェック: NR22の上位5ビットが全て0の場合、トリガーを無視（実機仕様）
                    val nr22 = soundRegs[0x07]
                    if ((nr22.toInt() and 0xF8) == 0) {
                        return // DACが無効な場合、トリガーを無視
                    }

                    square2State.enabled = true
                    // エンベロープの初期化（Trigger時にリセット）
                    square2State.envelopeVolume = (nr22.toInt() shr 4) and 0x0F
                    // エンベロープカウンタをピリオド値で初期化（実機仕様）
                    val envelopePeriod = nr22.toInt() and 0x07
                    square2State.envelopeCounter = if (envelopePeriod == 0) 8 else envelopePeriod
                    // 長さカウンタが0の場合、最大値（64）にリセット
                    if (square2State.lengthCounter == 0) {
                        square2State.lengthCounter = 64
                    }
                    // 長さカウンタの累積器をリセット
                    square2State.lengthCounterAccumulator = 0
                    // 位相をリセット（Trigger時は波形の先頭から再生）
                    square2State.phaseAccumulator = 0.0
                    // スキャンラインPCM: トリガーイベントをフレーム内タイムスタンプ付きで記録
                    if (sq2TriggerCount < sq2TriggerRelHalfCycles.size) {
                        sq2TriggerRelHalfCycles[sq2TriggerCount] = frameHalfCycles
                        sq2TriggerVolumes[sq2TriggerCount] = square2State.envelopeVolume
                        sq2TriggerCount++
                    }
                }
            }

            0x0A -> { // NR30 (Wave Enable)
                // Bit 7: Wave enable (1=有効, 0=無効)
                val waveEnabled = (value.toInt() and 0x80) != 0
                if (!waveEnabled) {
                    waveState.enabled = false
                }
                // Waveチャンネルの有効化はNR34のTriggerで行う
            }

            0x0B -> { // NR31 (Wave Length)
                waveState.lengthCounter = 256 - value.toInt()
            }

            0x0C -> { // NR32 (Wave Volume)
                waveState.volumeShift =
                    when ((value.toInt() shr 5) and 0x03) {
                        0 -> 4

                        // 無音
                        1 -> 0

                        // 100%
                        2 -> 1

                        // 50%
                        3 -> 2

                        // 25%
                        else -> 4
                    }
            }

            0x0E -> { // NR34 (Wave Frequency High & Trigger)
                // Length enableはトリガーに関係なく常に更新（実機仕様）
                waveState.lengthEnabled = (value.toInt() and 0x40) != 0
                if ((value.toInt() and 0x80) != 0) {
                    // トリガー時、NR52[7]=0の場合はチャンネルを有効化しない（実機仕様）
                    val nr52 = soundRegs[0x16]
                    val soundEnabled = (nr52.toInt() and 0x80) != 0
                    if (!soundEnabled) {
                        return // サウンドが無効な場合、トリガーを無視
                    }

                    // NR30のWave Enableをチェック
                    val nr30 = soundRegs[0x0A]
                    val waveEnabled = (nr30.toInt() and 0x80) != 0
                    if (waveEnabled) {
                        waveState.enabled = true
                        waveState.position = 0
                        waveState.phaseAccumulator = 0.0
                        // 長さカウンタが0の場合、最大値（256）にリセット
                        if (waveState.lengthCounter == 0) {
                            waveState.lengthCounter = 256
                        }
                        // 長さカウンタの累積器をリセット
                        waveState.lengthCounterAccumulator = 0
                    }
                }
            }

            0x10 -> { // NR41 (Noise Length)
                noiseState.lengthCounter = 64 - (value.toInt() and 0x3F)
            }

            0x11 -> { // NR42 (Noise Volume & Envelope)
                noiseState.envelopeVolume = (value.toInt() shr 4) and 0x0F
                noiseState.envelopePeriod = value.toInt() and 0x07
                noiseState.envelopeDirection = if ((value.toInt() and 0x08) != 0) 1 else -1
                noiseState.envelopeCounter = 0
                // DAC無効化チェック: NR42の上位5ビットが全て0の場合、DACが無効化される（実機仕様）
                if ((value.toInt() and 0xF8) == 0) {
                    noiseState.enabled = false
                }
            }

            0x12 -> { // NR43 (Noise Polynomial)
                // LFSRの設定はサンプル生成時に使用
            }

            0x13 -> { // NR44 (Noise Trigger)
                // Length enableはトリガーに関係なく常に更新（実機仕様）
                noiseState.lengthEnabled = (value.toInt() and 0x40) != 0
                if ((value.toInt() and 0x80) != 0) {
                    // トリガー時、NR52[7]=0の場合はチャンネルを有効化しない（実機仕様）
                    val nr52 = soundRegs[0x16]
                    val soundEnabled = (nr52.toInt() and 0x80) != 0
                    if (!soundEnabled) {
                        return // サウンドが無効な場合、トリガーを無視
                    }

                    // DAC無効化チェック: NR42の上位5ビットが全て0の場合、トリガーを無視（実機仕様）
                    val nr42 = soundRegs[0x11]
                    if ((nr42.toInt() and 0xF8) == 0) {
                        return // DACが無効な場合、トリガーを無視
                    }

                    noiseState.enabled = true
                    // エンベロープの初期化（Trigger時にリセット）
                    noiseState.envelopeVolume = (nr42.toInt() shr 4) and 0x0F
                    // エンベロープカウンタをピリオド値で初期化（実機仕様）
                    val envelopePeriod = nr42.toInt() and 0x07
                    noiseState.envelopeCounter = if (envelopePeriod == 0) 8 else envelopePeriod
                    // LFSRの初期化（Trigger時にリセット）
                    noiseState.lfsr = 0x7FFF
                    noiseState.lfsrHalfCycleAccumulator = 0L
                    // LFSR位置同期: トリガー時の絶対半サウンドサイクルを記録
                    noiseState.lfsrSampleHalfCycles = halfSoundCycleCounter
                    // 長さカウンタが0の場合、最大値（64）にリセット
                    if (noiseState.lengthCounter == 0) {
                        noiseState.lengthCounter = 64
                    }
                    // 長さカウンタの累積器をリセット
                    noiseState.lengthCounterAccumulator = 0
                }
            }

            0x00 -> { // NR10 (Square 1 Sweep)
                sweepPeriod = ((value.toInt() shr 4) and 0x07)
                sweepNegate = (value.toInt() and 0x08) != 0
                sweepShift = value.toInt() and 0x07
                // スイープが無効な場合（period == 0 かつ shift == 0）でも、shadowFrequencyをレジスタから更新
                if (sweepPeriod == 0 && sweepShift == 0) {
                    val nr13 = soundRegs[0x03]
                    val nr14 = soundRegs[0x04]
                    shadowFrequency = ((nr14.toInt() and 0x07) shl 8) or nr13.toInt()
                }
            }
        }

        // 1-bit PCM: NR50(0x14)/NR51(0x15) の書き込みをフレーム内相対タイムスタンプで記録。
        // generateSamples() でサンプル単位にリプレイすることで、CPU によるマスターボリューム
        // 高速切り替え（ピカチュウの鳴き声など）を正確に再現する。
        if ((offset == 0x14 || offset == 0x15) && pcmWriteCount < pcmWriteRelHalfCycles.size) {
            pcmWriteRelHalfCycles[pcmWriteCount] = frameHalfCycles
            pcmWriteOffsets[pcmWriteCount] = offset
            pcmWriteValues[pcmWriteCount] = soundRegs[offset]
            pcmWriteCount++
        }
    }

    /**
     * サウンド処理を進める（CPUサイクル単位）。
     *
     * @param cycles 処理するCPUサイクル数
     */
    fun step(
        cycles: Int,
        divInternalCounter: Int,
    ) {
        // 半サウンドサイクル = CPUサイクル / 4（常に整数）
        val halfSoundCycles = cycles / 4
        halfSoundCycleCounter += halfSoundCycles
        frameHalfCycles += halfSoundCycles

        // フレームシーケンサ（512Hz）をDIVレジスタのbit 12の立ち下がりエッジで進める
        // 実機仕様: DIVレジスタの内部16bitカウンタのbit 12が1→0になるタイミングで更新
        val currentDivBit12 = (divInternalCounter and 0x1000) != 0
        if (lastDivBit12 && !currentDivBit12) {
            // 立ち下がりエッジ検出
            advanceFrameSequencerStep()
        }
        lastDivBit12 = currentDivBit12
        // Noise の LFSR はサンプル生成時（generateNoiseSample）にサンプル単位で更新する
    }

    /**
     * 長さカウンタを更新（フレームシーケンサの256Hzステップで呼ばれる）
     *
     * 実機仕様: 256Hzで各チャンネルのLength Counterを1ずつデクリメント
     * 累積器は不要で、単純にカウンタを減らすだけ
     */
    private fun updateLengthCounters() {
        // Square 1の長さカウンタ
        if (square1State.lengthEnabled && square1State.lengthCounter > 0) {
            square1State.lengthCounter--
            if (square1State.lengthCounter <= 0) {
                square1State.enabled = false
            }
        }

        // Square 2の長さカウンタ
        if (square2State.lengthEnabled && square2State.lengthCounter > 0) {
            square2State.lengthCounter--
            if (square2State.lengthCounter <= 0) {
                square2State.enabled = false
            }
        }

        // Waveの長さカウンタ
        if (waveState.lengthEnabled && waveState.lengthCounter > 0) {
            waveState.lengthCounter--
            if (waveState.lengthCounter <= 0) {
                waveState.enabled = false
            }
        }

        // Noiseの長さカウンタ
        if (noiseState.lengthEnabled && noiseState.lengthCounter > 0) {
            noiseState.lengthCounter--
            if (noiseState.lengthCounter <= 0) {
                noiseState.enabled = false
            }
        }
    }

    /**
     * デバッグ用: チャンネルの内部状態を文字列で返す（android.util.Log非依存）。
     */
    fun getDebugState(): String {
        val sq1freq = ((soundRegs[0x04].toInt() and 0x07) shl 8) or soundRegs[0x03].toInt()
        val sq2freq = ((soundRegs[0x09].toInt() and 0x07) shl 8) or soundRegs[0x08].toInt()
        return "sq1[en=${square1State.enabled} vol=${square1State.envelopeVolume} freq=$sq1freq " +
            "nr12=0x${soundRegs[0x02].toString(16)}] " +
            "sq2[en=${square2State.enabled} vol=${square2State.envelopeVolume} freq=$sq2freq " +
            "nr22=0x${soundRegs[0x07].toString(16)}] " +
            "pcmWrites=$pcmWriteCount"
    }

    /**
     * 1フレーム分のオーディオサンプルを生成する（ステレオ形式）。
     *
     * @return 16bit PCMサンプル配列（ステレオ形式、左右交互、約735サンプル×2）
     */
    fun generateSamples(): ShortArray {
        val nr52 = soundRegs[0x16] // NR52 (Sound Control)
        val soundEnabled = (nr52.toInt() and 0x80) != 0

        if (!soundEnabled) {
            // サウンドが無効な場合は無音を返す（ステレオ形式）
            pcmWriteCount = 0
            sq1TriggerCount = 0
            sq2TriggerCount = 0
            frameHalfCycles = 0L
            return ShortArray(SAMPLES_PER_FRAME * 2) { 0 }
        }

        // フレーム開始時点を記録（半サウンドサイクル単位）
        // 1フレーム = 70224 CPUサイクル = 70224 / 4 = 17556 半サウンドサイクル
        val halfCyclesPerFrame = 70224 / 4

        val currentFrameStartHalf = frameStartHalfSoundCycle
        frameStartHalfSoundCycle = currentFrameStartHalf + halfCyclesPerFrame

        // サンプル生成用にDoubleに変換（サウンドサイクル単位）
        val currentFrameStart = currentFrameStartHalf / 2.0

        // 各サンプルごとに生成
        // 1サンプルあたりのサウンドサイクル数
        // サウンド周波数 = CPU_FREQUENCY / 8 = 524288 Hz
        // サンプリングレート = 44100 Hz
        // 1サンプルあたりのサウンドサイクル数 = 524288 / 44100 ≈ 11.89
        val soundCyclesPerSample = (CPU_FREQUENCY / 8.0) / SAMPLE_RATE

        // ステレオ形式（左右交互）のサンプル配列
        val samples = ShortArray(SAMPLES_PER_FRAME * 2)

        // チャンネルの有効性を事前にチェック（ループ内の条件分岐を削減）
        // チャンネル有効状態はサンプル生成中に変化しないのでループ外でキャッシュ
        val square1Enabled = square1State.enabled
        val square2Enabled = square2State.enabled
        val waveEnabled = waveState.enabled
        val noiseEnabled = noiseState.enabled

        // 1-bit PCM: NR50/NR51 はフレーム内書き込みログを使いサンプル単位で追跡する。
        // 初期値: このフレーム開始時点のレジスタ値（= 前フレーム最終書き込み値）
        var currentNr50 = soundRegs[0x14]
        var currentNr51 = soundRegs[0x15]
        var pcmWritePtr = 0

        // スキャンラインPCM: CH1/CH2のトリガーログをサンプル単位でリプレイする。
        // 初期値: フレーム開始前の最後のトリガー音量（存在しない場合は現状のエンベロープ値）
        var currentSq1Volume = square1State.envelopeVolume
        var currentSq2Volume = square2State.envelopeVolume
        var sq1TriggerPtr = 0
        var sq2TriggerPtr = 0

        var sampleIndex = 0
        var soundCycleOffset = 0.0

        for (i in 0 until SAMPLES_PER_FRAME) {
            // このサンプルのタイミング（サウンドサイクル単位）
            val sampleSoundCycle = currentFrameStart + soundCycleOffset
            soundCycleOffset += soundCyclesPerSample

            // このサンプルの終端タイミング（フレーム先頭からの相対半サウンドサイクル）
            // 「終端」を使うことで最終サンプルの直前まで書き込まれた NR50/NR51 が漏れなく適用される
            val sampleEndRelHalf = (i + 1).toLong() * halfCyclesPerFrame / SAMPLES_PER_FRAME

            // このサンプルの終端より前に書き込まれた NR50/NR51 を時系列順に適用
            // 1-bit PCM では NR50 が ~8kHz で切り替わるため、サンプルごとに確認が必要
            while (pcmWritePtr < pcmWriteCount &&
                pcmWriteRelHalfCycles[pcmWritePtr] < sampleEndRelHalf
            ) {
                val wrOffset = pcmWriteOffsets[pcmWritePtr]
                if (wrOffset == 0x14) {
                    currentNr50 = pcmWriteValues[pcmWritePtr]
                } else if (wrOffset == 0x15) {
                    currentNr51 = pcmWriteValues[pcmWritePtr]
                }
                pcmWritePtr++
            }

            // NR50/NR51 から現サンプルのボリュームとチャンネルルーティングを取得
            // NR51: Bit 7-4 = SO2(左チャンネル), Bit 3-0 = SO1(右チャンネル)
            val leftVolume = (currentNr50.toInt() shr 4) and 0x07 // 0=ミュート, 7=最大
            val rightVolume = currentNr50.toInt() and 0x07
            val nr51Int = currentNr51.toInt()
            val square1Left = (nr51Int and 0x10) != 0
            val square1Right = (nr51Int and 0x01) != 0
            val square2Left = (nr51Int and 0x20) != 0
            val square2Right = (nr51Int and 0x02) != 0
            val waveLeft = (nr51Int and 0x40) != 0
            val waveRight = (nr51Int and 0x04) != 0
            val noiseLeft = (nr51Int and 0x80) != 0
            val noiseRight = (nr51Int and 0x08) != 0

            // スキャンラインPCM: CH1トリガーログをリプレイして現サンプルの音量を決定。
            // pace=0（凍結）の場合はトリガー時の音量を優先する。
            // pace>0（減衰）の場合は現在のエンベロープ音量が低ければそちらを使う（減衰を尊重）。
            while (sq1TriggerPtr < sq1TriggerCount &&
                sq1TriggerRelHalfCycles[sq1TriggerPtr] < sampleEndRelHalf
            ) {
                val triggerVol = sq1TriggerVolumes[sq1TriggerPtr]
                val nr12period = soundRegs[0x02].toInt() and 0x07
                currentSq1Volume =
                    if (nr12period > 0 && square1State.envelopeVolume < triggerVol) {
                        square1State.envelopeVolume
                    } else {
                        triggerVol
                    }
                sq1TriggerPtr++
            }
            // CH2
            while (sq2TriggerPtr < sq2TriggerCount &&
                sq2TriggerRelHalfCycles[sq2TriggerPtr] < sampleEndRelHalf
            ) {
                val triggerVol = sq2TriggerVolumes[sq2TriggerPtr]
                val nr22period = soundRegs[0x07].toInt() and 0x07
                currentSq2Volume =
                    if (nr22period > 0 && square2State.envelopeVolume < triggerVol) {
                        square2State.envelopeVolume
                    } else {
                        triggerVol
                    }
                sq2TriggerPtr++
            }

            // 各チャンネルからサンプルを生成
            // オーバーフローを防ぐため、Long型で累積してからクリップ
            var leftMixed: Long = 0
            var rightMixed: Long = 0

            // Square 1チャンネル
            if (square1Enabled) {
                val square1Sample = generateSquare1Sample(soundCyclesPerSample, currentSq1Volume)
                if (square1Left) leftMixed += square1Sample
                if (square1Right) rightMixed += square1Sample
            }

            // Square 2チャンネル
            if (square2Enabled) {
                val square2Sample = generateSquare2Sample(soundCyclesPerSample, currentSq2Volume)
                if (square2Left) leftMixed += square2Sample
                if (square2Right) rightMixed += square2Sample
            }

            // Waveチャンネル
            if (waveEnabled) {
                val waveSample = generateWaveSample(soundCyclesPerSample)
                if (waveLeft) leftMixed += waveSample
                if (waveRight) rightMixed += waveSample
            }

            // Noiseチャンネル
            if (noiseEnabled) {
                val noiseSample = generateNoiseSample(sampleSoundCycle)
                if (noiseLeft) leftMixed += noiseSample
                if (noiseRight) rightMixed += noiseSample
            }

            // マスターボリュームを適用
            // 実機仕様: vol=0 は完全無音、vol=7 は最大出力。volume/7 で線形スケール。
            // （旧式の (volume+1)/8 は vol=0 でも 12.5% 出力が残り、1-bit PCM の
            //   OFF 状態を正しく再現できなかった）
            leftMixed = if (leftVolume == 0) 0L else leftMixed * leftVolume / 7
            rightMixed = if (rightVolume == 0) 0L else rightMixed * rightVolume / 7

            // ステレオ出力（左右交互）
            val leftSample =
                when {
                    leftMixed < -32768L -> -32768
                    leftMixed > 32767L -> 32767
                    else -> leftMixed.toInt()
                }.toShort()
            val rightSample =
                when {
                    rightMixed < -32768L -> -32768
                    rightMixed > 32767L -> 32767
                    else -> rightMixed.toInt()
                }.toShort()

            samples[sampleIndex++] = leftSample
            samples[sampleIndex++] = rightSample
        }

        // フレーム終了時に書き込みログとフレーム内カウンタをリセット
        pcmWriteCount = 0
        sq1TriggerCount = 0
        sq2TriggerCount = 0
        frameHalfCycles = 0L

        return samples
    }

    /**
     * Square 1チャンネルのサンプルを生成する。
     *
     * @param soundCyclesPerSample 1サンプルあたりのサウンドサイクル数（差分）
     */
    private fun generateSquare1Sample(
        soundCyclesPerSample: Double,
        volumeOverride: Int = -1,
    ): Int {
        // チャンネルが無効な場合は即座に0を返す（パフォーマンス向上）
        if (!square1State.enabled) {
            return 0
        }

        val nr11 = soundRegs[0x01] // NR11 (Length & Duty)

        // 周波数を計算（11bit値）
        // スイープ処理で更新されたshadowFrequencyを使用
        // ただし、スイープが無効な場合はレジスタから直接読み取る
        val nr10 = soundRegs[0x00]
        val sweepPeriod = ((nr10.toInt() shr 4) and 0x07)
        val sweepShift = nr10.toInt() and 0x07
        val frequency =
            if (sweepPeriod == 0 && sweepShift == 0) {
                // スイープが無効な場合、レジスタから直接読み取る
                val nr13 = soundRegs[0x03]
                val nr14 = soundRegs[0x04]
                ((nr14.toInt() and 0x07) shl 8) or nr13.toInt()
            } else {
                // スイープが有効な場合、shadowFrequencyを使用
                shadowFrequency
            }
        // 2048以上は無効（11bit値なので実際には到達しないが念のため）
        // 周波数0は有効: period=8192サウンドサイクルの極低周波矩形波として動作。
        // スキャンラインPCM技法では毎スキャンラインでトリガーするため、常にdutyパターンの
        // 先頭（High状態）に留まり、実質的にDACとして機能する。
        if (frequency >= 2048) {
            return 0
        }

        // 周期を計算（サウンドサイクル単位）
        // Game BoyのSquareチャンネルは、周波数 = (131072 / (2048 - frequency)) Hz
        // 周期 = (2048 - frequency) / 131072 秒
        // CPUサイクル = (2048 - frequency) * 32 CPUサイクル
        // サウンドサイクル = (2048 - frequency) * 32 / 8 = (2048 - frequency) * 4 サウンドサイクル
        // より正確な計算のため、浮動小数点を使用
        val period = (2048.0 - frequency) * 4.0
        if (period <= 0) {
            return 0
        }

        // デューティ比を取得（NR11 bit 6-7）
        val dutyCycle = (nr11.toInt() shr 6) and 0x03
        val dutyPattern =
            when (dutyCycle) {
                0 -> 0b00000001

                // 12.5% (1/8) - bit 0のみ
                1 -> 0b10000001

                // 25% (2/8) - bit 0, 7
                2 -> 0b10000111

                // 50% (4/8) - bit 0-2, 7
                3 -> 0b01111110

                // 75% (6/8) - bit 1-6（実機の仕様）
                else -> 0b00000001
            }

        // エンベロープボリュームを使用（スキャンラインPCMではオーバーライド値を優先）
        val volume = if (volumeOverride >= 0) volumeOverride else square1State.envelopeVolume
        if (volume == 0) {
            square1State.phaseAccumulator += soundCyclesPerSample
            if (square1State.phaseAccumulator >= period) {
                square1State.phaseAccumulator %= period
            }
            return 0
        }

        // 位相を進める（周波数変化時も位相が連続するよう差分加算）
        square1State.phaseAccumulator += soundCyclesPerSample
        if (square1State.phaseAccumulator >= period) {
            square1State.phaseAccumulator %= period
        }
        val dutyPosition = ((square1State.phaseAccumulator * 8.0 / period).toInt() and 0x07)
        val waveValue = if ((dutyPattern and (1 shl dutyPosition)) != 0) 1 else -1

        return (waveValue * volume * 546).coerceIn(-32768, 32767)
    }

    /**
     * Square 2チャンネルのサンプルを生成する（Square 1と同様）。
     *
     * @param soundCyclesPerSample 1サンプルあたりのサウンドサイクル数（差分）
     */
    private fun generateSquare2Sample(
        soundCyclesPerSample: Double,
        volumeOverride: Int = -1,
    ): Int {
        // チャンネルが無効な場合は即座に0を返す（パフォーマンス向上）
        if (!square2State.enabled) {
            return 0
        }

        val nr21 = soundRegs[0x06] // NR21 (Length & Duty)
        val nr23 = soundRegs[0x08] // NR23 (Frequency Low)
        val nr24 = soundRegs[0x09] // NR24 (Frequency High & Trigger)

        // 周波数を計算
        val frequency = ((nr24.toInt() and 0x07) shl 8) or nr23.toInt()
        // 2048以上は無効。周波数0はSquare1と同様に許可（スキャンラインPCM対応）。
        if (frequency >= 2048) {
            return 0
        }

        // 周期を計算（サウンドサイクル単位）
        // Game BoyのSquareチャンネルは、周波数 = (131072 / (2048 - frequency)) Hz
        // 周期 = (2048 - frequency) * 32 CPUサイクル = (2048 - frequency) * 4 サウンドサイクル
        // より正確な計算のため、浮動小数点を使用
        val period = (2048.0 - frequency) * 4.0
        if (period <= 0) {
            return 0
        }

        // デューティ比を取得
        val dutyCycle = (nr21.toInt() shr 6) and 0x03
        val dutyPattern =
            when (dutyCycle) {
                0 -> 0b00000001

                // 12.5% (1/8)
                1 -> 0b10000001

                // 25% (2/8)
                2 -> 0b10000111

                // 50% (4/8)
                3 -> 0b01111110

                // 75% (6/8) - bit 1-6（実機の仕様）
                else -> 0b00000001
            }

        // エンベロープボリュームを使用（スキャンラインPCMではオーバーライド値を優先）
        val volume = if (volumeOverride >= 0) volumeOverride else square2State.envelopeVolume
        if (volume == 0) {
            square2State.phaseAccumulator += soundCyclesPerSample
            if (square2State.phaseAccumulator >= period) {
                square2State.phaseAccumulator %= period
            }
            return 0
        }

        // 位相を進める（周波数変化時も位相が連続するよう差分加算）
        square2State.phaseAccumulator += soundCyclesPerSample
        if (square2State.phaseAccumulator >= period) {
            square2State.phaseAccumulator %= period
        }
        val dutyPosition = ((square2State.phaseAccumulator * 8.0 / period).toInt() and 0x07)
        val waveValue = if ((dutyPattern and (1 shl dutyPosition)) != 0) 1 else -1

        return (waveValue * volume * 546).coerceIn(-32768, 32767)
    }

    /**
     * Squareチャンネルの内部状態
     */
    private class SquareChannelState {
        var enabled = false
        var lengthCounter = 0
        var lengthEnabled = false
        var lengthCounterAccumulator = 0 // 長さカウンタ更新用の累積器（256Hz = 2048サウンドサイクルごと）
        var envelopeVolume = 0
        var envelopePeriod = 0
        var envelopeCounter = 0
        var envelopeDirection = 1 // 1 = 増加, -1 = 減少
        var phaseAccumulator: Double = 0.0 // 位相アキュムレータ（0 to period）。周波数変化時も位相連続
    }

    /**
     * Waveチャンネルの内部状態
     */
    private class WaveChannelState {
        var enabled = false
        var lengthCounter = 0
        var lengthEnabled = false
        var lengthCounterAccumulator = 0 // 長さカウンタ更新用の累積器（256Hz = 2048サウンドサイクルごと）
        var volumeShift = 0
        var position = 0
        var phaseAccumulator: Double = 0.0 // 位相アキュムレータ（0 to period）。周波数変化時も位相連続
    }

    /**
     * Noiseチャンネルの内部状態
     */
    private class NoiseChannelState {
        var enabled = false
        var lengthCounter = 0
        var lengthEnabled = false
        var lengthCounterAccumulator = 0 // 長さカウンタ更新用の累積器（256Hz = 2048サウンドサイクルごと）
        var envelopeVolume = 0
        var envelopePeriod = 0
        var envelopeCounter = 0
        var envelopeDirection = 1
        var lfsr = 0x7FFF // Linear Feedback Shift Register
        var lfsrHalfCycleAccumulator = 0L // LFSR周期内の半サウンドサイクル累積器

        // サンプル生成時のLFSR位置同期用：最後にLFSRを進めた絶対半サウンドサイクル
        var lfsrSampleHalfCycles: Long = 0L
    }

    /**
     * Square 1チャンネルのエンベロープを更新（フレームシーケンサの64Hzステップで呼ばれる）
     *
     * 実機仕様: 64Hzで呼ばれ、envelopeCounterをデクリメント
     * カウンタが0になったらボリュームを更新
     */
    private fun updateSquare1Envelope() {
        if (!square1State.enabled) {
            return
        }

        val nr12 = soundRegs[0x02]
        val envelopePeriod = nr12.toInt() and 0x07

        // periodが0の場合、エンベロープは無効
        if (envelopePeriod == 0) {
            return
        }

        // カウンタをデクリメント
        square1State.envelopeCounter--
        if (square1State.envelopeCounter > 0) {
            return
        }

        // カウンタが0になったらボリュームを更新してリロード
        square1State.envelopeCounter = envelopePeriod

        val direction = if ((nr12.toInt() and 0x08) != 0) 1 else -1
        val newVolume = square1State.envelopeVolume + direction

        // ボリュームは0-15の範囲
        if (newVolume < 0 || newVolume > 15) {
            // 範囲外の場合、更新を停止
            return
        }

        square1State.envelopeVolume = newVolume
    }

    /**
     * Square 2チャンネルのエンベロープを更新（フレームシーケンサの64Hzステップで呼ばれる）
     */
    private fun updateSquare2Envelope() {
        if (!square2State.enabled) {
            return
        }

        val nr22 = soundRegs[0x07] // NR22 (Square 2 Volume & Envelope)
        val envelopePeriod = nr22.toInt() and 0x07

        if (envelopePeriod == 0) {
            return
        }

        square2State.envelopeCounter--
        if (square2State.envelopeCounter > 0) {
            return
        }

        square2State.envelopeCounter = envelopePeriod

        val direction = if ((nr22.toInt() and 0x08) != 0) 1 else -1
        val newVolume = square2State.envelopeVolume + direction

        if (newVolume < 0 || newVolume > 15) {
            return
        }

        square2State.envelopeVolume = newVolume
    }

    /**
     * Noiseチャンネルのエンベロープを更新（フレームシーケンサの64Hzステップで呼ばれる）
     */
    private fun updateNoiseEnvelope() {
        if (!noiseState.enabled) {
            return
        }

        val nr42 = soundRegs[0x11]
        val envelopePeriod = nr42.toInt() and 0x07

        if (envelopePeriod == 0) {
            return
        }

        noiseState.envelopeCounter--
        if (noiseState.envelopeCounter > 0) {
            return
        }

        noiseState.envelopeCounter = envelopePeriod

        val direction = if ((nr42.toInt() and 0x08) != 0) 1 else -1
        val newVolume = noiseState.envelopeVolume + direction

        if (newVolume < 0 || newVolume > 15) {
            return
        }

        noiseState.envelopeVolume = newVolume
    }

    /**
     * フレームシーケンサを1ステップ進める（DIVのbit 12立ち下がりエッジで呼ばれる）。
     *
     * - 512Hz ステップごとに frameSequencerStep (0-7) が進む。
     * - ステップ 0,2,4,6: 256Hz Length カウンタ
     * - ステップ 2,6: 128Hz Sweep（Square1）
     * - ステップ 7: 64Hz Envelope（Square1/2/Noise）
     */
    private fun advanceFrameSequencerStep() {
        frameSequencerStep = (frameSequencerStep + 1) and 0x07

        when (frameSequencerStep) {
            0, 2, 4, 6 -> {
                // 256Hz: Lengthカウンタ（全チャンネル）
                updateLengthCounters()
            }
        }

        when (frameSequencerStep) {
            2, 6 -> {
                // 128Hz: Square1 スイープ
                updateSweep()
            }
        }

        if (frameSequencerStep == 7) {
            // 64Hz: エンベロープ（Square1/2/Noise）
            updateSquare1Envelope()
            updateSquare2Envelope()
            updateNoiseEnvelope()
        }
    }

    /**
     * Waveチャンネルのサンプルを生成する。
     *
     * @param soundCyclesPerSample 1サンプルあたりのサウンドサイクル数（差分）
     */
    private fun generateWaveSample(soundCyclesPerSample: Double): Int {
        // チャンネルが無効な場合は即座に0を返す（パフォーマンス向上）
        if (!waveState.enabled) {
            return 0
        }

        val nr33 = soundRegs[0x0D] // NR33 (Frequency Low)
        val nr34 = soundRegs[0x0E] // NR34 (Frequency High & Trigger)

        // 周波数を計算
        val frequency = ((nr34.toInt() and 0x07) shl 8) or nr33.toInt()
        if (frequency == 0 || frequency >= 2048) {
            return 0
        }

        // 周期を計算
        // 仕様書: 周波数 = 65536 / (2048 - x) Hz（Squareチャンネルの半分）
        // 周期 = (2048 - frequency) / 65536 秒
        // CPUサイクル = (2048 - frequency) * 64 CPUサイクル
        // サウンドサイクル = (2048 - frequency) * 64 / 8 = (2048 - frequency) * 8 サウンドサイクル
        // ※Squareチャンネルは (2048 - frequency) * 4 なので、Waveはその2倍
        // より正確な計算のため、浮動小数点を使用
        val period = (2048.0 - frequency) * 8.0
        if (period <= 0) {
            return 0
        }

        // 位相を進める（周波数変化時も位相が連続するよう差分加算）
        waveState.phaseAccumulator += soundCyclesPerSample
        if (waveState.phaseAccumulator >= period) {
            waveState.phaseAccumulator %= period
        }
        // 32サンプルの波形なので、位置を0-31の範囲に正規化
        val sampleIndex = ((waveState.phaseAccumulator * 32.0 / period).toInt() and 0x1F)

        // 波形RAMからサンプルを読み取り（16バイト = 32サンプル）
        val byteIndex = (sampleIndex / 2).coerceIn(0, 15)
        val nibble =
            if (sampleIndex % 2 == 0) {
                (waveRam[byteIndex].toInt() shr 4) and 0x0F
            } else {
                waveRam[byteIndex].toInt() and 0x0F
            }

        // ボリュームシフトを適用
        val volumeShift = waveState.volumeShift
        if (volumeShift >= 4) {
            return 0 // 無音
        }

        val sample = (nibble shr volumeShift) - 8 // -8 to 7
        // 16bit範囲にスケール（整数演算で丸め誤差を防ぐ）
        // 実機では、Waveチャンネルの出力は -8 to 7 の範囲で、16bit範囲にスケールされる
        // スケール係数: 4096 / 4 = 1024（4チャンネルミキシングのヘッドルーム確保）
        return (sample * 1024).coerceIn(-32768, 32767)
    }

    /**
     * Noiseチャンネルのサンプルを生成する。
     *
     * 実機仕様: LFSR は独立した周波数で常時更新される。
     * 各サンプルのタイミング（soundCycle）に対応した LFSR 状態を使うため、
     * このメソッド内でサンプルごとに LFSR を進める。
     *
     * @param soundCycle サウンドサイクル単位の絶対タイミング（Double型）
     */
    private fun generateNoiseSample(soundCycle: Double): Int {
        if (!noiseState.enabled) {
            return 0
        }

        val volume = noiseState.envelopeVolume
        if (volume == 0) {
            return 0
        }

        val nr43 = soundRegs[0x12] // NR43 (Polynomial Counter)
        val r = nr43.toInt() and 0x07
        val s = (nr43.toInt() shr 4) and 0x0F

        // Pan Docs: 半サウンドサイクル単位の LFSR 周期
        //   r=0: 2^(s+1) 半サウンドサイクル
        //   r>0: r * 2^(s+2) 半サウンドサイクル
        val periodHalfCycles: Long =
            if (s < 16) {
                if (r == 0) 1L shl (s + 1) else r.toLong() shl (s + 2)
            } else {
                0L
            }

        if (periodHalfCycles <= 0) {
            return 0
        }

        // このサンプルの絶対タイミング（半サウンドサイクル単位）
        val currentHalfCycles = (soundCycle * 2.0).toLong()
        val elapsedHalfCycles = currentHalfCycles - noiseState.lfsrSampleHalfCycles

        // 前回のサンプルから経過した分だけ LFSR を進める
        if (elapsedHalfCycles > 0) {
            var acc = noiseState.lfsrHalfCycleAccumulator + elapsedHalfCycles
            while (acc >= periodHalfCycles) {
                acc -= periodHalfCycles
                // XOR = bit0 XOR bit1
                val xor = (noiseState.lfsr xor (noiseState.lfsr shr 1)) and 1
                noiseState.lfsr = (noiseState.lfsr shr 1) and 0x7FFF
                if (xor != 0) {
                    noiseState.lfsr = noiseState.lfsr or 0x4000
                }
                // 7bitモード: bit6 へフィードバックをコピー
                if ((nr43.toInt() and 0x08) != 0) {
                    val bit14 = (noiseState.lfsr shr 14) and 1
                    noiseState.lfsr = (noiseState.lfsr and 0x7FBF) or (bit14 shl 6)
                }
            }
            noiseState.lfsrHalfCycleAccumulator = acc
            noiseState.lfsrSampleHalfCycles = currentHalfCycles
        }

        // LFSR bit0 が出力: 0 → +1, 1 → -1（実機仕様）
        val noiseValue = if ((noiseState.lfsr and 1) == 0) 1 else -1

        // スケール係数: 32767 / 15 / 4 = 546（4チャンネルミキシングのヘッドルーム確保）
        return (noiseValue * volume * 546).coerceIn(-32768, 32767)
    }

    /**
     * Sweepを更新（フレームシーケンサの128Hzステップで呼ばれる）
     *
     * 実機仕様: 128Hzで呼ばれ、sweepCounterをデクリメント
     * sweepCounterが0になったら周波数を更新
     */
    private fun updateSweep() {
        // スイープが無効な場合、またはチャンネルが無効な場合は何もしない
        if (!square1State.enabled || !sweepEnabled) {
            return
        }

        val nr10 = soundRegs[0x00]
        val currentSweepPeriod = ((nr10.toInt() shr 4) and 0x07)
        val currentSweepShift = nr10.toInt() and 0x07

        // スイープが無効な場合（period == 0）
        if (currentSweepPeriod == 0) {
            return
        }

        // カウンタをデクリメント
        sweepCounter--
        if (sweepCounter > 0) {
            return
        }

        // カウンタが0になったら周波数を更新してリロード
        sweepCounter = if (currentSweepPeriod == 0) 8 else currentSweepPeriod

        // 周波数を更新
        // 実機の仕様: change = shadowFrequency >> sweepShift
        val change = shadowFrequency shr currentSweepShift
        if (change == 0) {
            // changeが0の場合、何もしない
            return
        }

        val newFrequency =
            if ((nr10.toInt() and 0x08) != 0) {
                shadowFrequency - change
            } else {
                shadowFrequency + change
            }

        if (newFrequency < 0 || newFrequency > 2047) {
            square1State.enabled = false
            sweepEnabled = false
            return
        }

        shadowFrequency = newFrequency
        // 周波数レジスタを更新
        soundRegs[0x03] = (newFrequency and 0xFF).toUByte()
        soundRegs[0x04] = ((soundRegs[0x04].toInt() and 0xF8) or (newFrequency shr 8)).toUByte()
    }
}
