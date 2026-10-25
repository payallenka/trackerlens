package app.trackerlens.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.trackerlens.TrackerLensApp
import app.trackerlens.core.Alerts
import app.trackerlens.core.AppChange
import app.trackerlens.core.PrivacyScore
import app.trackerlens.core.Risk
import app.trackerlens.core.TrackerDef
import app.trackerlens.data.AppReport
import app.trackerlens.data.ScanRepository
import app.trackerlens.data.ScanResult
import app.trackerlens.data.Settings
import app.trackerlens.work.AlertNotifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

enum class SortOrder(val label: String) { HighestRisk("Highest risk"), Name("Name"), RecentlyUpdated("Recently updated") }

enum class ThemeMode(val label: String) { System("System default"), Light("Light"), Dark("Dark") }

/** The dashboard's sections, one per bottom-navigation tab. */
enum class Tab(val label: String) { Home("Home"), Apps("Apps"), Trackers("Trackers"), Alerts("Alerts") }

data class TrackerUsage(val tracker: TrackerDef, val appCount: Int)

/** Dashboard figures derived from one set of reports. */
data class Summary(
    val score: Int,
    val risk: Risk,
    val appsWithTrackers: Int,
    val topTrackers: List<TrackerUsage>,
    val distinctTrackers: Int,
    /** How many apps the score covers (fully read); 0 means there is no score yet. */
    val scoredApps: Int = 0,
    /** True when the score covers apps the user installed (the normal case), false if it fell back to system apps. */
    val scoredUserApps: Boolean = true,
    /** Preinstalled apps with trackers, reported separately from the score. */
    val systemAppsWithTrackers: Int = 0,
    /** Apps the user installed with the most trackers, for the results reveal. */
    val mostTracked: List<AppReport> = emptyList(),
    val userApps: Int = 0,
    val preinstalledApps: Int = 0,
    /** Apps the user installed that contain trackers: the headline figure, in the score's own scope. */
    val userAppsWithTrackers: Int = 0,
    /** Every tracker found, most common first (the Trackers tab). */
    val allTrackers: List<TrackerUsage> = emptyList(),
    /** Most common tracker among the apps the user installed (null if none of them has one). */
    val topUserTracker: TrackerUsage? = null,
) {
    companion object {
        fun of(reports: List<AppReport>): Summary {
            val usage = reports.flatMap { it.trackers }.groupingBy { it }.eachCount()
            // Scored over apps the user installed, so turning on hundreds of mostly clean system apps
            // can't dilute the score. Falls back to all apps if every app is a system app.
            // Apps whose code couldn't be fully read may hold more trackers than found, so they're left out of the score.
            val complete = reports.filter { it.snapshot.scanComplete }
            val userApps = complete.filterNot { it.snapshot.isSystem }
            val scored = userApps.ifEmpty { complete }
            val score = if (scored.isEmpty()) 100 else scored.sumOf { it.score } / scored.size
            return Summary(
                score = score,
                risk = if (scored.isEmpty()) Risk.Unknown else PrivacyScore.risk(score, scored.sumOf { it.trackers.size }),
                scoredApps = scored.size,
                scoredUserApps = userApps.isNotEmpty(),
                systemAppsWithTrackers = reports.count { it.snapshot.isSystem && it.trackers.isNotEmpty() },
                // The reveal and the plan focus on apps the user chose to install: those they can act on.
                mostTracked = reports.filter { it.trackers.isNotEmpty() && !it.snapshot.isSystem }
                    .sortedWith(compareBy<AppReport> { it.score }.thenByDescending { it.trackers.size }).take(3),
                userApps = reports.count { !it.snapshot.isSystem },
                userAppsWithTrackers = reports.count { !it.snapshot.isSystem && it.trackers.isNotEmpty() },
                topUserTracker = reports.filterNot { it.snapshot.isSystem }.flatMap { it.trackers }.groupingBy { it }.eachCount()
                    .maxByOrNull { it.value }?.let { TrackerUsage(it.key, it.value) },
                preinstalledApps = reports.count { it.snapshot.isSystem },
                appsWithTrackers = reports.count { it.trackers.isNotEmpty() },
                allTrackers = usage.entries.sortedWith(compareByDescending<Map.Entry<TrackerDef, Int>> { it.value }.thenBy { it.key.name })
                    .map { TrackerUsage(it.key, it.value) },
                topTrackers = usage.entries.sortedByDescending { it.value }.take(3).map { TrackerUsage(it.key, it.value) },
                distinctTrackers = usage.size,
            )
        }
    }
}

