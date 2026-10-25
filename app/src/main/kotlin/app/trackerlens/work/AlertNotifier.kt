package app.trackerlens.work

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.trackerlens.R
import app.trackerlens.core.AppChange
import app.trackerlens.ui.MainActivity

/** The single place that creates, posts and clears the "update reduced privacy" notification. */
class AlertNotifier(private val context: Context) {

    fun createChannel() {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.channel_changes), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    fun cancel() = NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)

    /** Whether update alerts can actually be shown (runtime permission granted and notifications enabled). */
    fun enabled(): Boolean {
        val nm = NotificationManagerCompat.from(context)
        val permitted = Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        // The user can also block just this channel (long-press on a notification).
        val channelOn = nm.getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE
        return permitted && nm.areNotificationsEnabled() && channelOn
    }

    /**
     * Shows every unseen alert in one expandable notification. The lock screen only gets a generic
     * public version, and the notification stays off paired watches, because the details reveal
     * which apps are installed. Returns whether the notification was actually posted (false if
     * notifications are off).
     */
    @SuppressLint("MissingPermission") // runtime permission only exists on API 33+, checked below
    fun show(unseen: List<AppChange>, trackerName: (String) -> String, permissionLabel: (String) -> String): Boolean {
        // A blocked channel would drop the notification silently, so it must count as "not posted".
        if (unseen.isEmpty() || !enabled()) return false
        val nm = NotificationManagerCompat.from(context)

        val title = if (unseen.size == 1) "An app update reduced your privacy" else "${unseen.size} app updates reduced your privacy"
        val summary = unseen.joinToString(" · ") { c ->
            val parts = buildList {
                if (c.addedTrackers.isNotEmpty()) add(count(c.addedTrackers.size, "new tracker"))
                if (c.addedPermissions.isNotEmpty()) add(count(c.addedPermissions.size, "new permission"))
            }
            "${c.label}: ${parts.joinToString(" and ")}"
        }
        // Expanded text names every tracker and permission so nothing is hidden behind an ellipsis.
        val details = unseen.joinToString("\n\n") { c ->
            buildString {
                append("${c.label} ${c.fromVersion ?: "?"} → ${c.toVersion ?: "?"}")
                c.addedTrackers.forEach { append("\n+ Tracker: ${trackerName(it)}") }
                c.addedPermissions.forEach { append("\n+ Permission: ${permissionLabel(it)}") }
            }
        }
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_ALERTS, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val public = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_tl)
            .setContentTitle(title)
            .setContentText("Open TrackerLens to see details")
            .build()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_tl)
            .setContentTitle(title)
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(details))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setLocalOnly(true)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIFICATION_ID, notification)
        return true
    }

    private fun count(n: Int, noun: String) = if (n == 1) "1 $noun" else "$n ${noun}s"

    companion object {
        const val CHANNEL_ID = "changes"
        const val NOTIFICATION_ID = 1
    }
}
