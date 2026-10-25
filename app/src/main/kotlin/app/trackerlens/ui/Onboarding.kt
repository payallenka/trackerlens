package app.trackerlens.ui

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import app.trackerlens.R

/**
 * The first-run journey, served as five courses: welcome, how it works and consent, the live scan,
 * the results reveal, and a plan. Each course has one clear action, and a step indicator shows
 * where the user is.
 */
@Composable
fun Journey(state: UiState, vm: MainViewModel) {
    val course = state.course ?: return
    BackHandler(enabled = course == Course.Consent || course == Course.Results || course == Course.Plan || (course == Course.Scan && !state.scanning)) {
        vm.previousCourse()
    }
    // During a replay, Back on the first course returns to the dashboard instead of leaving the app.
    BackHandler(enabled = course == Course.Welcome && vm.isReplay) { vm.finishJourney() }
    val context = LocalContext.current
    val askAlerts = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.refreshNotificationState() }
    val requestAlerts: () -> Unit = {
        val activity = context as? Activity
        val dialogCanShow = Build.VERSION.SDK_INT >= 33 && activity != null &&
            (!vm.alertsAskedBefore || ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.POST_NOTIFICATIONS))
        if (dialogCanShow) {
            vm.markAlertsAsked()
            askAlerts.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            // Below Android 13, or after a permanent "Don't allow", only system settings can turn alerts on.
            openNotificationSettings(context)
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(24.dp)) {
            Box(Modifier.fillMaxWidth()) {
                StepDots(course)
                if (vm.isReplay) {
                    TextButton(onClick = vm::finishJourney, modifier = Modifier.align(Alignment.CenterEnd)) { Text("Close") }
                }
            }
            AnimatedContent(targetState = course, label = "course", modifier = Modifier.weight(1f)) { c ->
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 24.dp)) {
                    when (c) {
                        Course.Welcome -> Welcome()
                        Course.Consent -> Consent(state.notificationsOn, requestAlerts)
                        Course.Scan -> Scanning(state)
                        Course.Results -> Results(state)
                        Course.Plan -> Plan(state, requestAlerts)
                    }
                }
            }
            CourseActions(course, state, vm)
        }
    }
}

@Composable
private fun StepDots(course: Course) {
    Row(
        Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = "Step ${course.ordinal + 1} of ${Course.entries.size}" },
        horizontalArrangement = Arrangement.Center,
    ) {
        Course.entries.forEach { c ->
            Box(
                Modifier.padding(4.dp).size(if (c == course) 10.dp else 8.dp).background(
                    if (c.ordinal <= course.ordinal) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    CircleShape,
                ),
            )
        }
    }
}

@Composable
private fun CourseActions(course: Course, state: UiState, vm: MainViewModel) {
    val scanDone = state.loaded && !state.scanning && state.lastScanAt > 0
    Column(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        when (course) {
            Course.Welcome -> Button(onClick = vm::nextCourse, modifier = Modifier.fillMaxWidth()) { Text("Get started") }
            Course.Consent -> Button(onClick = vm::nextCourse, modifier = Modifier.fillMaxWidth()) { Text("Scan my apps") }
            Course.Scan -> when {
                state.scanFailed && !state.scanning -> Button(onClick = vm::rescan, modifier = Modifier.fillMaxWidth()) { Text("Try again") }
                else -> Button(onClick = vm::nextCourse, enabled = scanDone, modifier = Modifier.fillMaxWidth()) {
                    Text(if (scanDone) "Show my results" else "Scanning…")
                }
            }
            Course.Results -> Button(onClick = vm::nextCourse, modifier = Modifier.fillMaxWidth()) { Text("What can I do?") }
            Course.Plan -> {
                val next = state.nextToReview
                Button(onClick = vm::finishJourney, modifier = Modifier.fillMaxWidth()) { Text("Go to my dashboard") }
                if (next != null) {
                    TextButton(onClick = { vm.finishJourney(); vm.select(next.snapshot.packageName) }) {
                        Text("Review ${next.snapshot.label} now")
                    }
                }
            }
        }
    }
}

// ---------- Course 1: welcome ----------

