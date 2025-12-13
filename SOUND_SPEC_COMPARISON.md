# Game Boy 音声仕様と実装の比較

## Game Boy 音声仕様（実機）

### 基本仕様
- **CPU周波数**: 4.194304 MHz
- **サウンド周波数**: CPU周波数 / 8 = 524288 Hz
- **サンプリングレート**: 約44.1kHz（ハードウェア生成）
- **1フレーム**: 70224 CPUサイクル = 70224 / 8 = 8778 サウンドサイクル

### チャンネル仕様

#### Square 1 チャンネル (0xFF10-0xFF14)
- **NR10 (0xFF10)**: Sweep
  - Bit 6-4: Sweep period (0-7)
  - Bit 3: Sweep direction (0=増加, 1=減少)
  - Bit 2-0: Sweep shift (0-7)
- **NR11 (0xFF11)**: Length & Duty
  - Bit 7-6: Duty cycle (0=12.5%, 1=25%, 2=50%, 3=75%)
  - Bit 5-0: Length counter (64 - value)
- **NR12 (0xFF12)**: Volume & Envelope
  - Bit 7-4: Initial volume (0-15)
  - Bit 3: Envelope direction (0=減少, 1=増加)
  - Bit 2-0: Envelope period (0-7, 0=無効)
- **NR13 (0xFF13)**: Frequency Low (8bit)
- **NR14 (0xFF14)**: Frequency High & Trigger
  - Bit 7: Trigger (1=有効化)
  - Bit 6: Length enable
  - Bit 2-0: Frequency High (3bit)
  - **周波数計算**: frequency = (NR14[2:0] << 8) | NR13
  - **出力周波数**: 131072 / (2048 - frequency) Hz
  - **周期（サウンドサイクル）**: (2048 - frequency) * 8

#### Square 2 チャンネル (0xFF15-0xFF19)
- Square 1と同様（スイープなし）
- **NR21 (0xFF15)**: Length & Duty
- **NR22 (0xFF16)**: Volume & Envelope
- **NR23 (0xFF17)**: Frequency Low
- **NR24 (0xFF18)**: Frequency High & Trigger

#### Wave チャンネル (0xFF1A-0xFF1E)
- **NR30 (0xFF1A)**: Wave Enable
  - Bit 7: Wave enable (1=有効)
- **NR31 (0xFF1B)**: Length
  - Bit 7-0: Length counter (256 - value)
- **NR32 (0xFF1C)**: Volume
  - Bit 6-5: Volume shift (0=無音, 1=100%, 2=50%, 3=25%)
- **NR33 (0xFF1D)**: Frequency Low
- **NR34 (0xFF1E)**: Frequency High & Trigger
  - Bit 7: Trigger
  - Bit 6: Length enable
  - Bit 2-0: Frequency High
- **波形RAM (0xFF30-0xFF3F)**: 32サンプル（各4bit、16バイト）

#### Noise チャンネル (0xFF1F-0xFF23)
- **NR41 (0xFF20)**: Length
  - Bit 5-0: Length counter (64 - value)
- **NR42 (0xFF21)**: Volume & Envelope
- **NR43 (0xFF22)**: Polynomial
  - Bit 7-4: Shift clock frequency
  - Bit 3: Counter step (0=15bit, 1=7bit)
  - Bit 2-0: Dividing ratio
- **NR44 (0xFF23)**: Trigger
  - Bit 7: Trigger
  - Bit 6: Length enable

#### 制御レジスタ
- **NR50 (0xFF24)**: Master Volume
  - Bit 7-4: Left volume (0-7)
  - Bit 3: Vin to left
  - Bit 2-0: Right volume (0-7)
- **NR51 (0xFF25)**: Channel Select
  - Bit 7: Noise to left
  - Bit 6: Wave to left
  - Bit 5: Square 2 to left
  - Bit 4: Square 1 to left
  - Bit 3: Noise to right
  - Bit 2: Wave to right
  - Bit 1: Square 2 to right
  - Bit 0: Square 1 to right
