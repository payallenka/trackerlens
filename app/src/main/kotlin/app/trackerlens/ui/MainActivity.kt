package app.trackerlens.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels { MainViewModel.Factory }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Notification permission is requested inside the first-run journey, where it's explained.
        // Only a fresh launch follows the intent; after rotation the ViewModel already has the screen.
        if (savedInstanceState == null) handle(intent)
        setContent { TrackerLensTheme { TrackerLensScreen(vm) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onResume() {
        super.onResume()
        vm.onForeground()
    }

    override fun onStop() {
        super.onStop()
        vm.onBackground()
    }

    private fun handle(intent: Intent) {
        if (intent.getBooleanExtra(EXTRA_OPEN_ALERTS, false)) {
            vm.select(null)
            vm.openAlerts()
        }
    }

    companion object {
        const val EXTRA_OPEN_ALERTS = "open_alerts"
    }
}

@Composable
fun TrackerLensTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme(primary = Color(0xFF7FD8C2))
        else -> lightColorScheme(primary = Color(0xFF1B5E50))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
