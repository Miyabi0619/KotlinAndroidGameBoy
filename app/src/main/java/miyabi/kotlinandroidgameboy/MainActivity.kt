package miyabi.kotlinandroidgameboy

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import gb.core.api.CoreError
import gb.core.api.CoreResult
import gb.core.api.InputState
import miyabi.kotlinandroidgameboy.emulator.CoreProvider
import miyabi.kotlinandroidgameboy.emulator.GameLoop
import miyabi.kotlinandroidgameboy.ui.theme.GameBoyTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val core = CoreProvider.provideCore()
            val gameLoop = remember { GameLoop(core) }

            GameBoyTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                ) { innerPadding ->
                    gameScreen(
                        modifier = Modifier.padding(innerPadding),
                        gameLoop = gameLoop,
                    )
                }
            }
        }
    }

    // dispatchKeyEvent を使うことで、Compose のフォーカスナビゲーション処理より前に
    // キーイベントを消費できる（onKeyDown/onKeyUp は Compose 処理後に呼ばれるため不十分）
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val isDown = event.action == KeyEvent.ACTION_DOWN
        val isUp = event.action == KeyEvent.ACTION_UP
        if (!isDown && !isUp) return super.dispatchKeyEvent(event)
        val handled = InputStateHolder.updateFromKey(event.keyCode, isDown = isDown)
        return if (handled) true else super.dispatchKeyEvent(event)
    }
}