- **NR52 (0xFF26)**: Sound Enable
  - Bit 7: Sound enable (1=有効)
  - Bit 3: Noise on
  - Bit 2: Wave on
  - Bit 1: Square 2 on
  - Bit 0: Square 1 on

### タイミング仕様
- **長さカウンタ**: 256Hz = 524288 / 256 = 2048 サウンドサイクルごと
- **エンベロープ**: 64Hz = 524288 / 64 = 8192 サウンドサイクルごと
- **スイープ**: 128Hz = 524288 / 128 = 4096 サウンドサイクルごと

---

## 現在の実装との比較

### ✅ 正しく実装されている項目

1. **基本周波数計算**
   - CPU周波数: 4.194304 MHz ✅
   - サウンド周波数: CPU / 8 = 524288 Hz ✅
   - サンプリングレート: 44100 Hz ✅

2. **レジスタアドレス範囲**
   - Square 1: 0xFF10-0xFF14 ✅
   - Square 2: 0xFF15-0xFF19 ✅
   - Wave: 0xFF1A-0xFF1E ✅
   - Noise: 0xFF1F-0xFF23 ✅
   - 制御: 0xFF24-0xFF26 ✅
   - 波形RAM: 0xFF30-0xFF3F ✅

3. **Squareチャンネルの周波数計算**
   - 周波数 = (NR14[2:0] << 8) | NR13 ✅
   - 周期 = (2048 - frequency) * 8 サウンドサイクル ✅

4. **デューティ比パターン**
   - 0 = 12.5% (0b00000001) ✅
   - 1 = 25% (0b00000011) ✅
   - 2 = 50% (0b00001111) ✅
   - 3 = 75% (0b11111100) ✅

5. **エンベロープ処理**
   - 初期ボリューム: NR12[7:4] ✅
   - 方向: NR12[3] ✅
   - 周期: NR12[2:0] ✅
   - 更新タイミング: 64Hz = 8192サウンドサイクル ✅

6. **長さカウンタ**
   - Square 1/2/Noise: 64 - value ✅
   - Wave: 256 - value ✅
   - 更新タイミング: 256Hz = 2048サウンドサイクル ✅

7. **NR52レジスタの読み取り**
   - 各チャンネルの有効状態を反映 ✅

### ⚠️ 問題がある項目（修正済み）

1. **スイープ処理の更新タイミング** ✅ 修正済み
   - **実機仕様**: 128Hz = 4096サウンドサイクルごと
   - **修正前**: `16384 / sweepPeriod` で計算
   - **修正後**: `4096 / sweepPeriod` (sweepPeriod > 0の場合) ✅

2. **Waveチャンネルの周波数計算**
   - **実機仕様**: Squareチャンネルと同じ計算式
   - **現在の実装**: `((nr34.toInt() and 0x07) shl 8) or nr33.toInt()`
   - **問題**: NR31ではなくNR33を使用している（正しい）
   - **確認**: NR31はLength、NR33はFrequency Low（正しい）

3. **Waveチャンネルの波形読み取り**
   - **実機仕様**: 32サンプル（各4bit）を周波数に基づいて繰り返し再生
   - **現在の実装**: `sampleIndex = ((position * 32) / periodInt)`
   - **問題**: 計算式は正しいが、精度の問題がある可能性

4. **NoiseチャンネルのLFSR更新**
   - **実機仕様**: 周波数に基づいて定期的に更新
   - **現在の実装**: `updateNoiseChannel`で更新
   - **問題**: 更新タイミングの計算が正しいか確認が必要

5. **NR52レジスタの書き込み制限**
   - **実機仕様**: NR52[7]=0の場合、一部のレジスタは書き込み不可
   - **現在の実装**: チェックしているが、完全ではない可能性

