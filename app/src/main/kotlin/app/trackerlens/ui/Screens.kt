package app.trackerlens.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.text.format.DateUtils
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.trackerlens.core.AppChange
import app.trackerlens.core.Risk
import app.trackerlens.core.TrackerDef
import app.trackerlens.data.AppReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** Plain-language meaning of each Exodus tracker category, shown next to every tracker. */
private val CATEGORY_HELP = mapOf(
    "Advertisement" to "Shows you ads and may build an ad profile about you.",
    "Analytics" to "Records how you use the app, usually sent to the developer or a third party.",
    "Crash reporting" to "Sends error reports when the app crashes. Usually low risk.",
    "Identification" to "Recognises you or your device across apps or sessions.",
    "Location" to "Collects where you are.",
    "Profiling" to "Combines data about you to infer interests or behaviour.",
)

/** Permissions shown before "Show all", so long lists don't bury the trackers. */
private const val PERMISSIONS_PREVIEW = 6

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackerLensScreen(vm: MainViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    if (state.course != null) {
        Journey(state, vm)
        return
    }
    val selected = state.selected?.let { pkg -> state.reports.firstOrNull { it.snapshot.packageName == pkg } }
    val listFiltered = state.query.isNotBlank() || state.trackerFilter != null
    // Back, in order: close an app's details; from a tracker's app list, return to where the tracker was
    // picked (Home or Trackers); clear a typed search; return to Home; leave the app.
    BackHandler(enabled = selected != null) { vm.select(null) }
    BackHandler(enabled = selected == null && state.tab == Tab.Apps && listFiltered) {
        if (state.trackerFilter != null) vm.backFromTrackerFilter() else vm.setQuery("")
    }
    BackHandler(enabled = selected == null && state.tab != Tab.Home && !(state.tab == Tab.Apps && listFiltered)) {
        vm.selectTab(Tab.Home)
    }
    val snackbar = remember { SnackbarHostState() }
    var explainScore by remember { mutableStateOf(false) }
    if (explainScore) ScoreExplanation(onDismiss = { explainScore = false })
    // Hoisted so each list keeps its position while an app's details are open and across rotation.
    val appsListState = rememberLazyListState()
    val homeListState = rememberLazyListState()
    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); vm.messageShown() }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        selected?.snapshot?.label ?: if (state.tab == Tab.Home) "TrackerLens" else state.tab.label,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.semantics { heading() },
                    )
                },
                navigationIcon = {
                    if (selected != null) {
                        IconButton(onClick = { vm.select(null) }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    if (selected == null) {
                        IconButton(onClick = vm::rescan, enabled = !state.scanning) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Rescan")
                        }
                        OverflowMenu(onReplay = vm::replayJourney, onExplain = { explainScore = true })
                    }
                },
            )
        },
        bottomBar = { if (selected == null) BottomTabs(state, vm) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (state.scanning) {
                val p = state.progress
                if (p == null || p.second == 0) LinearProgressIndicator(Modifier.fillMaxWidth())
                else LinearProgressIndicator(progress = { p.first.toFloat() / p.second }, modifier = Modifier.fillMaxWidth())
            }
            when {
                selected != null -> AppDetail(
                    selected, state.changes.filter { it.packageName == selected.snapshot.packageName }, vm,
                    reviewed = selected.snapshot.packageName in state.reviewed,
                )
                state.tab == Tab.Home -> HomeTab(state, vm, homeListState)
                state.tab == Tab.Apps -> AppsTab(state, vm, appsListState)
                state.tab == Tab.Trackers -> TrackersTab(state, vm)
                else -> AlertsScreen(state, vm)
            }
        }
    }
}