@Composable
private fun gameScreen(
    modifier: Modifier = Modifier,
    gameLoop: GameLoop,
) {
    var frameBuffer by remember { mutableStateOf<IntArray?>(null) }
    var frameIndex by remember { mutableStateOf(0L) }
    var romLoaded by remember { mutableStateOf(false) }
    var isRunning by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var currentSavFileName by remember { mutableStateOf<String?>(null) }

    // 画面上のボタンからの入力
    var uiInput by remember { mutableStateOf(InputState()) }

    // ROM ファイル選択ランチャ
    val context = androidx.compose.ui.platform.LocalContext.current
    val romPickerLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument(),
            onResult = { uri ->
                if (uri == null) {
                    return@rememberLauncherForActivityResult
                }
                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                } catch (_: SecurityException) {
                    // 既に権限を持っている場合などは無視
                }

                val romBytes =
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        input.readBytes()
                    }

                if (romBytes == null) {
                    android.util.Log.e("GameLoop", "Failed to read ROM file")
                    errorMessage = "ROM を読み込めませんでした。"
                    romLoaded = false
                    return@rememberLauncherForActivityResult
                }

                android.util.Log.d("GameLoop", "ROM loaded: ${romBytes.size} bytes")
                when (val result = gameLoop.loadRomAndReset(romBytes)) {
                    is CoreResult.Success -> {
                        android.util.Log.d("GameLoop", "ROM loaded successfully")
                        // セーブファイル名を導出し、バッテリバックアップがあればロード
                        val savName = deriveSavFileName(uri)
                        currentSavFileName = savName
                        loadCartridgeRam(context, savName, gameLoop)
                        romLoaded = true
                        errorMessage = null
                    }

                    is CoreResult.Error -> {
                        android.util.Log.e("GameLoop", "ROM load error: ${toErrorMessage(result.error)}")
                        romLoaded = false
                        errorMessage = toErrorMessage(result.error)
                    }
                }
            },
        )

    // オーディオ出力の初期化（ステレオ形式）
    val audioTrack =
        remember {
            val sampleRate = 44100
            val channelConfig = AudioFormat.CHANNEL_OUT_STEREO // ステレオ出力に変更
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val bufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat) * 2

            AudioTrack
                .Builder()
                .setAudioAttributes(
                    AudioAttributes
                        .Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                ).setAudioFormat(
                    AudioFormat
                        .Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(audioFormat)
                        .setChannelMask(channelConfig)
                        .build(),
                ).setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }
    // オーディオ出力の開始/停止
    LaunchedEffect(isRunning) {
        if (isRunning) {
            audioTrack.play()
        } else {
            audioTrack.pause()
            audioTrack.flush()
        }
    }
    // シンプルなゲームループ（UI スレッドを塞がないようにコルーチンで）
    LaunchedEffect(isRunning, romLoaded) {
        android.util.Log.d("GameLoop", "LaunchedEffect: isRunning=$isRunning, romLoaded=$romLoaded")
        if (!isRunning || !romLoaded) {
            android.util.Log.d("GameLoop", "Game loop not started: isRunning=$isRunning, romLoaded=$romLoaded")
            return@LaunchedEffect
        }
        android.util.Log.d("GameLoop", "Game loop started")
        var frameCount = 0
        var skippedFrames = 0
        // フレームレート制御用の変数
        val targetFrameTime = 1000.0 / 59.7275 // 約16.74ms（実機のフレームレート）
        var lastFrameTime = System.nanoTime() / 1_000_000.0 // より高精度なタイミング制御
        var audioBufferSize = 0 // 音声バッファのサイズを追跡
        while (isRunning && romLoaded) {
            val frameStartTime = System.nanoTime() / 1_000_000.0

            // フレームスキップ判定：処理が遅れている場合は描画をスキップ
            // ただし、音声は常に処理してタイミングを保つ
            // スキップは極力避ける（ゲームの可玩性を保つため）
            val timeSinceLastFrame =
                if (frameCount > 0) {
                    frameStartTime - lastFrameTime
                } else {
                    targetFrameTime // 最初のフレームはスキップしない
                }
            // フレームスキップを大幅に削減：スキップ率が高すぎる場合は無効化
            val skipRate = if (frameCount > 0) (skippedFrames * 100.0 / frameCount) else 0.0
            // スキップ率が20%を超える場合は、スキップを無効化（ゲームの可玩性を保つため）
            val shouldSkipRender =
                skipRate < 20.0 &&
                    timeSinceLastFrame < targetFrameTime * 0.2 &&
                    frameCount > 30 // 最初の30フレームはスキップしない

            val mergedInput = mergeInput(uiInput, InputStateHolder.controllerInput.value)
            when (val result = gameLoop.runSingleFrame(mergedInput)) {
                is CoreResult.Success -> {
                    // オーディオサンプルは常に出力（タイミングを保つため）
                    result.value.audioSamples?.let { samples ->
                        if (audioTrack.playState == AudioTrack.PLAYSTATE_PLAYING) {
                            val written = audioTrack.write(samples, 0, samples.size)
                            audioBufferSize += samples.size - written

                            // バッファが溜まりすぎている場合は警告
                            if (audioBufferSize > samples.size * 10) {
                                if (frameCount % 300 == 0) {
                                    android.util.Log.w("GameLoop", "Audio buffer overflow: $audioBufferSize samples")
                                }
                                audioBufferSize = samples.size * 5 // リセット
                            }
                        }
                    }

                    // 描画はスキップ判定に基づいて処理
                    if (!shouldSkipRender) {
                        frameBuffer = result.value.frameBuffer
                        frameIndex = result.value.stats?.frameIndex ?: 0L
                    } else {
                        skippedFrames++
                    }

                    errorMessage = null
                    frameCount++

                    if (frameCount % 300 == 0) {
                        val actualFps = 1000.0 / (timeSinceLastFrame.coerceAtLeast(0.1))
                        val skipRate = if (frameCount > 0) (skippedFrames * 100.0 / frameCount) else 0.0
                        android.util.Log.d(
                            "GameLoop",
                            "Frame $frameCount: FPS=${String.format(java.util.Locale.US, "%.2f", actualFps)}, " +
                                "skipped=$skippedFrames (${String.format(java.util.Locale.US, "%.1f", skipRate)}%), " +
                                "audioBuffer=$audioBufferSize",
                        )
                    }
                }

                is CoreResult.Error -> {
                    android.util.Log.e("GameLoop", "Error in frame: ${toErrorMessage(result.error)}")
                    errorMessage = toErrorMessage(result.error)
                    isRunning = false
                }
            }

            // フレームレートを正確に保つため、実機のフレームレート（59.7275Hz）に合わせる
            val frameEndTime = System.nanoTime() / 1_000_000.0
            val elapsed = frameEndTime - frameStartTime
            val sleepTime = (targetFrameTime - elapsed).coerceAtLeast(0.0)

            // フレームレートが遅すぎる場合は警告
            if (elapsed > targetFrameTime * 1.5) {
                if (frameCount % 300 == 0) {
                    val actualFps = 1000.0 / elapsed
                    val fpsStr = String.format(java.util.Locale.US, "%.2f", actualFps)
                    android.util.Log.w(
                        "GameLoop",
                        "Frame rate too slow: $fpsStr Hz (target: 59.73 Hz)",
                    )
                }
            }

            // より正確なタイミング制御のため、残り時間を待機
            // フレームレートを正確に保つため、処理時間を考慮して待機時間を調整
            // ただし、処理が遅れている場合は待機しない（フレームスキップで対応）
            if (sleepTime > 0.1 && elapsed < targetFrameTime * 1.2) {
                kotlinx.coroutines.delay(sleepTime.toLong())
            }

            lastFrameTime = frameEndTime
        }
        // ゲームループ停止時にセーブデータを保存
        currentSavFileName?.let { savName ->
            saveCartridgeRam(context, savName, gameLoop)
        }
        android.util.Log.d("GameLoop", "Game loop stopped")
    }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        // 画面上部: ROM ロードとエラー表示
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        romPickerLauncher.launch(arrayOf("*/*"))
                    },
                ) {
                    Text("ROM を選択")
                }
                Button(
                    onClick = {
                        if (romLoaded) {
                            isRunning = !isRunning
                        }
                    },
                    enabled = romLoaded,
                ) {
                    Text(if (isRunning) "一時停止" else "開始")
                }
            }
            if (!romLoaded) {
                Text(
                    text = "ROM が読み込まれていません。",
                    color = MaterialTheme.colorScheme.error,
                )
            }
            errorMessage?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        // 中央: フレームバッファを描画
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            val pixels = frameBuffer
            if (pixels != null && pixels.size == 160 * 144) {
                val bitmap =
                    remember {
                        android.util.Log.d("GameLoop", "Creating bitmap (initial)")
                        Bitmap.createBitmap(160, 144, Bitmap.Config.ARGB_8888)
                    }
                // frameIndex が変わるたびにビットマップを更新
                LaunchedEffect(frameIndex) {
                    bitmap.setPixels(pixels, 0, 160, 0, 0, 160, 144)
                }
                Image(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(160f / 144f),
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Game Boy Screen",
                )
            } else {
                Text(
                    "フレームがまだ描画されていません。 (pixels=${pixels?.size ?: "null"})",
                )
            }
        }

        // 下部: 画面上のボタン入力
        inputButtons(
            uiInputState = remember { mutableStateOf(uiInput) },
            onInputChanged = { uiInput = it },
        )
    }
}