@Composable
private fun ColumnScope.Welcome() {
    Surface(Modifier.size(96.dp).align(Alignment.CenterHorizontally), shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
        Icon(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
    }
    Spacer(Modifier.height(24.dp))
    Title("See who's inside your apps")
    Body(
        "Most apps include code from other companies that track what you do. TrackerLens finds it, " +
            "shows you who it belongs to, and warns you when an update adds more.",
    )
    Spacer(Modifier.height(24.dp))
    Promise(Icons.Filled.Lock, "Everything stays on this phone", "Your apps are checked here, never uploaded.")
    Promise(Icons.Filled.CheckCircle, "No internet access", "TrackerLens can't send anything anywhere. No ads, no accounts.")
    Promise(Icons.Filled.Search, "Proof, not guesses", "Every tracker comes with the exact code it was found in.")
}

// ---------- Course 2: how it works and consent ----------

@Composable
private fun ColumnScope.Consent(alertsOn: Boolean, requestAlerts: () -> Unit) {
    Title("How it works")
    Step(1, "Reads the code of the apps on your phone and matches it against 428 known trackers.")
    Step(2, "Never opens your messages, photos, contacts or accounts. Android doesn't allow it, and we don't try.")
    Step(3, "Checks again about every 12 hours, so app updates don't slip by.")
    Spacer(Modifier.height(24.dp))
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.secondaryContainer) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Notifications, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Update alerts", fontWeight = FontWeight.Bold)
            }
            Text(
                "Get a notification when an app update adds trackers or permissions. Optional; you can change it later.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            if (alertsOn) {
                Text("✓ Alerts are on", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
            } else {
                OutlinedButton(onClick = requestAlerts) { Text("Turn on alerts") }
            }
        }
    }
}

// ---------- Course 3: the live scan ----------

@Composable
private fun ColumnScope.Scanning(state: UiState) {
    val p = state.progress
    val done = state.loaded && !state.scanning && state.lastScanAt > 0
    val failed = state.scanFailed && !state.scanning
    // The title changes on completion or failure, which also moves accessibility focus to it (announcing it).
    Title(
        when {
            failed -> "Scan didn't finish"
            done -> "Scan complete"
            else -> "Checking your apps"
        },
    )
    if (failed) {
        Body("Something stopped the scan before it finished. This can happen when the phone is very low on storage. Try again; if it keeps happening, free up some space first.")
        return
    }
    Spacer(Modifier.height(24.dp))
    Box(Modifier.size(160.dp).align(Alignment.CenterHorizontally), contentAlignment = Alignment.Center) {
        if (done) {
            CircularProgressIndicator(progress = { 1f }, modifier = Modifier.size(160.dp), strokeWidth = 10.dp)
        } else if (p == null || p.second == 0) {
            CircularProgressIndicator(Modifier.size(160.dp), strokeWidth = 10.dp)
        } else {
            CircularProgressIndicator(progress = { p.first.toFloat() / p.second }, modifier = Modifier.size(160.dp), strokeWidth = 10.dp)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // Counts up rather than jumping, so even a fast scan reads as discovery.
            // Your apps with trackers: the same scope as the score on the next screen. Live while scanning;
            // the true figure from the results once done (some apps may come from an earlier scan).
            val count = if (done) state.summary.userAppsWithTrackers else state.appsWithTrackersSoFar
            val shown by animateIntAsState(count, tween(durationMillis = 800), label = "found")
            Text("$shown", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
            Text(if (count == 1) "of your apps\nhas trackers" else "of your apps\nhave trackers", style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
        }
    }
    Spacer(Modifier.height(24.dp))
    Text(
        when {
            done -> "Done: checked ${checkedScope(state.summary)}."
            p != null && p.second > 0 -> "Checked ${p.first} of ${p.second} apps"
            else -> "Looking for installed apps…"
        },
        // Announced politely as it changes, so screen-reader users know when the scan is done.
        Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium,
    )
    state.lastDiscovery?.let {
        Spacer(Modifier.height(8.dp))
        Text(it, Modifier.fillMaxWidth(), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
    }
    if (done && state.summary.systemAppsWithTrackers > 0) {
        Text(
            "Plus ${plural(state.summary.systemAppsWithTrackers, "preinstalled app")} with trackers",
            Modifier.fillMaxWidth().padding(top = 4.dp), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium,
        )
    }
    if (!done) {
        Spacer(Modifier.height(16.dp))
        Body("The first scan takes up to a minute. After that, only apps that change are checked again.")
    }
}

// ---------- Course 4: the results reveal ----------

@Composable
private fun ColumnScope.Results(state: UiState) {
    val s = state.summary
    Title("Your results")
    Spacer(Modifier.height(16.dp))
    val headline: @Composable () -> Unit = {
        Column {
            Text(riskHeadline(s.risk), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(scopeLine(s), style = MaterialTheme.typography.bodyMedium)
        }
    }
    if (LocalDensity.current.fontScale > 1.3f) {
        ScoreRing(s.score, s.risk)
        Spacer(Modifier.height(8.dp))
        headline()
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ScoreRing(s.score, s.risk)
            Spacer(Modifier.width(16.dp))
            headline()
        }
    }
    Spacer(Modifier.height(16.dp))
    val partly = s.userApps - (if (s.scoredUserApps) s.scoredApps else 0)
    Body(
        when {
            s.userApps == 0 -> "No apps you installed were found."
            s.userAppsWithTrackers == 0 && partly == 0 -> "None of the ${plural(s.userApps, "app")} you installed contain known trackers. That's rare; nice."
            s.userAppsWithTrackers == 0 -> "No known trackers in the ${plural(s.userApps - partly, "app")} we could fully check; ${plural(partly, "app")} couldn't be fully read."
            else -> "${s.userAppsWithTrackers} of the ${plural(s.userApps, "app")} you installed contain known trackers."
        },
    )
    if (s.systemAppsWithTrackers > 0) {
        Body(
            "${plural(s.systemAppsWithTrackers, "preinstalled app")} also ${if (s.systemAppsWithTrackers == 1) "has" else "have"} trackers. " +
                "These came with the phone; you can limit their permissions but usually not remove them.",
        )
    }
    if (s.mostTracked.isNotEmpty()) {
        Spacer(Modifier.height(16.dp))
        Text("Most tracked of your apps", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        s.mostTracked.forEach { r ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                AppIcon(r.snapshot.packageName)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(r.snapshot.label, fontWeight = FontWeight.Medium)
                    Text(plural(r.trackers.size, "tracker"), style = MaterialTheme.typography.bodySmall)
                }
                RiskBadge(r.risk, partial = !r.snapshot.scanComplete)
            }
        }
    }
    s.topUserTracker?.let { top ->
        Spacer(Modifier.height(12.dp))
        Body("Most common in your apps: ${top.tracker.name}, inside ${plural(top.appCount, "app")}.")
    }
}

// ---------- Course 5: the plan ----------

@Composable
private fun ColumnScope.Plan(state: UiState, requestAlerts: () -> Unit) {
    Title("Your privacy plan")
    Spacer(Modifier.height(8.dp))
    val risky = state.riskyApps
    if (risky.isEmpty()) {
        PlanItem(
            Icons.Filled.CheckCircle,
            "Your apps are in good shape",
            "None of the apps you installed carry medium or high tracking risk. Nothing to do right now.",
        )
    } else {
        PlanItem(
            Icons.Filled.Warning,
            "Review your ${plural(risky.size, "riskiest app")}",
            "Start with ${risky.first().snapshot.label}. For each, turn off permissions it doesn't need, or find an alternative.",
        )
    }
    if (state.summary.systemAppsWithTrackers > 0) {
        PlanItem(
            Icons.Filled.Lock,
            "Limit preinstalled apps",
            "${plural(state.summary.systemAppsWithTrackers, "preinstalled app")} with trackers can't usually be removed. " +
                "Turn off permissions they don't need, or disable the ones you never use.",
        )
    }
    PlanItem(
        Icons.Filled.Notifications,
        if (state.notificationsOn) "Alerts are on" else "Update alerts are off",
        if (state.notificationsOn) "We'll tell you when an update adds trackers or permissions."
        else "You'll only see changes when you open the app. You can turn alerts on any time.",
        action = if (state.notificationsOn) null else ("Turn on alerts" to requestAlerts),
    )
    PlanItem(
        Icons.Filled.CheckCircle,
        "We'll keep watching",
        "Your dashboard shows your next step and every update that reduced your privacy.",
    )
}

// ---------- Shared pieces ----------

/** Each course's title takes accessibility focus when the course appears, so screen readers announce it. */
@Composable
private fun Title(text: String) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(text) { runCatching { focus.requestFocus() } }
    Text(
        text, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
        modifier = Modifier.semantics { heading() }.focusRequester(focus).focusable(),
    )
}

@Composable
private fun Body(text: String) = Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))

