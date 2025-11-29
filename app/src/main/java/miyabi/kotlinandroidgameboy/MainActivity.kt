package miyabi.kotlinandroidgameboy

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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

    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent?,
    ): Boolean {
        val handled = InputStateHolder.updateFromKey(keyCode, isDown = true)
        return if (handled) {
            true
        } else {
            super.onKeyDown(keyCode, event)
        }
    }

    override fun onKeyUp(
        keyCode: Int,
        event: KeyEvent?,
    ): Boolean {
        val handled = InputStateHolder.updateFromKey(keyCode, isDown = false)
        return if (handled) {
            true
        } else {
            super.onKeyUp(keyCode, event)
        }
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

    // シンプルなゲームループ（UI スレッドを塞がないようにコルーチンで）
    LaunchedEffect(isRunning, romLoaded) {
        android.util.Log.d("GameLoop", "LaunchedEffect: isRunning=$isRunning, romLoaded=$romLoaded")
        if (!isRunning || !romLoaded) {
            android.util.Log.d("GameLoop", "Game loop not started: isRunning=$isRunning, romLoaded=$romLoaded")
            return@LaunchedEffect
        }
        android.util.Log.d("GameLoop", "Game loop started")
        var frameCount = 0
        while (isRunning && romLoaded) {
            val mergedInput = mergeInput(uiInput, InputStateHolder.controllerInput.value)
            when (val result = gameLoop.runSingleFrame(mergedInput)) {
                is CoreResult.Success -> {
                    frameBuffer = result.value.frameBuffer
                    frameIndex = result.value.stats?.frameIndex ?: 0L
                    errorMessage = null
                    frameCount++
                    if (frameCount % 60 == 0) {
                        android.util.Log.d("GameLoop", "Frame $frameCount rendered, buffer size=${frameBuffer?.size}")
                    }
                }
                is CoreResult.Error -> {
                    android.util.Log.e("GameLoop", "Error in frame: ${toErrorMessage(result.error)}")
                    errorMessage = toErrorMessage(result.error)
                    isRunning = false
                }
            }
            kotlinx.coroutines.delay(16L)
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
    val input = uiInputState.value

    fun toggle(
        current: Boolean,
        setter: (Boolean) -> InputState,
    ) {
        val next = !current
        val newState = setter(next)
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
            Button(onClick = { toggle(input.up) { v -> input.copy(up = v) } }) {
                Text(if (input.up) "↑ ON" else "↑")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { toggle(input.left) { v -> input.copy(left = v) } }) {
                Text(if (input.left) "← ON" else "←")
            }
            Button(onClick = { toggle(input.down) { v -> input.copy(down = v) } }) {
                Text(if (input.down) "↓ ON" else "↓")
            }
            Button(onClick = { toggle(input.right) { v -> input.copy(right = v) } }) {
                Text(if (input.right) "→ ON" else "→")
            }
        }

        // ボタン群
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            Button(onClick = { toggle(input.a) { v -> input.copy(a = v) } }) {
                Text(if (input.a) "A ON" else "A")
            }
            Button(onClick = { toggle(input.b) { v -> input.copy(b = v) } }) {
                Text(if (input.b) "B ON" else "B")
            }
            Button(onClick = { toggle(input.select) { v -> input.copy(select = v) } }) {
                Text(if (input.select) "SELECT ON" else "SELECT")
            }
            Button(onClick = { toggle(input.start) { v -> input.copy(start = v) } }) {
                Text(if (input.start) "START ON" else "START")
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

private fun toErrorMessage(error: CoreError): String =
    when (error) {
        is CoreError.RomNotLoaded -> "ROM がロードされていません。"
        is CoreError.InvalidRom -> "ROM が不正です: ${error.reason}"
        is CoreError.IllegalState -> "エミュレータの状態が不正です: ${error.message}"
        is CoreError.InternalError -> {
            val cause = error.cause
            val message = cause?.message ?: "不明なエラー"
            val className = cause?.javaClass?.simpleName ?: "Unknown"
            "内部エラー ($className): $message"
        }
    }