@Composable
private fun inputButtons(
    uiInputState: MutableState<InputState>,
    onInputChanged: (InputState) -> Unit,
) {
    // ボタンを押している間だけON、離すとOFF（ゲームボーイ本来の挙動）
    fun setButton(
        isPressed: Boolean,
        setter: (InputState, Boolean) -> InputState,
    ) {
        val newState = setter(uiInputState.value, isPressed)
        uiInputState.value = newState
        onInputChanged(newState)
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 十字キー
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            androidx.compose.material3.FilledTonalButton(
                onClick = { },
                modifier =
                    Modifier
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                setButton(true) { state, pressed -> state.copy(up = pressed) }
                                waitForUpOrCancellation()
                                setButton(false) { state, _ -> state.copy(up = false) }
                            }
                        },
            ) {
                Text("↑")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            androidx.compose.material3.FilledTonalButton(
                onClick = { },
                modifier =
                    Modifier
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                setButton(true) { state, pressed -> state.copy(left = pressed) }
                                waitForUpOrCancellation()
                                setButton(false) { state, _ -> state.copy(left = false) }
                            }
                        },
            ) {
                Text("←")
            }
            androidx.compose.material3.FilledTonalButton(
                onClick = { },
                modifier =
                    Modifier
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                setButton(true) { state, pressed -> state.copy(down = pressed) }
                                waitForUpOrCancellation()
                                setButton(false) { state, _ -> state.copy(down = false) }
                            }
                        },
            ) {
                Text("↓")
            }
            androidx.compose.material3.FilledTonalButton(
                onClick = { },
                modifier =
                    Modifier
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                setButton(true) { state, pressed -> state.copy(right = pressed) }
                                waitForUpOrCancellation()
                                setButton(false) { state, _ -> state.copy(right = false) }
                            }
                        },
            ) {
                Text("→")
            }
        }

        // ボタン群
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            androidx.compose.material3.FilledTonalButton(
                onClick = { },
                modifier =
                    Modifier
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                setButton(true) { state, pressed -> state.copy(a = pressed) }
                                waitForUpOrCancellation()
                                setButton(false) { state, _ -> state.copy(a = false) }
                            }
                        },
            ) {
                Text("A")
            }
            androidx.compose.material3.FilledTonalButton(
                onClick = { },
                modifier =
                    Modifier
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                setButton(true) { state, pressed -> state.copy(b = pressed) }
                                waitForUpOrCancellation()
                                setButton(false) { state, _ -> state.copy(b = false) }
                            }
                        },
            ) {
                Text("B")
            }
            androidx.compose.material3.FilledTonalButton(
                onClick = { },
                modifier =
                    Modifier
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                setButton(true) { state, pressed -> state.copy(select = pressed) }
                                waitForUpOrCancellation()
                                setButton(false) { state, _ -> state.copy(select = false) }
                            }
                        },
            ) {
                Text("SELECT")
            }
            androidx.compose.material3.FilledTonalButton(
                onClick = { },
                modifier =
                    Modifier
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                setButton(true) { state, pressed -> state.copy(start = pressed) }
                                waitForUpOrCancellation()
                                setButton(false) { state, _ -> state.copy(start = false) }
                            }
                        },
            ) {
                Text("START")
            }
        }
    }
}