@Composable
private fun Promise(icon: ImageVector, title: String, text: String) {
    Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, fontWeight = FontWeight.Bold)
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun Step(n: Int, text: String) {
    Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
        Surface(Modifier.size(28.dp), shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
            Box(contentAlignment = Alignment.Center) { Text("$n", fontWeight = FontWeight.Bold) }
        }
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun PlanItem(icon: ImageVector, title: String, text: String, action: Pair<String, () -> Unit>? = null) {
    Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, fontWeight = FontWeight.Bold)
            Text(text, style = MaterialTheme.typography.bodyMedium)
            action?.let { (label, onClick) -> OutlinedButton(onClick = onClick, modifier = Modifier.padding(top = 6.dp)) { Text(label) } }
        }
    }
}

/** What a scan covered, in the same terms everywhere: "5 apps you installed and 2 preinstalled apps". */
internal fun checkedScope(s: Summary): String = when {
    s.preinstalledApps == 0 -> plural(s.userApps, "app") + " you installed"
    s.userApps == 0 -> plural(s.preinstalledApps, "preinstalled app")
    else -> "${plural(s.userApps, "app")} you installed and ${plural(s.preinstalledApps, "preinstalled app")}"
}

/** Opens this app's notification settings; falls back to its App info page on unusual ROMs. */
internal fun openNotificationSettings(context: Context) {
    val notif = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    try {
        context.startActivity(notif)
    } catch (_: ActivityNotFoundException) {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
    }
}

/** What the score covers, stated with counts so it can't be mistaken for the whole phone. */
internal fun scopeLine(s: Summary): String = when {
    s.scoredApps == 0 -> "No app could be fully checked yet."
    s.scoredUserApps -> "Across the ${plural(s.scoredApps, "app")} you installed"
    else -> "Across ${plural(s.scoredApps, "preinstalled app")}"
}
