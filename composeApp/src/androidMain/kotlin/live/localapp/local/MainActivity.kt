package live.localapp.local

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import live.localapp.local.di.initKoin

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        initKoin()
        ActivityProvider.activity = this

        setContent {
            App()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (ActivityProvider.activity === this) ActivityProvider.activity = null
    }
}

/** Provides Activity reference for platform APIs that need it (e.g. Play In-App Review). */
object ActivityProvider {
    var activity: ComponentActivity? = null
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}