6. **サンプル生成のタイミング**
   - **実機仕様**: 各フレームで735サンプル生成（44100 / 60 = 735）
   - **現在の実装**: `SAMPLES_PER_FRAME = 44100 / 60 = 735` ✅
   - **問題**: フレーム開始時点の計算が正確か確認が必要

### ❌ 未実装または間違っている項目（修正済み）

1. **NR30 (Wave Enable) レジスタ** ✅ 修正済み
   - **実機仕様**: 0xFF1A (NR30) の bit 7 でWaveチャンネルを有効/無効
   - **修正前**: 未実装（NR34のTriggerのみで制御）
   - **修正後**: NR30の処理を実装、NR34のTrigger時にNR30をチェック ✅

2. **NR52レジスタの書き込み時の処理** ✅ 修正済み
   - **実機仕様**: NR52[7]=0に書き込むと、全チャンネルが無効化される
   - **修正前**: 未実装
   - **修正後**: NR52[7]=0に書き込むと全チャンネルを無効化、NR52[7]=0の場合チャンネルレジスタへの書き込みを無視 ✅

3. **スイープ処理の詳細** ✅ 修正済み
   - **実機仕様**: スイープ処理は最初の更新が遅延する（1サイクル遅れ）
   - **修正前**: 即座に更新（簡易実装）
   - **修正後**: 最初の更新を1サウンドサイクル遅延させる仕様を実装 ✅

4. **Waveチャンネルの波形RAMアクセス制限** ✅ 修正済み
   - **実機仕様**: Waveチャンネルが有効な場合、波形RAMへのアクセスが制限される
   - **修正前**: 制限なし
   - **修正後**: Waveチャンネルが有効な場合、波形RAMへの書き込みを無視 ✅

5. **エンベロープの初期化タイミング** ✅ 修正済み
   - **実機仕様**: Trigger時にエンベロープカウンタをリセット
   - **修正前**: 実装されているが、タイミングが正確か確認が必要
   - **修正後**: Trigger時にエンベロープカウンタを0にリセット、エンベロープボリュームを初期化 ✅

6. **長さカウンタの初期化** ✅ 修正済み
   - **実機仕様**: Trigger時に長さカウンタをリセット（Length Enableが有効な場合）
   - **修正前**: 実装されているが、値の計算が正確か確認が必要
   - **修正後**: 長さカウンタの更新を累積器ベースに変更し、精度を向上 ✅

---

## 修正が必要な項目（優先順位順）

### 優先度: 高 ✅ すべて修正済み

1. **スイープ処理の更新タイミング修正** ✅
   - 修正前: `16384 / sweepPeriod`
   - 修正後: `4096 / sweepPeriod` (sweepPeriod > 0の場合)

2. **NR30 (Wave Enable) レジスタの実装** ✅
   - Waveチャンネルの有効/無効をNR30で制御

3. **NR52レジスタの書き込み時の処理** ✅
   - NR52[7]=0に書き込むと全チャンネルを無効化
   - NR52[7]=0の場合、チャンネルレジスタへの書き込みを無視

### 優先度: 中 ✅ すべて修正済み

4. **Waveチャンネルの波形RAMアクセス制限** ✅
   - Waveチャンネルが有効な場合の制限を実装

5. **スイープ処理の遅延実装** ✅
   - 最初の更新が1サイクル遅れる仕様を実装

6. **サンプル生成のタイミング精度向上** ✅
   - フレーム開始時点の計算をより正確に（累積器ベースの更新に変更）

### 優先度: 低 ✅ すべて修正済み

7. **エンベロープ/長さカウンタの初期化タイミング確認** ✅
   - Trigger時にエンベロープカウンタと長さカウンタをリセット
   - 長さカウンタの更新を累積器ベースに変更し、精度を向上

8. **NoiseチャンネルのLFSR更新タイミング確認** ✅
   - LFSR更新タイミングの計算を改善
   - shift値の範囲チェックを追加