@Composable
private fun BottomTabs(state: UiState, vm: MainViewModel) {
    NavigationBar {
        Tab.entries.forEach { tab ->
            NavigationBarItem(
                selected = state.tab == tab,
                onClick = { vm.selectTab(tab) },
                label = { Text(tab.label) },
                icon = {
                    val icon = when (tab) {
                        Tab.Home -> Icons.Filled.Home
                        Tab.Apps -> Icons.AutoMirrored.Filled.List
                        Tab.Trackers -> Icons.Filled.Search
                        Tab.Alerts -> Icons.Filled.Notifications
                    }
                    if (tab == Tab.Alerts && state.unreadAlerts > 0) {
                        BadgedBox(badge = { Badge { Text("${state.unreadAlerts}") } }) {
                            Icon(icon, contentDescription = "${state.unreadAlerts} unread")
                        }
                    } else {
                        Icon(icon, contentDescription = null)
                    }
                },
            )
        }
    }
}

// ---------- Home: where you stand and what to do next ----------

@Composable
private fun HomeTab(state: UiState, vm: MainViewModel, listState: LazyListState) {
    LazyColumn(Modifier.fillMaxSize(), state = listState) {
        item(key = "summary") { SummaryCard(state) }
        if (state.loaded) item(key = "next-step") { NextStepCard(state, vm) }
        if (state.unreadAlerts > 0) item(key = "alert-banner") { AlertBanner(state.unreadAlerts, vm::openAlerts) }
        if (!state.notificationsOn) item(key = "alerts-off") { AlertsOffBanner() }
        if (state.summary.topTrackers.isNotEmpty()) {
            item(key = "top") {
                Column(Modifier.padding(top = 8.dp)) {
                    SectionTitle("Most common trackers on your phone")
                    state.summary.topTrackers.forEach { u -> TrackerUsageRow(u) { vm.filterByTracker(u.tracker) } }
                    TextButton(onClick = { vm.selectTab(Tab.Trackers) }, modifier = Modifier.padding(horizontal = 8.dp)) {
                        Text("See all ${plural(state.summary.distinctTrackers, "tracker")}")
                    }
                }
            }
        }
        if (state.loaded) {
            item(key = "browse") {
                OutlinedButton(onClick = { vm.selectTab(Tab.Apps) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("Browse all ${plural(state.reports.size, "app")}")
                }
            }
        }
        item(key = "footer") { Footer(state.catalogVersion) }
    }
}

// ---------- Apps: search, sort and the full list ----------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppsTab(state: UiState, vm: MainViewModel, listState: LazyListState) {
    var searchFocused by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    // Back with an empty, focused search box just closes the keyboard and focus.
    BackHandler(enabled = searchFocused && state.query.isBlank() && state.trackerFilter == null) { focus.clearFocus() }
    val visible = state.visible

    LazyColumn(Modifier.fillMaxSize().imePadding(), state = listState) {
        stickyHeader(key = "search") {
            SearchBar(state.query, vm::setQuery, onFocus = { searchFocused = it })
        }
        item(key = "controls") { ListControls(state, vm, showSort = !searchFocused) }
        if (state.loaded && visible.isEmpty()) {
            item(key = "empty") {
                val why = when {
                    state.query.isNotBlank() -> "No apps match \"${state.query}\"."
                    state.trackerFilter != null -> "No apps contain ${state.trackerFilter.name}."
                    else -> "No apps to show."
                }
                Text(why, Modifier.padding(16.dp))
            }
        }
        items(visible, key = { it.snapshot.packageName }) { r -> AppRow(r) { vm.select(r.snapshot.packageName) } }
    }
}

// ---------- Trackers: every tracker found, and where ----------

@Composable
private fun TrackersTab(state: UiState, vm: MainViewModel) {
    val all = state.summary.allTrackers
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Text(
                if (all.isEmpty()) "No known trackers were found in the apps shown. Turn on \"Include system apps\" in Apps to check preinstalled apps too."
                else "${plural(all.size, "tracker")} found across ${plural(state.summary.appsWithTrackers, "app")}. Tap one to see which apps contain it.",
                Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium,
            )
        }
        items(all, key = { it.tracker.id }) { u -> TrackerUsageRow(u) { vm.filterByTracker(u.tracker) } }
    }
}

