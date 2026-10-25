package app.trackerlens

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import app.trackerlens.data.ScanRepository
import app.trackerlens.data.Settings
import app.trackerlens.work.AlertNotifier
import app.trackerlens.work.ChangeCheckWorker
import java.util.concurrent.TimeUnit

/** Holds the app-wide singletons (one repository per process, shared by UI and worker). */
class TrackerLensApp : Application() {
    val repository by lazy { ScanRepository(this) }
    val settings by lazy { Settings(this) }
    val notifier by lazy { AlertNotifier(this) }

    override fun onCreate() {
        super.onCreate()
        notifier.createChannel()
        val request = PeriodicWorkRequestBuilder<ChangeCheckWorker>(12, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .build()
        WorkManager.getInstance(this)
            .enqueueUniquePeriodicWork(ChangeCheckWorker.NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}