private fun mergeInput(
    ui: InputState,
    controller: InputState,
): InputState =
    InputState(
        a = ui.a || controller.a,
        b = ui.b || controller.b,
        select = ui.select || controller.select,
        start = ui.start || controller.start,
        up = ui.up || controller.up,
        down = ui.down || controller.down,
        left = ui.left || controller.left,
        right = ui.right || controller.right,
    )

/**
 * ROM URIからセーブファイル名を導出する。
 * URIの最後のパスセグメントから拡張子を.savに変更する。
 */
private fun deriveSavFileName(uri: Uri): String {
    val lastSegment = uri.lastPathSegment ?: "unknown"
    val baseName = lastSegment.substringAfterLast('/').substringBeforeLast('.')
    return "$baseName.sav"
}

/**
 * セーブデータをファイルに保存する。
 */
private fun saveCartridgeRam(
    context: Context,
    savFileName: String,
    gameLoop: GameLoop,
) {
    if (!gameLoop.hasBattery()) return
    val ramData = gameLoop.getCartridgeRam() ?: return
    try {
        val savFile = java.io.File(context.filesDir, savFileName)
        savFile.writeBytes(ramData)
        android.util.Log.d("SaveData", "Saved $savFileName (${ramData.size} bytes)")
    } catch (e: java.io.IOException) {
        android.util.Log.e("SaveData", "Failed to save $savFileName: ${e.message}")
    }
}

/**
 * セーブデータをファイルから読み込む。
 */
private fun loadCartridgeRam(
    context: Context,
    savFileName: String,
    gameLoop: GameLoop,
) {
    if (!gameLoop.hasBattery()) return
    try {
        val savFile = java.io.File(context.filesDir, savFileName)
        if (savFile.exists()) {
            val ramData = savFile.readBytes()
            gameLoop.loadCartridgeRam(ramData)
            android.util.Log.d("SaveData", "Loaded $savFileName (${ramData.size} bytes)")
        }
    } catch (e: java.io.IOException) {
        android.util.Log.e("SaveData", "Failed to load $savFileName: ${e.message}")
    }
}

private fun toErrorMessage(error: CoreError): String =
    when (error) {
        is CoreError.RomNotLoaded -> {
            "ROM がロードされていません。"
        }

        is CoreError.InvalidRom -> {
            "ROM が不正です: ${error.reason}"
        }

        is CoreError.IllegalState -> {
            "エミュレータの状態が不正です: ${error.message}"
        }

        is CoreError.InternalError -> {
            val cause = error.cause
            val message = cause?.message ?: "不明なエラー"
            val className = cause?.javaClass?.simpleName ?: "Unknown"
            "内部エラー ($className): $message"
        }
    }
