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
        const val SQUARE2_START = 0xFF15
        const val SQUARE2_END = 0xFF19
        const val WAVE_START = 0xFF1A
        const val WAVE_END = 0xFF1E
        const val NOISE_START = 0xFF1F
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
    }

    // サウンドレジスタ（0xFF10-0xFF3F）
    private val soundRegs: UByteArray = UByteArray(0x30) { 0u }
    
    // 波形RAM（0xFF30-0xFF3F、Waveチャンネル用）
    private val waveRam: UByteArray = UByteArray(0x10) { 0u }
    
    // サウンド生成用の内部状態
    // soundCycleCounterはサウンドサイクル単位（CPUサイクル/8）で管理
    // 浮動小数点で精度を保持
    private var soundCycleCounter: Double = 0.0
    private var frameStartSoundCycle: Double = 0.0 // フレーム開始時点のサウンドサイクル
    
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
            // 実機では、Waveチャンネルが有効な場合、波形RAMの読み取りも制限される
            // ただし、実機の動作は複雑で、正確には「Waveチャンネルが再生中の場合、波形RAMは読み取り専用になる」
            // 簡易実装として、Waveチャンネルが有効な場合は、最後に書き込まれた値を返す
            // （実機では、Waveチャンネルが有効な場合、波形RAMは読み取り専用になるが、値は保持される）
            return waveRam[offset - 0x20]
        }
        
        // NR52 (Sound Control) の読み取り時、各チャンネルの有効状態を反映
        if (offset == 0x16) {
            val baseValue = soundRegs[offset]
            var result = baseValue.toInt()
            if (square1State.enabled) result = result or 0x01
            if (square2State.enabled) result = result or 0x02
            if (waveState.enabled) result = result or 0x04
            if (noiseState.enabled) result = result or 0x08
            return result.toUByte()
        }
        
        return soundRegs[offset]
    }

    /**
     * サウンドレジスタの書き込み。
     *
     * @param offset 0xFF10からのオフセット（0-0x2F）
     * @param value 書き込む値
     */
    fun writeRegister(offset: Int, value: UByte) {
        if (offset < 0 || offset >= 0x30) {
            return
        }
        
        // 波形RAM（0xFF30-0xFF3F）の書き込み
        if (offset >= 0x20) {
            // Waveチャンネルが有効な場合、波形RAMへの書き込みは制限される
            // 実機では、Waveチャンネルが有効な場合、波形RAMは読み取り専用になる
            // ただし、簡易実装として、Waveチャンネルが有効な場合は書き込みを無視
            if (!waveState.enabled) {
                waveRam[offset - 0x20] = value
            }
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
            0x04, // NR14 (Square 1)
            0x09, // NR24 (Square 2)
            0x0E, // NR34 (Wave)
            0x13, // NR44 (Noise)
            -> {
                // これらのレジスタの bit 6-7 は読み取り専用
                val currentValue = soundRegs[offset]
                val newValue = (value and 0x3Fu.toUByte()) or (currentValue and 0xC0u.toUByte())
                soundRegs[offset] = newValue
            }
            0x16, // NR52 (Sound Control)
            -> {
                // bit 0-6 は読み取り専用、bit 7 のみ書き込み可能
                val currentValue = soundRegs[offset]
                val newValue = (value and 0x80u.toUByte()) or (currentValue and 0x7Fu.toUByte())
                soundRegs[offset] = newValue
                
                // NR52[7]=0に書き込むと全チャンネルを無効化
                val soundEnabled = (newValue.toInt() and 0x80) != 0
                if (!soundEnabled) {
                    square1State.enabled = false
                    square2State.enabled = false
                    waveState.enabled = false
                    noiseState.enabled = false
                    sweepEnabled = false
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
            }
            0x04 -> { // NR14 (Square 1 Frequency High & Trigger)
                if ((value.toInt() and 0x80) != 0) {
                    // チャンネルを有効化
                    square1State.enabled = true
                    // エンベロープの初期化（Trigger時にリセット）
                    square1State.envelopeVolume = (soundRegs[0x02].toInt() shr 4) and 0x0F
                    square1State.envelopeCounter = 0
                    // 長さカウンタの初期化（Length Enableが有効な場合）
                    if ((value.toInt() and 0x40) != 0) {
                        square1State.lengthEnabled = true
                        // 長さカウンタが0の場合、最大値（64）にリセット
                        if (square1State.lengthCounter == 0) {
                            square1State.lengthCounter = 64
                        }
                        // 長さカウンタの累積器をリセット
                        square1State.lengthCounterAccumulator = 0
                    } else {
                        square1State.lengthEnabled = false
                    }
                    // スイープの初期化
                    val nr10 = soundRegs[0x00]
                    sweepPeriod = ((nr10.toInt() shr 4) and 0x07)
                    sweepEnabled = sweepPeriod > 0 || (nr10.toInt() and 0x08) != 0
                    sweepCounter = 0
                    sweepNegate = (nr10.toInt() and 0x08) != 0
                    sweepShift = nr10.toInt() and 0x07
                    val frequency = ((value.toInt() and 0x07) shl 8) or soundRegs[0x03].toInt()
                    shadowFrequency = frequency
                    sweepInitialized = false // 最初の更新を遅延させる
                }
            }
            0x05 -> { // NR21 (Square 2 Length & Duty)
                square2State.lengthCounter = 64 - (value.toInt() and 0x3F)
            }
            0x06 -> { // NR22 (Square 2 Volume & Envelope)
                square2State.envelopeVolume = (value.toInt() shr 4) and 0x0F
                square2State.envelopePeriod = value.toInt() and 0x07
                square2State.envelopeDirection = if ((value.toInt() and 0x08) != 0) 1 else -1
                square2State.envelopeCounter = 0
            }
            0x08 -> { // NR24 (Square 2 Frequency High & Trigger)
                if ((value.toInt() and 0x80) != 0) {
                    square2State.enabled = true
                    // エンベロープの初期化（Trigger時にリセット）
                    square2State.envelopeVolume = (soundRegs[0x06].toInt() shr 4) and 0x0F
                    square2State.envelopeCounter = 0
                    // 長さカウンタの初期化（Length Enableが有効な場合）
                    if ((value.toInt() and 0x40) != 0) {
                        square2State.lengthEnabled = true
                        // 長さカウンタが0の場合、最大値（64）にリセット
                        if (square2State.lengthCounter == 0) {
                            square2State.lengthCounter = 64
                        }
                        // 長さカウンタの累積器をリセット
                        square2State.lengthCounterAccumulator = 0
                    } else {
                        square2State.lengthEnabled = false
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
                waveState.volumeShift = when ((value.toInt() shr 5) and 0x03) {
                    0 -> 4 // 無音
                    1 -> 0 // 100%
                    2 -> 1 // 50%
                    3 -> 2 // 25%
                    else -> 4
                }
            }
            0x0E -> { // NR34 (Wave Frequency High & Trigger)
                if ((value.toInt() and 0x80) != 0) {
                    // NR30のWave Enableをチェック
                    val nr30 = soundRegs[0x0A]
                    val waveEnabled = (nr30.toInt() and 0x80) != 0
                    if (waveEnabled) {
                        waveState.enabled = true
                        waveState.position = 0
                        // 長さカウンタの初期化（Length Enableが有効な場合）
                        if ((value.toInt() and 0x40) != 0) {
                            waveState.lengthEnabled = true
                            // 長さカウンタが0の場合、最大値（256）にリセット
                            if (waveState.lengthCounter == 0) {
                                waveState.lengthCounter = 256
                            }
                            // 長さカウンタの累積器をリセット
                            waveState.lengthCounterAccumulator = 0
                        } else {
                            waveState.lengthEnabled = false
                        }
                    }
                }
            }
            0x11 -> { // NR41 (Noise Length)
                noiseState.lengthCounter = 64 - (value.toInt() and 0x3F)
            }
            0x12 -> { // NR42 (Noise Volume & Envelope)
                noiseState.envelopeVolume = (value.toInt() shr 4) and 0x0F
                noiseState.envelopePeriod = value.toInt() and 0x07
                noiseState.envelopeDirection = if ((value.toInt() and 0x08) != 0) 1 else -1
                noiseState.envelopeCounter = 0
            }
            0x13 -> { // NR43 (Noise Polynomial)
                // LFSRの設定はサンプル生成時に使用
            }
            0x14 -> { // NR44 (Noise Trigger)
                if ((value.toInt() and 0x80) != 0) {
                    noiseState.enabled = true
                    // エンベロープの初期化（Trigger時にリセット）
                    noiseState.envelopeVolume = (soundRegs[0x12].toInt() shr 4) and 0x0F
                    noiseState.envelopeCounter = 0
                    // LFSRの初期化（Trigger時にリセット）
                    noiseState.lfsr = 0x7FFF
                    noiseState.lfsrCounter = 0
                    noiseState.lfsrCounterAccumulator = 0.0
                    // 長さカウンタの初期化（Length Enableが有効な場合）
                    if ((value.toInt() and 0x40) != 0) {
                        noiseState.lengthEnabled = true
                        // 長さカウンタが0の場合、最大値（64）にリセット
                        if (noiseState.lengthCounter == 0) {
                            noiseState.lengthCounter = 64
                        }
                        // 長さカウンタの累積器をリセット
                        noiseState.lengthCounterAccumulator = 0
                    } else {
                        noiseState.lengthEnabled = false
                    }
                }
            }
            0x00 -> { // NR10 (Square 1 Sweep)
                sweepPeriod = ((value.toInt() shr 4) and 0x07)
                sweepNegate = (value.toInt() and 0x08) != 0
                sweepShift = value.toInt() and 0x07
            }
        }
    }
    
    /**
     * サウンド処理を進める（CPUサイクル単位）。
     *
     * @param cycles 処理するCPUサイクル数
     */
    fun step(cycles: Int) {
        // サウンド処理はCPU周波数の1/8で動作
        // soundCycleCounterはサウンドサイクル単位で管理（浮動小数点で精度を保持）
        soundCycleCounter += cycles / 8.0
        
        // サウンドサイクル単位に変換（1/8）
        val soundCycles = cycles / 8
        
        // 各チャンネルの状態を更新
        updateSquare1Channel(soundCycles)
        updateSquare2Channel(soundCycles)
        updateWaveChannel(soundCycles)
        updateNoiseChannel(soundCycles)
        
        // Square 1のスイープ処理
        updateSweep(soundCycles)
    }
    
    /**
     * 1フレーム分のオーディオサンプルを生成する。
     *
     * @return 16bit PCMサンプル配列（約735サンプル）
     */
    fun generateSamples(): ShortArray {
        val nr52 = soundRegs[0x16] // NR52 (Sound Control)
        val soundEnabled = (nr52.toInt() and 0x80) != 0
        
        if (!soundEnabled) {
            // サウンドが無効な場合は無音を返す
            return ShortArray(SAMPLES_PER_FRAME) { 0 }
        }
        
        // フレーム開始時点のサウンドサイクルを記録
        // 1フレーム = 70224 CPUサイクル = 70224 / 8 = 8778 サウンドサイクル
        val cyclesPerFrame = 70224 / 8.0
        
        // 最初のフレームでは、現在のカウンタから1フレーム分を引く
        // より正確なタイミング計算のため、フレーム開始時点を正確に記録
        val currentFrameStart = if (frameStartSoundCycle == 0.0) {
            // 最初のフレームでは、現在のカウンタから1フレーム分を引く
            soundCycleCounter - cyclesPerFrame
        } else {
            // 前回のフレーム開始時点を使用
            frameStartSoundCycle
        }
        
        // 次のフレームの開始時点を更新（現在のカウンタ位置）
        // 実際のフレームサイクル数に基づいて更新（より正確に）
        // 1フレーム = 70224 CPUサイクル = 70224 / 8 = 8778 サウンドサイクル
        // 正確なフレームサイクル数を使用
        frameStartSoundCycle = soundCycleCounter
        
        // 各サンプルごとに生成
        // 1サンプルあたりのサウンドサイクル数
        // サウンド周波数 = CPU_FREQUENCY / 8 = 524288 Hz
        // サンプリングレート = 44100 Hz
        // 1サンプルあたりのサウンドサイクル数 = 524288 / 44100 ≈ 11.89
        val soundCyclesPerSample = (CPU_FREQUENCY / 8.0) / SAMPLE_RATE
        
        // 常に正確な数のサンプルを生成（フレームレートに基づく）
        val samples = ShortArray(SAMPLES_PER_FRAME)
        
        // レジスタ読み取りをループ外に移動（パフォーマンス向上）
        val nr50 = soundRegs[0x14] // NR50 (Master Volume)
        val nr51 = soundRegs[0x15] // NR51 (Channel Select)
        val leftVolume = ((nr50.toInt() shr 4) and 0x07) + 1
        val rightVolume = (nr50.toInt() and 0x07) + 1
        val nr51Int = nr51.toInt()
        
        // チャンネルの有効性を事前にチェック（ループ内の条件分岐を削減）
        val square1Enabled = square1State.enabled
        val square2Enabled = square2State.enabled
        val waveEnabled = waveState.enabled
        val noiseEnabled = noiseState.enabled
        
        // チャンネル出力マスクを事前に計算
        // 実機の仕様に基づく正しいビットマスク
        val square1Left = (nr51Int and 0x10) != 0  // Bit 4
        val square1Right = (nr51Int and 0x01) != 0  // Bit 0
        val square2Left = (nr51Int and 0x20) != 0  // Bit 5
        val square2Right = (nr51Int and 0x02) != 0  // Bit 1
        val waveLeft = (nr51Int and 0x40) != 0  // Bit 6
        val waveRight = (nr51Int and 0x04) != 0  // Bit 2
        val noiseLeft = (nr51Int and 0x80) != 0  // Bit 7
        val noiseRight = (nr51Int and 0x08) != 0  // Bit 3
        
        // 各チャンネルからサンプルを生成してミキシング
        for (i in samples.indices) {
            // このサンプルのタイミング（サウンドサイクル単位）
            // フレーム開始時点からの相対的な位置を計算（浮動小数点で精度を保持）
            val sampleSoundCycle = currentFrameStart + i * soundCyclesPerSample
            
            // 各チャンネルからサンプルを生成
            // オーバーフローを防ぐため、Long型で累積してからクリップ
            var leftMixed: Long = 0
            var rightMixed: Long = 0
            
            // Square 1チャンネル
            // 実機では、チャンネルが有効で、かつNR51で有効化されている場合のみ出力
            if (square1Enabled && (square1Left || square1Right)) {
                val square1Sample = generateSquare1Sample(sampleSoundCycle)
                if (square1Left) leftMixed += square1Sample
                if (square1Right) rightMixed += square1Sample
            }
            
            // Square 2チャンネル
            if (square2Enabled && (square2Left || square2Right)) {
                val square2Sample = generateSquare2Sample(sampleSoundCycle)
                if (square2Left) leftMixed += square2Sample
                if (square2Right) rightMixed += square2Sample
            }
            
            // Waveチャンネル
            if (waveEnabled && (waveLeft || waveRight)) {
                val waveSample = generateWaveSample(sampleSoundCycle)
                if (waveLeft) leftMixed += waveSample
                if (waveRight) rightMixed += waveSample
            }
            
            // Noiseチャンネル
            if (noiseEnabled && (noiseLeft || noiseRight)) {
                val noiseSample = generateNoiseSample(sampleSoundCycle)
                if (noiseLeft) leftMixed += noiseSample
                if (noiseRight) rightMixed += noiseSample
            }
            
            // ボリュームを適用（より正確な計算）
            // 実機では、各チャンネルの出力をボリュームでスケールしてからミキシング
            // ボリュームは1-8の範囲で、出力は (sample * volume) / 8 で計算される
            // オーバーフローを防ぐため、Long型で計算してからクリップ
            leftMixed = (leftMixed * leftVolume) / 8
            rightMixed = (rightMixed * rightVolume) / 8
            
            // ステレオミキシング（簡易実装：モノラルとして出力）
            // 実機では、左右のチャンネルを別々に出力するが、ここでは平均を取る
            // オーバーフローを防ぐため、Long型で計算してからクリップ
            val mixed = ((leftMixed + rightMixed) / 2).coerceIn(-32768, 32767)
            
            // ミキシング結果を16bitにクリップ
            samples[i] = mixed.toInt().toShort()
        }
        
        return samples
    }
    
    /**
     * Square 1チャンネルのサンプルを生成する。
     *
     * @param soundCycle サウンドサイクル単位のタイミング（Double型で精度を保持）
     */
    private fun generateSquare1Sample(soundCycle: Double): Int {
        // チャンネルが無効な場合は即座に0を返す（パフォーマンス向上）
        if (!square1State.enabled) {
            return 0
        }
        
        val nr11 = soundRegs[0x01] // NR11 (Length & Duty)
        
        // 周波数を計算（11bit値）
        // スイープ処理で更新されたshadowFrequencyを使用
        val frequency = shadowFrequency
        // 周波数が0または2048以上の場合、無効
        if (frequency == 0 || frequency >= 2048) {
            return 0
        }
        
        // 周期を計算（サウンドサイクル単位）
        // Game BoyのSquareチャンネルは、周波数 = (131072 / (2048 - frequency)) Hz
        // サウンド周波数はCPU周波数の1/8なので、周期 = (2048 - frequency) * 8 サウンドサイクル
        // より正確な計算のため、浮動小数点を使用
        val period = (2048.0 - frequency) * 8.0
        if (period <= 0) {
            return 0
        }
        
        // デューティ比を取得（NR11 bit 6-7）
        val dutyCycle = (nr11.toInt() shr 6) and 0x03
        val dutyPattern = when (dutyCycle) {
            0 -> 0b00000001 // 12.5% (1/8) - bit 0のみ
            1 -> 0b00000011 // 25% (2/8) - bit 0-1
            2 -> 0b00001111 // 50% (4/8) - bit 0-3
            3 -> 0b11111100 // 75% (6/8) - bit 2-7
            else -> 0b00001111
        }
        
        // エンベロープボリュームを使用
        // 実機では、エンベロープのボリュームが0でも、チャンネルは有効のまま
        // ただし、サンプル生成時にボリュームが0の場合は、無音を返す
        val volume = square1State.envelopeVolume
        if (volume == 0) {
            return 0
        }
        
        // 波形を生成（矩形波）
        // 周期内の位置を0-7の範囲に正規化（より正確な計算）
        // 浮動小数点で計算してから整数に変換することで、精度を向上
        // periodはDouble型なので、浮動小数点の剰余演算を使用
        val positionInPeriod = soundCycle % period
        // デューティ比の位置を計算（0-7の範囲）
        // より正確な計算のため、浮動小数点を使用
        val dutyPositionDouble = (positionInPeriod * 8.0 / period)
        val dutyPosition = dutyPositionDouble.toInt().coerceIn(0, 7)
        val waveValue = if ((dutyPattern and (1 shl dutyPosition)) != 0) 1 else -1
        
        // ボリュームを適用（0-15を0-32767にスケール）
        // 実機では、ボリュームは0-15で、出力は waveValue * volume で計算される
        // 16bit範囲にスケールするため、2184.5を掛ける（15 * 2184.5 ≈ 32767.5）
        // より正確なスケーリングのため、浮動小数点で計算
        return (waveValue * volume * 2184.5).toInt().coerceIn(-32768, 32767)
    }
    
    /**
     * Square 2チャンネルのサンプルを生成する（Square 1と同様）。
     *
     * @param soundCycle サウンドサイクル単位のタイミング（Double型で精度を保持）
     */
    private fun generateSquare2Sample(soundCycle: Double): Int {
        // チャンネルが無効な場合は即座に0を返す（パフォーマンス向上）
        if (!square2State.enabled) {
            return 0
        }
        
        val nr21 = soundRegs[0x05] // NR21 (Length & Duty)
        val nr23 = soundRegs[0x07] // NR23 (Frequency Low)
        val nr24 = soundRegs[0x08] // NR24 (Frequency High & Trigger)
        
        // 周波数を計算
        val frequency = ((nr24.toInt() and 0x07) shl 8) or nr23.toInt()
        if (frequency == 0 || frequency >= 2048) {
            return 0
        }
        
        // 周期を計算
        // より正確な計算のため、浮動小数点を使用
        val period = (2048.0 - frequency) * 8.0
        if (period <= 0) {
            return 0
        }
        
        // デューティ比を取得
        val dutyCycle = (nr21.toInt() shr 6) and 0x03
        val dutyPattern = when (dutyCycle) {
            0 -> 0b00000001 // 12.5% (1/8)
            1 -> 0b00000011 // 25% (2/8)
            2 -> 0b00001111 // 50% (4/8)
            3 -> 0b11111100 // 75% (6/8)
            else -> 0b00001111
        }
        
        // エンベロープボリュームを使用
        // 実機では、エンベロープのボリュームが0でも、チャンネルは有効のまま
        // ただし、サンプル生成時にボリュームが0の場合は、無音を返す
        val volume = square2State.envelopeVolume
        if (volume == 0) {
            return 0
        }
        
        // 波形を生成
        // 浮動小数点で計算してから整数に変換することで、精度を向上
        // periodはDouble型なので、浮動小数点の剰余演算を使用
        val positionInPeriod = soundCycle % period
        // デューティ比の位置を計算（0-7の範囲）
        val dutyPositionDouble = (positionInPeriod * 8.0 / period)
        val dutyPosition = dutyPositionDouble.toInt().coerceIn(0, 7)
        val waveValue = if ((dutyPattern and (1 shl dutyPosition)) != 0) 1 else -1
        
        // ボリュームを適用（0-15を0-32767にスケール）
        // より正確なスケーリングのため、浮動小数点で計算
        return (waveValue * volume * 2184.5).toInt().coerceIn(-32768, 32767)
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
        var lfsrCounter = 0 // LFSR更新用カウンタ（整数）
        var lfsrCounterAccumulator = 0.0 // LFSR更新用カウンタ（浮動小数点、サンプル生成時の精度向上のため）
    }
    
    /**
     * Square 1チャンネルの状態を更新
     */
    private fun updateSquare1Channel(soundCycles: Int) {
        val nr11 = soundRegs[0x01]
        val nr12 = soundRegs[0x02]
        val nr14 = soundRegs[0x04]
        
        // 長さカウンタの更新（256Hz = 524288 / 256 = 2048サウンドサイクルごと）
        if (square1State.lengthEnabled && square1State.lengthCounter > 0) {
            square1State.lengthCounterAccumulator += soundCycles
            val lengthStep = 2048
            while (square1State.lengthCounterAccumulator >= lengthStep && square1State.lengthCounter > 0) {
                square1State.lengthCounterAccumulator -= lengthStep
                square1State.lengthCounter--
                if (square1State.lengthCounter <= 0) {
                    square1State.enabled = false
                    break
                }
            }
        }
        
        // エンベロープの更新（64Hz = 524288 / 64 = 8192サウンドサイクルごと）
        // 実機では、エンベロープのperiodが0の場合、エンベロープは無効になり、初期ボリュームがそのまま使われる
        val envelopePeriod = nr12.toInt() and 0x07
        if (envelopePeriod > 0 && square1State.enabled) {
            square1State.envelopeCounter += soundCycles
            val envelopeStep = 8192 / envelopePeriod.coerceAtLeast(1)
            while (square1State.envelopeCounter >= envelopeStep) {
                square1State.envelopeCounter -= envelopeStep
                val direction = if ((nr12.toInt() and 0x08) != 0) 1 else -1
                square1State.envelopeVolume += direction
                square1State.envelopeVolume = square1State.envelopeVolume.coerceIn(0, 15)
                // 実機では、エンベロープのボリュームが0または15に達しても、エンベロープの更新は停止するが、チャンネルは有効のまま
                if (square1State.envelopeVolume == 0 || square1State.envelopeVolume == 15) {
                    // エンベロープが最大/最小に達したら停止
                    break
                }
            }
        }
        // envelopePeriodが0の場合、エンベロープは無効で、初期ボリュームがそのまま使われる（既に設定されている）
    }
    
    /**
     * Square 2チャンネルの状態を更新
     */
    private fun updateSquare2Channel(soundCycles: Int) {
        val nr21 = soundRegs[0x05]
        val nr22 = soundRegs[0x06]
        val nr24 = soundRegs[0x08]
        
        // 長さカウンタの更新（256Hz = 2048サウンドサイクルごと）
        if (square2State.lengthEnabled && square2State.lengthCounter > 0) {
            square2State.lengthCounterAccumulator += soundCycles
            val lengthStep = 2048
            while (square2State.lengthCounterAccumulator >= lengthStep && square2State.lengthCounter > 0) {
                square2State.lengthCounterAccumulator -= lengthStep
                square2State.lengthCounter--
                if (square2State.lengthCounter <= 0) {
                    square2State.enabled = false
                    break
                }
            }
        }
        
        // エンベロープの更新（64Hz = 8192サウンドサイクルごと）
        // 実機では、エンベロープのperiodが0の場合、エンベロープは無効になり、初期ボリュームがそのまま使われる
        val envelopePeriod = nr22.toInt() and 0x07
        if (envelopePeriod > 0 && square2State.enabled) {
            square2State.envelopeCounter += soundCycles
            val envelopeStep = 8192 / envelopePeriod.coerceAtLeast(1)
            while (square2State.envelopeCounter >= envelopeStep) {
                square2State.envelopeCounter -= envelopeStep
                val direction = if ((nr22.toInt() and 0x08) != 0) 1 else -1
                square2State.envelopeVolume += direction
                square2State.envelopeVolume = square2State.envelopeVolume.coerceIn(0, 15)
                // 実機では、エンベロープのボリュームが0または15に達しても、エンベロープの更新は停止するが、チャンネルは有効のまま
                if (square2State.envelopeVolume == 0 || square2State.envelopeVolume == 15) {
                    break
                }
            }
        }
        // envelopePeriodが0の場合、エンベロープは無効で、初期ボリュームがそのまま使われる（既に設定されている）
    }
    
    /**
     * Waveチャンネルの状態を更新
     */
    private fun updateWaveChannel(soundCycles: Int) {
        val nr33 = soundRegs[0x0D] // NR33 (Frequency Low)
        val nr34 = soundRegs[0x0E] // NR34 (Frequency High & Trigger)
        
        // 長さカウンタの更新（256Hz = 2048サウンドサイクルごと）
        if (waveState.lengthEnabled && waveState.lengthCounter > 0) {
            waveState.lengthCounterAccumulator += soundCycles
            val lengthStep = 2048
            while (waveState.lengthCounterAccumulator >= lengthStep && waveState.lengthCounter > 0) {
                waveState.lengthCounterAccumulator -= lengthStep
                waveState.lengthCounter--
                if (waveState.lengthCounter <= 0) {
                    waveState.enabled = false
                    break
                }
            }
        }
        
        // 波形位置の更新（周波数に基づく）
        // 注意: 波形位置はサンプル生成時に直接計算するため、ここでは更新しない
        // 実機では、波形位置は周波数に基づいて自動的に更新される
    }
    
    /**
     * Noiseチャンネルの状態を更新
     */
    private fun updateNoiseChannel(soundCycles: Int) {
        val nr42 = soundRegs[0x11]
        val nr43 = soundRegs[0x12]
        val nr44 = soundRegs[0x13]
        
        // 長さカウンタの更新（256Hz = 2048サウンドサイクルごと）
        if (noiseState.lengthEnabled && noiseState.lengthCounter > 0) {
            noiseState.lengthCounterAccumulator += soundCycles
            val lengthStep = 2048
            while (noiseState.lengthCounterAccumulator >= lengthStep && noiseState.lengthCounter > 0) {
                noiseState.lengthCounterAccumulator -= lengthStep
                noiseState.lengthCounter--
                if (noiseState.lengthCounter <= 0) {
                    noiseState.enabled = false
                    break
                }
            }
        }
        
        // エンベロープの更新（64Hz = 8192サウンドサイクルごと）
        val envelopePeriod = nr42.toInt() and 0x07
        if (envelopePeriod > 0 && noiseState.enabled) {
            noiseState.envelopeCounter += soundCycles
            val envelopeStep = 8192 / envelopePeriod.coerceAtLeast(1)
            while (noiseState.envelopeCounter >= envelopeStep) {
                noiseState.envelopeCounter -= envelopeStep
                val direction = if ((nr42.toInt() and 0x08) != 0) 1 else -1
                noiseState.envelopeVolume += direction
                noiseState.envelopeVolume = noiseState.envelopeVolume.coerceIn(0, 15)
                if (noiseState.envelopeVolume == 0 || noiseState.envelopeVolume == 15) {
                    break
                }
            }
        }
        
        // LFSRの更新（ノイズ生成）
        if (noiseState.enabled) {
            val divisor = when (nr43.toInt() and 0x07) {
                0 -> 8
                else -> (nr43.toInt() and 0x07) * 16
            }
            val shift = (nr43.toInt() shr 4) and 0x0F
            // 周期 = (divisor << shift) * 8 サウンドサイクル
            // ただし、shiftが大きすぎる場合は制限
            val period = if (shift < 16) {
                (divisor shl shift) * 8L
            } else {
                0L // 無効な値
            }
            
            if (period > 0) {
                // 浮動小数点で累積（精度向上）
                noiseState.lfsrCounterAccumulator += soundCycles.toDouble()
                val periodDouble = period.toDouble()
                while (noiseState.lfsrCounterAccumulator >= periodDouble) {
                    noiseState.lfsrCounterAccumulator -= periodDouble
                    
                    // LFSRを更新
                    // XOR = bit 0 XOR bit 1
                    val xor = (noiseState.lfsr xor (noiseState.lfsr shr 1)) and 1
                    noiseState.lfsr = noiseState.lfsr shr 1
                    if (xor != 0) {
                        noiseState.lfsr = noiseState.lfsr or 0x4000
                    }
                    
                    // 7bitモード（bit 3がセットされている場合）
                    if ((nr43.toInt() and 0x08) != 0) {
                        noiseState.lfsr = noiseState.lfsr and 0x7F
                    }
                }
                // 整数カウンタも更新（後方互換性のため）
                noiseState.lfsrCounter = noiseState.lfsrCounterAccumulator.toInt()
            }
        }
    }
    
    /**
     * Waveチャンネルのサンプルを生成する。
     *
     * @param soundCycle サウンドサイクル単位のタイミング（Double型で精度を保持）
     */
    private fun generateWaveSample(soundCycle: Double): Int {
        // チャンネルが無効な場合は即座に0を返す（パフォーマンス向上）
        if (!waveState.enabled) {
            return 0
        }
        
        val nr32 = soundRegs[0x0C] // NR32 (Volume)
        val nr33 = soundRegs[0x0D] // NR33 (Frequency Low)
        val nr34 = soundRegs[0x0E] // NR34 (Frequency High & Trigger)
        
        // 周波数を計算
        val frequency = ((nr34.toInt() and 0x07) shl 8) or nr33.toInt()
        if (frequency == 0 || frequency >= 2048) {
            return 0
        }
        
        // 周期を計算
        // より正確な計算のため、浮動小数点を使用
        val period = (2048.0 - frequency) * 8.0
        if (period <= 0) {
            return 0
        }
        
        // 波形位置を計算（32サンプルの波形）
        // より正確な計算のため、浮動小数点を使用
        val position = soundCycle % period
        val sampleIndexDouble = (position * 32.0 / period)
        val sampleIndex = sampleIndexDouble.toInt().coerceIn(0, 31)
        
        // 波形RAMからサンプルを読み取り（16バイト = 32サンプル）
        val byteIndex = (sampleIndex / 2).coerceIn(0, 15)
        val nibble = if (sampleIndex % 2 == 0) {
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
        // 16bit範囲にスケール（より正確な計算）
        // 実機では、Waveチャンネルの出力は -8 to 7 の範囲で、16bit範囲にスケールされる
        return (sample * 4096.0).toInt().coerceIn(-32768, 32767)
    }
    
    /**
     * Noiseチャンネルのサンプルを生成する。
     *
     * @param soundCycle サウンドサイクル単位のタイミング（Double型で精度を保持）
     */
    private fun generateNoiseSample(soundCycle: Double): Int {
        val nr43 = soundRegs[0x12] // NR43 (Polynomial)
        
        if (!noiseState.enabled) {
            return 0
        }
        
        // エンベロープボリュームをチェック
        val volume = noiseState.envelopeVolume
        if (volume == 0) {
            return 0
        }
        
        // 周波数を計算
        val divisor = when (nr43.toInt() and 0x07) {
            0 -> 8
            else -> (nr43.toInt() and 0x07) * 16
        }
        val shift = (nr43.toInt() shr 4) and 0x0F
        // 周期をDouble型で計算（精度向上）
        val period = if (shift < 16) {
            (divisor shl shift) * 8.0
        } else {
            0.0
        }
        
        if (period <= 0) {
            return 0
        }
        
        // サンプル生成時にLFSRの状態を正確に反映するため、
        // 現在のサウンドサイクルに基づいてLFSRの状態を計算
        // 実機では、LFSRは定期的に更新されるため、サンプル生成時には
        // 現在のLFSR値を取得するだけで十分（updateNoiseChannelで既に更新されている）
        // ただし、より正確なタイミングのため、サンプル生成時のLFSRの状態を補間
        
        // 現在のLFSRカウンタの位置を計算
        val currentLfsrCounter = noiseState.lfsrCounterAccumulator % period
        
        // LFSRはupdateNoiseChannelで更新されているため、現在の値をそのまま使用
        // ノイズ値を取得（LFSRの最下位ビット）
        val noiseValue = if ((noiseState.lfsr and 1) != 0) 1 else -1
        
        // ボリュームを適用（0-15を0-32767にスケール）
        // 実機では、ボリュームは0-15で、出力は noiseValue * volume で計算される
        // 16bit範囲にスケールするため、2184.5を掛ける（15 * 2184.5 ≈ 32767.5）
        // より正確なスケーリングのため、浮動小数点で計算
        return (noiseValue * volume * 2184.5).toInt().coerceIn(-32768, 32767)
    }
    
    /**
     * Square 1のスイープ処理を更新
     */
    private fun updateSweep(soundCycles: Int) {
        if (!sweepEnabled || sweepPeriod == 0 || !square1State.enabled) {
            return
        }
        
        // 最初の更新は1サイクル遅延させる（実機の仕様）
        if (!sweepInitialized) {
            sweepInitialized = true
            // 最初の更新を1サウンドサイクル遅延させる
            if (soundCycles > 0) {
                sweepCounter += soundCycles - 1
            }
            return
        }
        
        sweepCounter += soundCycles
        // 128Hz = 524288 / 128 = 4096サウンドサイクルごと
        // sweepPeriodが0の場合は無効（既にチェック済み）
        val sweepStep = 4096 / sweepPeriod.coerceAtLeast(1)
        while (sweepCounter >= sweepStep) {
            sweepCounter -= sweepStep
            
            // 周波数を更新
            val change = shadowFrequency shr sweepShift
            val newFrequency = if (sweepNegate) {
                shadowFrequency - change
            } else {
                shadowFrequency + change
            }
            
            if (newFrequency < 0 || newFrequency > 2047) {
                square1State.enabled = false
                sweepEnabled = false
            } else {
                shadowFrequency = newFrequency
                // 周波数レジスタを更新
                soundRegs[0x03] = (newFrequency and 0xFF).toUByte()
                soundRegs[0x04] = ((soundRegs[0x04].toInt() and 0xF8) or (newFrequency shr 8)).toUByte()
            }
        }
    }
}


