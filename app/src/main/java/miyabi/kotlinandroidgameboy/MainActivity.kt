package miyabi.kotlinandroidgameboy

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import miyabi.kotlinandroidgameboy.emulator.CoreProvider
import miyabi.kotlinandroidgameboy.emulator.GameLoop
import miyabi.kotlinandroidgameboy.ui.theme.GameBoyTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            // 将来的には ViewModel などから GameLoop を渡す。
            val core = CoreProvider.provideCore()
            val framePreview = GameLoop(core)

            GameBoyTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                ) { innerPadding ->
                    greetingMessage(
                        name = "Android (${framePreview.hashCode()})",
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }
    }
}

@Composable
fun greetingMessage(
    name: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = "Hello $name!",
        modifier = modifier,
    )
}

@Preview(showBackground = true)
@Composable
fun greetingPreview() {
    GameBoyTheme {
        greetingMessage("Android")
    }
}