/**
 * Screen state. The last block holds values derived from the others; they are recomputed only
 * when their inputs change (see [withDerived]), not on every recomposition or progress tick.
 */
data class UiState(
    val loaded: Boolean = false,
    val scanning: Boolean = false,
    val progress: Pair<Int, Int>? = null,
    val reports: List<AppReport> = emptyList(),
    val changes: List<AppChange> = emptyList(),
    val lastScanAt: Long = 0,
    val catalogVersion: String = "",
    val includeSystem: Boolean = false,
    val theme: ThemeMode = ThemeMode.System,
    /** First-run journey step (see [Course]); null once completed. */
    val course: Course? = null,
    /** Live discoveries during a scan, for the scanning course. */
    val appsWithTrackersSoFar: Int = 0,
    val lastDiscovery: String? = null,
    val reviewed: Set<String> = emptySet(),
    val notificationsOn: Boolean = true,
    /** The last scan failed (storage error); the journey offers a retry. */
    val scanFailed: Boolean = false,
    val query: String = "",
    val trackerFilter: TrackerDef? = null,
    val sort: SortOrder = SortOrder.HighestRisk,
    val tab: Tab = Tab.Home,
    val selected: String? = null,
    val alertsSeenUntil: Long = 0,
    /** Alerts newer than this are labelled "New" on the Alerts screen (the seen mark when it was opened). */
    val newAlertsAfter: Long = Long.MAX_VALUE,
    /** One-off message shown as a snackbar, then cleared. */
    val message: String? = null,
    // Derived:
    val summary: Summary = Summary.of(emptyList()),
    val visible: List<AppReport> = emptyList(),
    val alerts: List<AppChange> = emptyList(),
    val unreadAlerts: Int = 0,
    /** Medium/High-risk apps, riskiest first; the journey asks the user to review these. */
    val riskyApps: List<AppReport> = emptyList(),
) {
    val nextToReview: AppReport? get() = riskyApps.firstOrNull { it.snapshot.packageName !in reviewed }
    val reviewedRisky: Int get() = riskyApps.count { it.snapshot.packageName in reviewed }
}

/** The first-run journey, in order. */
enum class Course { Welcome, Consent, Scan, Results, Plan }

private fun UiState.withDerived(old: UiState?): UiState {
    if (old == null) {
        return copy(summary = Summary.of(reports), visible = computeVisible(), alerts = computeAlerts(), unreadAlerts = computeUnread(), riskyApps = computeRisky())
    }
    val reportsChanged = reports !== old.reports
    val listChanged = reportsChanged || query != old.query || sort != old.sort || trackerFilter != old.trackerFilter
    val alertsChanged = changes !== old.changes || alertsSeenUntil != old.alertsSeenUntil
    return copy(
        summary = if (reportsChanged) Summary.of(reports) else old.summary,
        visible = if (listChanged) computeVisible() else old.visible,
        alerts = if (alertsChanged) computeAlerts() else old.alerts,
        unreadAlerts = if (alertsChanged) computeUnread() else old.unreadAlerts,
        riskyApps = if (reportsChanged) computeRisky() else old.riskyApps,
    )
}

/** Medium/High-risk apps the user installed (preinstalled apps can't be replaced, so they aren't "to-dos"). */
private fun UiState.computeRisky() =
    reports.filter { !it.snapshot.isSystem && (it.risk == Risk.High || it.risk == Risk.Medium) }
        .sortedWith(compareBy<AppReport> { it.score }.thenByDescending { it.trackers.size })

private fun UiState.computeAlerts() = changes.filter { it.isWorse }.asReversed()
private fun UiState.computeUnread() = Alerts.unseen(changes, alertsSeenUntil).size

private fun UiState.computeVisible(): List<AppReport> = reports
    .filter { trackerFilter == null || trackerFilter in it.trackers }
    // Matches the name people see; package names made results look random ("goo" matched com.google.*).
    .filter { query.isBlank() || it.snapshot.label.contains(query.trim(), ignoreCase = true) }
    .let { list ->
        when (sort) {
            SortOrder.HighestRisk -> list.sortedWith(compareBy<AppReport> { it.score }.thenByDescending { it.trackers.size })
            SortOrder.Name -> list.sortedBy { it.snapshot.label.lowercase() }
            SortOrder.RecentlyUpdated -> list.sortedByDescending { it.snapshot.lastUpdateTime }
        }
    }