@Composable
private fun TrackerUsageRow(u: TrackerUsage, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(onClickLabel = "Show apps with ${u.tracker.name}", onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(u.tracker.name, fontWeight = FontWeight.Medium)
            if (u.tracker.categories.isNotEmpty()) Text(u.tracker.categories.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
        }
        Text("in ${plural(u.appCount, "app")}", style = MaterialTheme.typography.labelLarge)
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
    }
}

@Composable
private fun SummaryCard(state: UiState) {
    val ctx = LocalContext.current
    val s = state.summary
    var explain by remember { mutableStateOf(false) }
    if (explain) ScoreExplanation(onDismiss = { explain = false })
    Card(Modifier.fillMaxWidth().padding(16.dp)) {
        Column(Modifier.padding(16.dp)) {
            if (!state.loaded || (state.reports.isEmpty() && state.scanning)) {
                val p = state.progress
                Text("Scanning your apps…", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(if (p != null && p.second > 0) "${p.first} of ${p.second} apps checked" else "Looking for installed apps")
                return@Column
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                val checked = if (state.lastScanAt > 0) "Checked ${checkedAgo(state.lastScanAt)}" else null
                // Read as one stop by screen readers: score, meaning and freshness together.
                Row(
                    Modifier.weight(1f).clearAndSetSemantics {
                        contentDescription = (if (s.scoredApps == 0) "No privacy score yet" else "Privacy score ${s.score} out of 100, ${riskHeadline(s.risk)}") +
                            ", ${scopeLine(s)}" + (checked?.let { ", $it" } ?: "")
                        heading()
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ScoreRing(s.score, s.risk)
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(riskHeadline(s.risk), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(scopeLine(s), style = MaterialTheme.typography.bodyMedium)
                        checked?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
                IconButton(onClick = { explain = true }) { Icon(Icons.Filled.Info, contentDescription = "What does the score mean?") }
                IconButton(onClick = {
                    val worst = state.reports.minByOrNull { it.score }
                    val text = buildString {
                        append("I scanned my phone with TrackerLens: ${s.appsWithTrackers} of ${plural(state.reports.size, "app")} contain trackers.")
                        if (worst != null && worst.trackers.isNotEmpty()) append(" Worst: ${worst.snapshot.label} with ${plural(worst.trackers.size, "tracker")}.")
                    }
                    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
                }) { Icon(Icons.Filled.Share, contentDescription = "Share my results") }
            }
            Spacer(Modifier.size(12.dp))
            // Same scope as the score: your apps first; preinstalled apps counted separately.
            val stats = buildList {
                add("${s.userApps}" to "your apps")
                add("${s.userAppsWithTrackers}" to "of yours with trackers")
                if (s.preinstalledApps > 0) add("${s.systemAppsWithTrackers}" to "preinstalled with trackers")
                else add("${s.distinctTrackers}" to "different trackers")
            }
            // Three tiles side by side squeeze their labels at large font sizes, so they stack instead.
            if (LocalDensity.current.fontScale > 1.3f) {
                // One line per figure keeps the dashboard short at large font sizes.
                Column { stats.forEach { (v, l) -> Text("$v $l", style = MaterialTheme.typography.bodyLarge) } }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { stats.forEach { (v, l) -> Stat(v, l) } }
            }
            if (s.scoredUserApps && s.systemAppsWithTrackers > 0) {
                Text(
                    "Preinstalled apps came with the phone and aren't part of your score.",
                    Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** The journey continues on the dashboard: one clear next action and visible progress. */
@Composable
private fun NextStepCard(state: UiState, vm: MainViewModel) {
    val risky = state.riskyApps
    if (risky.isEmpty()) {
        Card(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("You're all set", fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
                    Text("None of your apps carry medium or high tracking risk. We'll alert you if an update changes that.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        return
    }
    val next = state.nextToReview
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Your next step", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
            if (next != null) {
                Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(next.snapshot.packageName)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Review ${next.snapshot.label}", fontWeight = FontWeight.Bold)
                        Text(plural(next.trackers.size, "tracker"), style = MaterialTheme.typography.bodySmall)
                    }
                    RiskBadge(next.risk, partial = !next.snapshot.scanComplete)
                }
                Button(onClick = { vm.select(next.snapshot.packageName) }) { Text("Review now") }
            } else {
                Text("You've reviewed all ${plural(risky.size, "risky app")}.", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                Text("We'll alert you when an update adds trackers or permissions.", style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.size(8.dp))
            if (state.reviewedRisky > 0) {
                LinearProgressIndicator(progress = { state.reviewedRisky.toFloat() / risky.size }, modifier = Modifier.fillMaxWidth())
            }
            Text("Reviewed ${state.reviewedRisky} of ${plural(risky.size, "risky app")}", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/** Shown when update alerts can't reach the user, with a direct fix. */
@Composable
private fun AlertsOffBanner() {
    val ctx = LocalContext.current
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Notifications, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Update alerts are off", fontWeight = FontWeight.Bold)
                Text("You won't be told when an update adds trackers.", style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = { openNotificationSettings(ctx) }) { Text("Turn on") }
        }
    }
}

/** Less frequent actions, reachable from any scroll position. */
@Composable
private fun OverflowMenu(onReplay: () -> Unit, onExplain: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More options") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("About the privacy score") }, onClick = { open = false; onExplain() })
            DropdownMenuItem(text = { Text("Replay introduction") }, onClick = { open = false; onReplay() })
        }
    }
}

@Composable
private fun ScoreExplanation(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Got it") } },
        title = { Text("About the privacy score") },
        text = {
            Text(
                "100 means no known trackers were found in an app's code.\n\n" +
                    "Each tracker lowers the score by how invasive it is: advertising, profiling and location " +
                    "trackers count most, analytics less, crash reporting least.\n\n" +
                    "Permissions are listed for each app but don't change the score, because many are needed " +
                    "for the app to work (a messenger needs the microphone).\n\n" +
                    "Apps whose code couldn't be fully read are marked \"partial\" and left out of your score, because " +
                    "they may contain more trackers than shown.\n\n" +
                    "Your score is the average over apps you installed. To raise it, remove or replace " +
                    "high-risk apps, and limit what they can access in app settings.",
            )
        },
    )
}

@Composable
private fun RowScope.Stat(value: String, label: String) {
    Surface(Modifier.weight(1f), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(vertical = 8.dp).clearAndSetSemantics { contentDescription = "$value $label" }, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
internal fun ScoreRing(score: Int, risk: Risk) {
    Box(Modifier.size(72.dp).clearAndSetSemantics { contentDescription = "Privacy score $score out of 100" }, contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            progress = { score / 100f },
            modifier = Modifier.size(72.dp),
            color = riskColor(risk),
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            strokeWidth = 7.dp,
        )
        Text("$score", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun AlertBanner(unread: Int, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.width(12.dp))
            Text(
                "${plural(unread, "app update")} reduced your privacy. Tap to review.",
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
private fun SearchBar(query: String, onQuery: (String) -> Unit, onFocus: (Boolean) -> Unit) {
    val focus = LocalFocusManager.current
    Surface(color = MaterialTheme.colorScheme.background) {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).onFocusChanged { onFocus(it.isFocused) },
            placeholder = { Text("Search apps") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQuery(""); focus.clearFocus() }) { Icon(Icons.Filled.Clear, contentDescription = "Clear search") }
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ListControls(state: UiState, vm: MainViewModel, showSort: Boolean) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        state.trackerFilter?.let { t ->
            InputChip(
                selected = true,
                onClick = { vm.filterByTracker(null) },
                label = { Text("Apps with ${t.name}") },
                trailingIcon = { Icon(Icons.Filled.Clear, contentDescription = "Remove filter", Modifier.size(18.dp)) },
            )
        }
        if (showSort) {
            // Chips wrap onto more lines instead of being cut off at large font sizes.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SortOrder.entries.forEach { s -> FilterChip(state.sort == s, { vm.setSort(s) }, label = { Text(s.label) }) }
            }
            Row(
                Modifier.fillMaxWidth().toggleable(
                    value = state.includeSystem,
                    // Always tappable; during a scan the change is queued (see MainViewModel.setIncludeSystem).
                    role = Role.Switch,
                    onValueChange = vm::setIncludeSystem,
                ).padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Include system apps")
                    Text(
                        "Preinstalled apps such as Google Play services. System apps you've updated, like Chrome, are always shown.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Switch(state.includeSystem, onCheckedChange = null)
            }
        }
    }
}

@Composable
private fun AppRow(r: AppReport, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClickLabel = "Open details", onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(r.snapshot.packageName)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(r.snapshot.label, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${plural(r.trackers.size, "tracker")} · ${plural(r.dangerousPermissions.size, "sensitive permission")}" +
                    (if (r.snapshot.isSystem) " · Preinstalled" else "") +
                    if (!r.snapshot.scanComplete) " · partial scan" else "",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.width(8.dp))
        RiskBadge(r.risk, partial = !r.snapshot.scanComplete)
    }
}

@Composable
private fun Footer(catalogVersion: String) {
    Text(
        "Scanned on this device only. TrackerLens has no internet permission. " +
            "Background checks run about every 12 hours. " +
            "Tracker definitions: Exodus Privacy (ODbL)" + (if (catalogVersion.isNotBlank()) ", version $catalogVersion." else "."),
        Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall,
    )
}

// ---------- Alerts ----------

@Composable
private fun AlertsScreen(state: UiState, vm: MainViewModel) {
    val alerts = state.alerts
    val installed = remember(state.reports) { state.reports.mapTo(HashSet()) { it.snapshot.packageName } }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!state.notificationsOn) item { AlertsOffBanner() }
        if (alerts.isEmpty()) {
            item {
                Column(Modifier.padding(24.dp)) {
                    Text("No alerts yet", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "TrackerLens checks your apps about every 12 hours. If an update adds trackers or " +
                            "permissions, you'll get a notification and it will be listed here.",
                    )
                }
            }
        }
        items(alerts, key = { "a-${it.packageName}-${it.detectedAt}" }) { c ->
            ChangeCard(
                c, vm,
                onClick = if (c.packageName in installed) ({ vm.select(c.packageName) }) else null,
                isNew = c.detectedAt > state.newAlertsAfter,
            )
        }
    }
}

@Composable
private fun ChangeCard(c: AppChange, vm: MainViewModel, onClick: (() -> Unit)?, isNew: Boolean = false, showLabel: Boolean = true) {
    val content: @Composable () -> Unit = {
        Column(Modifier.padding(16.dp)) {
            if (showLabel) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(c.label, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    if (isNew) Badge { Text("New") }
                }
            }
            Text(
                "Version ${c.fromVersion ?: "?"} → ${c.toVersion ?: "?"} · " + DateFormat.getDateInstance().format(Date(c.detectedAt)),
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.size(6.dp))
            c.addedTrackers.forEach { ChangeLine(true, "Tracker added: ${trackerWithCategory(vm, it)}", severe = isSevereTracker(vm, it)) }
            c.addedPermissions.forEach { ChangeLine(true, "Permission added: ${vm.permissionLabel(it)}", severe = false) }
            c.removedTrackers.forEach { ChangeLine(false, "Tracker removed: ${trackerWithCategory(vm, it)}") }
            c.removedPermissions.forEach { ChangeLine(false, "Permission removed: ${vm.permissionLabel(it)}") }
            if (onClick != null) {
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("See app details", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
    val m = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
    if (onClick != null) Card(onClick = onClick, modifier = m) { content() } else Card(m) { content() }
}

private fun isSevereTracker(vm: MainViewModel, id: String) =
    vm.trackerCategories(id).any { it == "Advertisement" || it == "Profiling" || it == "Location" }

private fun trackerWithCategory(vm: MainViewModel, id: String): String {
    val categories = vm.trackerCategories(id)
    return vm.trackerName(id) + if (categories.isEmpty()) "" else " (${categories.joinToString(", ")})"
}

/** Added = worse (warning icon), removed = better (check icon): meaning never relies on colour alone. */
@Composable
private fun ChangeLine(added: Boolean, text: String, severe: Boolean = true) {
    Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (added) Icons.Filled.Warning else Icons.Filled.CheckCircle,
            contentDescription = null,
            // Red only for the most invasive additions (ads, profiling, location trackers); amber otherwise.
            tint = if (!added) improvementColor() else if (severe) riskColor(Risk.High) else riskColor(Risk.Medium),
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

// ---------- App detail ----------

@Composable
private fun AppDetail(r: AppReport, changes: List<AppChange>, vm: MainViewModel, reviewed: Boolean) {
    val ctx = LocalContext.current
    val s = r.snapshot
    var showAllPermissions by remember(s.packageName) { mutableStateOf(false) }
    var showTechnical by remember(s.packageName) { mutableStateOf(false) }
    var explain by remember { mutableStateOf(false) }
    if (explain) ScoreExplanation(onDismiss = { explain = false })
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(s.packageName, size = 56)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Version ${s.versionName ?: s.versionCode}", style = MaterialTheme.typography.bodyMedium)
                        if (s.isSystem) Text("Preinstalled app", style = MaterialTheme.typography.bodySmall)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        ScoreRing(r.score, r.risk)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Privacy score", style = MaterialTheme.typography.labelSmall)
                            IconButton(onClick = { explain = true }) {
                                Icon(Icons.Filled.Info, contentDescription = "What does the score mean?", modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
                Spacer(Modifier.size(8.dp))
                RiskBadge(r.risk, partial = !s.scanComplete)
                if (!s.scanComplete) {
                    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.Top) {
                        Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Part of this app's code could not be read, so it may contain more trackers than shown.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.size(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = {
                        ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", s.packageName, null)))
                    }) { Text("Open app settings") }
                    // Reviewing is the user's own decision; it moves the dashboard's "next step" along.
                    // (What it involves is explained in the "What you can do" card below.)
                    OutlinedButton(onClick = { vm.setReviewed(s.packageName, !reviewed) }) {
                        Text(if (reviewed) "✓ Reviewed" else "Mark as reviewed")
                    }
                }
            }
        }
        item { SectionTitle("Trackers (${r.trackers.size})") }
        if (r.trackers.isEmpty()) item { Text("No known trackers found in this app's code.", Modifier.padding(horizontal = 16.dp)) }
        items(r.trackers, key = { it.id }) { t -> TrackerCard(t, s.evidence[t.id].takeIf { showTechnical }) }
        if (r.trackers.isNotEmpty()) {
            item {
                Card(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text("What you can do, then mark it as reviewed", fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
                        Text(
                            "Trackers run whenever the app does and can use every permission you give it. " +
                                "Turn off permissions it doesn't need in app settings, look for an alternative " +
                                "with fewer trackers, or " +
                                (if (s.isSystem) "disable it in app settings if you don't use it (preinstalled apps usually can't be uninstalled)."
                                else "uninstall it if you rarely use it."),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        item { SectionTitle("Sensitive permissions (${r.dangerousPermissions.size})") }
        item {
            Text(
                "Permissions Android asks you to approve. Tracker code inside the app can use them too.",
                Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall,
            )
        }
        val perms = if (showAllPermissions) r.dangerousPermissions else r.dangerousPermissions.take(PERMISSIONS_PREVIEW)
        items(perms, key = { it }) { p -> Text("• ${vm.permissionLabel(p)}", Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) }
        if (r.dangerousPermissions.size > PERMISSIONS_PREVIEW) {
            item {
                TextButton(onClick = { showAllPermissions = !showAllPermissions }, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text(if (showAllPermissions) "Show fewer" else "Show all ${r.dangerousPermissions.size}")
                }
            }
        }
        if (changes.isNotEmpty()) {
            item { SectionTitle("Update history") }
            items(changes.asReversed(), key = { "h-${it.detectedAt}" }) { c -> ChangeCard(c, vm, onClick = null, showLabel = false) }
        }
        item {
            TextButton(onClick = { showTechnical = !showTechnical }, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text(if (showTechnical) "Hide technical details" else "Show technical details")
            }
        }
        if (showTechnical) {
            item {
                Text(
                    "Package: ${s.packageName}\n" +
                        "Trackers are identified by matching the class names in the app's code against the Exodus Privacy " +
                        "database. Each tracker above shows the class that matched.",
                    Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        item { Spacer(Modifier.size(24.dp)) }
    }
}

@Composable
private fun TrackerCard(t: TrackerDef, evidence: String?) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(t.name, fontWeight = FontWeight.Bold)
            t.categories.forEach { c ->
                Text(c, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                CATEGORY_HELP[c]?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            evidence?.let {
                Spacer(Modifier.size(4.dp))
                Text("Found in code: $it", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

// ---------- Shared pieces ----------

@Composable
internal fun RiskBadge(risk: Risk, partial: Boolean = false) {
    val color = riskColor(risk)
    Surface(shape = RoundedCornerShape(50), color = color.copy(alpha = 0.15f)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (risk == Risk.Medium || risk == Risk.High) Icons.Filled.Warning else Icons.Filled.Info,
                contentDescription = null, tint = color, modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(4.dp))
            // A partial read can't support a confident verdict, so the badge says so itself.
            Text(riskLabel(risk) + if (partial && risk != Risk.Unknown) " · partial" else "", color = color, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun riskColor(risk: Risk): Color {
    val dark = isSystemInDarkTheme()
    return when (risk) {
        // Neutral, not "safe" green: no known trackers says nothing about permissions or first-party tracking.
        Risk.None, Risk.Unknown -> MaterialTheme.colorScheme.onSurfaceVariant
        Risk.Low -> if (dark) Color(0xFF8AB4F8) else Color(0xFF1A5FB4)
        Risk.Medium -> if (dark) Color(0xFFFDD663) else Color(0xFF8A5300)
        Risk.High -> MaterialTheme.colorScheme.error
    }
}

/** For changes that improve privacy (a tracker or permission removed). */
@Composable
private fun improvementColor(): Color = if (isSystemInDarkTheme()) Color(0xFF81C995) else Color(0xFF1E7B34)

private fun riskLabel(risk: Risk) = when (risk) {
    Risk.None -> "No known trackers"
    Risk.Low -> "Low risk"
    Risk.Medium -> "Medium risk"
    Risk.High -> "High risk"
    Risk.Unknown -> "Not fully scanned"
}

internal fun riskHeadline(risk: Risk) = when (risk) {
    Risk.None -> "No known trackers"
    Risk.Low -> "Mostly private"
    Risk.Medium -> "Some tracking"
    Risk.High -> "Heavy tracking"
    Risk.Unknown -> "Not enough data yet"
}

@Composable
private fun SectionTitle(text: String) =
    Text(
        text,
        Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 4.dp).semantics { heading() },
        style = MaterialTheme.typography.titleMedium,
    )

/** Decoded app icons, kept across scrolling so rows don't reload them from PackageManager. */
private object IconCache {
    private val cache = object : LruCache<String, ImageBitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    fun get(pkg: String): ImageBitmap? = cache.get(pkg)

    fun load(ctx: Context, pkg: String): ImageBitmap? =
        runCatching { ctx.packageManager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap() }.getOrNull()
            ?.also { cache.put(pkg, it) }
}

@Composable
internal fun AppIcon(pkg: String, size: Int = 40) {
    val ctx = LocalContext.current
    val icon by produceState(IconCache.get(pkg), pkg) {
        if (value == null) value = withContext(Dispatchers.IO) { IconCache.load(ctx, pkg) }
    }
    icon?.let { Image(it, contentDescription = null, modifier = Modifier.size(size.dp)) } ?: Spacer(Modifier.size(size.dp))
}

private fun checkedAgo(at: Long): String {
    val now = System.currentTimeMillis()
    return if (now - at < DateUtils.MINUTE_IN_MILLIS) "just now"
    else DateUtils.getRelativeTimeSpanString(at, now, DateUtils.MINUTE_IN_MILLIS).toString()
}

internal fun plural(n: Int, noun: String) = if (n == 1) "1 $noun" else "$n ${noun}s"
