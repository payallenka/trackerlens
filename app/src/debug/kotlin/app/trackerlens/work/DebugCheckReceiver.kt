package app.trackerlens.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import app.trackerlens.data.Settings

/**
 * `adb shell am broadcast -n app.trackerlens.debug/app.trackerlens.work.DebugCheckReceiver` runs ChangeCheckWorker
 * once, now. With `--ez consent true` it first records scan consent, so scripted tests work on a fresh install.
 */
class DebugCheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.getBooleanExtra("consent", false)) Settings(context).scanConsented = true
        WorkManager.getInstance(context).enqueue(OneTimeWorkRequestBuilder<ChangeCheckWorker>().build())
    }
}