/** Resumes the journey where the user left it (also after the app was closed), or skips it once finished. */
private fun journeyStart(settings: Settings, saved: SavedStateHandle): Course? {
    val stored = saved.get<String>("course") ?: settings.journeyCourse
    if (settings.onboarded && stored == null) return null
    return stored?.let { runCatching { Course.valueOf(it) }.getOrNull() } ?: Course.Welcome
}

class MainViewModel(
    private val repo: ScanRepository,
    private val settings: Settings,
    private val notifier: AlertNotifier,
    private val saved: SavedStateHandle,
) : ViewModel() {
    // The open screen and app are kept in saved state so they survive Android killing the process.
    private val _state = MutableStateFlow(
        UiState(
            includeSystem = settings.includeSystem,
            selected = saved[SELECTED],
            theme = ThemeMode.entries.firstOrNull { it.name.equals(settings.theme, ignoreCase = true) } ?: ThemeMode.System,
            tab = saved.get<String>(TAB)?.let { runCatching { Tab.valueOf(it) }.getOrNull() } ?: Tab.Home,
            alertsSeenUntil = settings.alertsSeenUntil,
            newAlertsAfter = saved.get<Long>(NEW_AFTER) ?: Long.MAX_VALUE,
            course = journeyStart(settings, saved),
            reviewed = settings.reviewedApps,
            notificationsOn = notifier.enabled(),
        ).withDerived(null),
    )
    val state: StateFlow<UiState> = _state

    fun permissionLabel(p: String) = repo.permissionLabel(p)
    fun trackerName(id: String) = repo.trackerName(id)
    fun trackerCategories(id: String) = repo.trackerCategories(id)

    init {
        viewModelScope.launch {
            guarded { applyResult(repo.current(settings.includeSystem)) }
            // During the first-run journey the scan starts at its own course, not behind the welcome screen.
            val course = _state.value.course
            if (course == null || course == Course.Scan) rescan()
        }
        // Pick up results stored by the background check while this screen is open.
        viewModelScope.launch {
            repo.revision.drop(1).collect {
                if (!_state.value.scanning) guarded { applyResult(repo.current(_state.value.includeSystem)) }
            }
        }
    }

    private fun set(f: (UiState) -> UiState) = _state.update { old -> f(old).withDerived(old) }

    /** True only while the screen is visible; nothing counts as "seen" in the background. */
    private var visible = false

    fun onBackground() { visible = false }

    /** Called when the app comes to the foreground. */
    fun onForeground() {
        visible = true
        set { it.copy(notificationsOn = notifier.enabled()) }
        notifier.cancel()
        markAlertsSeenIfVisible()
        val s = _state.value
        // During the journey, nothing is scanned before the user reaches the scan course.
        if (!s.loaded || s.scanning || (s.course != null && s.course < Course.Scan)) return
        // A full rescan only if results are older than a few minutes; otherwise just pick up
        // whatever the background check stored (from memory, no disk or APK access).
        if (System.currentTimeMillis() - s.lastScanAt > RESCAN_AFTER_MS) rescan()
        else viewModelScope.launch { guarded { applyResult(repo.current(s.includeSystem)) } }
    }

    fun rescan() {
        if (_state.value.scanning) return
        viewModelScope.launch { runScan() }
    }

    /** Returns false if a scan was already running or failed (callers then skip their follow-up). */
    private suspend fun runScan(): Boolean {
        if (_state.value.scanning) return false
        // A retry clears the previous failure's message, so it isn't shown again after a success.
        set {
            it.copy(
                scanning = true, progress = null, appsWithTrackersSoFar = 0, lastDiscovery = null, scanFailed = false,
                message = if (it.scanFailed) null else it.message,
            )
        }
        var ok = false
        try {
            ok = guarded {
                applyResult(repo.scan(_state.value.includeSystem) { done, total, app ->
                    // Live discoveries are only shown on the journey's scan course; skip the updates otherwise.
                    if (app.trackerIds.isNotEmpty() && !app.isSystem && _state.value.course == Course.Scan) {
                        val discovery = "Found ${repo.trackerName(app.trackerIds.first())} in ${app.label}"
                        set { s -> s.copy(appsWithTrackersSoFar = s.appsWithTrackersSoFar + 1, lastDiscovery = discovery) }
                    }
                    // Throttled and monotonic: parallel workers report out of order.
                    if (done == total || done % PROGRESS_STEP == 0) {
                        set { s -> if ((s.progress?.first ?: 0) < done) s.copy(progress = done to total) else s }
                    }
                })
            }
        } finally {
            set { it.copy(scanning = false, progress = null, scanFailed = !ok) }
        }
        if (scopeChangePending) {
            scopeChangePending = false
            applyScope(_state.value.includeSystem)
        }
        return ok
    }

    /** Runs a storage operation; failures become a message instead of crashing the app. */
    private inline fun guarded(block: () -> Unit): Boolean = try {
        block()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        set { it.copy(message = "Couldn't save or read scan results. Free up storage and try again.") }
        false
    } catch (e: RuntimeException) {
        set { it.copy(message = "Something went wrong while scanning. Please try again.") }
        false
    }

    private fun applyResult(r: ScanResult) {
        // An app opened from the list may have been uninstalled meanwhile; drop the dangling selection.
        val selected = _state.value.selected?.takeIf { pkg -> r.reports.any { it.snapshot.packageName == pkg } }
        if (selected == null) saved[SELECTED] = null
        if (r.notice != null) pendingNotice = r.notice
        // Forget "reviewed" marks for apps that are gone.
        val installed = r.reports.mapTo(HashSet()) { it.snapshot.packageName }
        if (_state.value.includeSystem && _state.value.reviewed.any { it !in installed }) {
            val kept = _state.value.reviewed.filterTo(HashSet()) { it in installed }
            settings.reviewedApps = kept
            set { it.copy(reviewed = kept) }
        }
        set {
            it.copy(
                loaded = true, reports = r.reports, changes = r.changes, lastScanAt = r.lastScanAt,
                catalogVersion = r.catalogVersion, selected = selected, message = it.message ?: r.notice,
            )
        }
        markAlertsSeenIfVisible()
    }

    /** Alerts count as seen only while the Alerts list itself is on screen. */
    private fun markAlertsSeenIfVisible() {
        val s = _state.value
        if (!visible || s.tab != Tab.Alerts || s.selected != null) return
        // The user is looking at the alerts, so a notification for them is redundant.
        notifier.cancel()
        val newest = s.changes.maxOfOrNull { it.detectedAt } ?: return
        if (newest <= s.alertsSeenUntil) return
        settings.alertsSeenUntil = newest
        set { it.copy(alertsSeenUntil = newest) }
    }

    /** A scope change made while a scan was running; applied as soon as that scan finishes. */
    private var scopeChangePending = false

    fun setIncludeSystem(value: Boolean) {
        settings.includeSystem = value
        set { it.copy(includeSystem = value) }
        // The switch always responds: during a scan the change is queued, not ignored.
        if (_state.value.scanning) {
            scopeChangePending = true
            set {
                it.copy(message = if (value) "System apps will be added when the current scan finishes" else "System apps will be hidden when the current scan finishes")
            }
            return
        }
        applyScope(value)
    }

    private fun applyScope(value: Boolean) {
        viewModelScope.launch {
            // Hidden apps' results are kept, so showing fewer apps needs no reading; showing more may.
            val ok = if (value) runScan() else guarded { applyResult(repo.current(false)) }
            if (!ok) return@launch
            val reports = _state.value.reports
            val system = reports.count { it.snapshot.isSystem }
            val msg = if (value) "Now showing ${reports.size} apps, including $system system apps"
            else "Now showing ${reports.size} apps you installed or updated"
            set { it.copy(message = msg) }
        }
    }

    /** Called once the snackbar has actually shown [UiState.message]. */
    fun messageShown() {
        val wasNotice = _state.value.message != null && _state.value.message == pendingNotice
        set { it.copy(message = null) }
        if (wasNotice) {
            pendingNotice = null
            viewModelScope.launch { guarded { repo.acknowledgeNotice() } }
        }
    }

    private var pendingNotice: String? = null
    fun setQuery(q: String) = set { it.copy(query = q) }
    fun setSort(s: SortOrder) = set { it.copy(sort = s) }
    /** Choosing a tracker shows the apps that contain it, on the Apps tab; Back returns to where it was chosen. */
    fun filterByTracker(t: TrackerDef?) {
        if (t != null) {
            val origin = _state.value.tab.takeIf { it != Tab.Apps } ?: Tab.Home
            saved[FILTER_ORIGIN] = origin.name
            selectTab(Tab.Apps)
        }
        set { it.copy(trackerFilter = t) }
    }

    /** Back from a tracker's app list: back to the list the tracker was picked from (Home or Trackers). */
    fun backFromTrackerFilter() {
        val origin = saved.get<String>(FILTER_ORIGIN)?.let { runCatching { Tab.valueOf(it) }.getOrNull() } ?: Tab.Home
        selectTab(origin)
    }

    fun select(pkg: String?) {
        saved[SELECTED] = pkg
        set { it.copy(selected = pkg) }
        markAlertsSeenIfVisible()
    }

    fun selectTab(tab: Tab) {
        if (tab == _state.value.tab) return
        saved[TAB] = tab.name
        // A tracker filter belongs to one visit to the Apps tab; leaving it ends that visit.
        if (_state.value.tab == Tab.Apps && _state.value.trackerFilter != null) set { it.copy(trackerFilter = null) }
        if (tab == Tab.Alerts) {
            notifier.cancel()
            saved[NEW_AFTER] = _state.value.alertsSeenUntil
            set { it.copy(tab = tab, newAlertsAfter = it.alertsSeenUntil) }
            markAlertsSeenIfVisible()
        } else {
            set { it.copy(tab = tab) }
        }
    }

    fun openAlerts() = selectTab(Tab.Alerts)

    // ---------- First-run journey ----------

    private fun goTo(course: Course?) {
        saved[COURSE] = course?.name
        settings.journeyCourse = course?.name
        set { it.copy(course = course) }
    }

    /** Moves to the next course; arriving at the scan course starts a scan unless results are fresh. */
    fun nextCourse() {
        val current = _state.value.course ?: return
        val next = Course.entries.getOrNull(current.ordinal + 1) ?: return finishJourney()
        goTo(next)
        if (next == Course.Scan) settings.scanConsented = true
        val s = _state.value
        if (next == Course.Scan && (!s.loaded || s.lastScanAt == 0L || System.currentTimeMillis() - s.lastScanAt > RESCAN_AFTER_MS)) rescan()
    }

    /** Back within the journey. Results goes back to the finished scan (no rescan). */
    fun previousCourse() {
        val prev = when (_state.value.course) {
            Course.Consent -> Course.Welcome
            Course.Results -> Course.Scan
            Course.Scan -> if (_state.value.scanning) return else Course.Consent
            Course.Plan -> Course.Results
            else -> return
        }
        goTo(prev)
    }

    fun finishJourney() {
        settings.onboarded = true
        goTo(null)
    }

    /** True when the journey is being replayed by someone who has finished it before (they can close it). */
    val isReplay: Boolean get() = settings.onboarded

    fun replayJourney() = goTo(Course.Welcome)

    fun markAlertsAsked() { settings.alertsAsked = true }
    val alertsAskedBefore: Boolean get() = settings.alertsAsked

    /** Reviewing is an explicit decision by the user, not a side effect of opening an app. */
    fun setReviewed(pkg: String, reviewed: Boolean) {
        val next = if (reviewed) _state.value.reviewed + pkg else _state.value.reviewed - pkg
        settings.reviewedApps = next
        set { it.copy(reviewed = next) }
    }

    fun setTheme(mode: ThemeMode) {
        settings.theme = mode.name.lowercase()
        set { it.copy(theme = mode) }
    }

    fun refreshNotificationState() = set { it.copy(notificationsOn = notifier.enabled()) }



    companion object {
        private const val SELECTED = "selected"
        private const val TAB = "tab"
        private const val FILTER_ORIGIN = "filter_origin"
        private const val COURSE = "course"
        private const val NEW_AFTER = "new_after"
        private const val PROGRESS_STEP = 10
        private const val RESCAN_AFTER_MS = 5 * 60 * 1000L

        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as TrackerLensApp
                MainViewModel(app.repository, app.settings, app.notifier, createSavedStateHandle())
            }
        }
    }
}
