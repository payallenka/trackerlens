package app.trackerlens.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.trackerlens.TrackerLensApp
import app.trackerlens.core.Alerts
import java.io.IOException

/** Periodic check: rescans apps that changed and announces any alerts the user has not seen yet. */
class ChangeCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as TrackerLensApp
        val settings = app.settings
        // Nothing is scanned before the user has started the first scan in the journey.
        if (!settings.scanConsented) {
            Log.i(TAG, "check skipped: first scan not started yet")
            return Result.success()
        }
        val result = try {
            app.repository.scan(includeSystem = settings.includeSystem)
        } catch (e: IOException) {
            Log.w(TAG, "scan failed, will retry: ${e.javaClass.simpleName}")
            return Result.retry()
        }
        // The notification lists every alert not yet seen in the app, so a newer notification never
        // hides older unseen ones. Only a notification that was actually posted counts as announced.
        val unseen = Alerts.unseen(result.changes, settings.alertsSeenUntil)
        if (Alerts.shouldNotify(unseen, settings.alertsNotifiedUntil) &&
            app.notifier.show(unseen.asReversed(), app.repository::trackerName, app.repository::permissionLabel)
        ) {
            settings.alertsNotifiedUntil = unseen.maxOf { it.detectedAt }
        }
        return Result.success()
    }

    companion object {
        const val NAME = "change-check"
        private const val TAG = "TrackerLens"
    }
}
