package app.trackerlens.data

import android.content.Context
import android.content.pm.PackageInfo
import android.util.Log
import app.trackerlens.core.ApkScanner
import app.trackerlens.core.AppChange
import app.trackerlens.core.AppSnapshot
import app.trackerlens.core.InFlightMarkers
import app.trackerlens.core.InstalledApp
import app.trackerlens.core.LoadResult
import app.trackerlens.core.PrivacyScore
import app.trackerlens.core.Reconciler
import app.trackerlens.core.Risk
import app.trackerlens.core.SnapshotStore
import app.trackerlens.core.StoredState
import app.trackerlens.core.TextSafety
import app.trackerlens.core.TrackerCatalog
import app.trackerlens.core.TrackerDef
import app.trackerlens.core.TrackerMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

data class AppReport(
    val snapshot: AppSnapshot,
    val trackers: List<TrackerDef>,
    val dangerousPermissions: List<String>,
) {
    // Computed once: lists sort and filter by these.
    val score: Int = PrivacyScore.of(trackers)
    val risk: Risk = if (!snapshot.scanComplete && trackers.isEmpty()) Risk.Unknown else PrivacyScore.risk(score, trackers.size)
}

/** The apps and history in view (respecting "Include system apps"). */
data class ScanResult(
    val reports: List<AppReport>,
    val changes: List<AppChange>,
    val lastScanAt: Long,
    val catalogVersion: String,
    /** Present while stored results were set aside as unreadable and the user hasn't been told yet. */
    val notice: String? = null,
)

/**
 * Owns scanning and the stored results. One instance per process (see TrackerLensApp), shared by
 * the UI and the background worker; a mutex serialises every read and write of the state, which is
 * also kept in memory so the file is decoded once.
 */
class ScanRepository(context: Context) {
    private val inventory = AppInventory(context)
    private val store = SnapshotStore(File(context.filesDir, "snapshots.json"))
    private val markers = InFlightMarkers(File(context.filesDir, "inflight"))
    private val mutex = Mutex()
    private val scanSlots = Semaphore(PARALLEL_SCANS)
    private var memory: StoredState? = null
    private var cached: Triple<Long, Boolean, ScanResult>? = null

    private val _revision = MutableStateFlow(0L)

    /** Changes whenever stored results change, so an open screen can pick up a background check's results. */
    val revision: StateFlow<Long> = _revision

    val catalog: TrackerCatalog by lazy {
        TrackerCatalog.parse(context.assets.open("trackers.json").bufferedReader().use { it.readText() })
    }
    private val scanKey by lazy { "${catalog.version}/scanner-$SCANNER_VERSION" }
    private val trackersById by lazy { catalog.trackers.associateBy { it.id } }
    private val scanner by lazy { ApkScanner(TrackerMatcher(catalog)) }

    fun permissionLabel(permission: String) = inventory.permissionLabel(permission)
    fun trackerName(id: String): String = trackersById[id]?.name ?: id
    fun trackerCategories(id: String): List<String> = trackersById[id]?.categories.orEmpty()

    /** The UI calls this once it has shown [ScanResult.notice]; until then every result carries it (persisted). */
    suspend fun acknowledgeNotice() = mutex.withLock {
        withContext(Dispatchers.IO) {
            val s = state()
            if (s.recoveredFromCorruption) save(s.copy(recoveredFromCorruption = false))
        }
    }

    /** Stored results without scanning, for showing something instantly. */
    suspend fun current(includeSystem: Boolean): ScanResult = mutex.withLock {
        withContext(Dispatchers.IO) { result(state(), includeSystem) }
    }

    /**
     * Brings stored results up to date: reads only apps that are new, updated, previously failed
     * temporarily, or need re-matching after a catalog or scanner change. Throws on storage errors.
     */
    suspend fun scan(
        includeSystem: Boolean,
        onProgress: (done: Int, total: Int, app: AppSnapshot) -> Unit = { _, _, _ -> },
    ): ScanResult =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val started = System.currentTimeMillis()
                var previous = state()
                // Reads interrupted by a process death last time: saved before the markers are cleared.
                val unfinished = markers.unfinished()
                val packages = inventory.installedPackages()
                val installed = packages.associate { it.packageName to inventory.toInstalledApp(it) }
                if (unfinished.isNotEmpty()) {
                    previous = Reconciler.recordUnfinishedReads(previous, unfinished, installed.mapValues { it.value.lastUpdateTime }, scanKey)
                    save(previous)
                }
                markers.clear()

                // Apps with one unfinished read go first, one at a time: if one crashes again, nothing
                // else was in flight, so the second strike can't land on an innocent neighbour.
                val (suspects, others) = packages
                    .filter { includeSystem || installed.getValue(it.packageName).shownByDefault }
                    .partition { Reconciler.isSuspect(previous, installed.getValue(it.packageName), scanKey) }
                val inView = suspects + others

                val fresh = ConcurrentHashMap<String, AppSnapshot>()
                val newChanges = mutableListOf<AppChange>()
                val done = AtomicInteger()
                val read = AtomicInteger()
                val batches = suspects.map { listOf(it) } + others.chunked(CHECKPOINT_EVERY)
                for (chunk in batches) {
                    currentCoroutineContext().ensureActive()
                    val readBefore = read.get()
                    val results = coroutineScope {
                        chunk.map { info ->
                            async {
                                val app = installed.getValue(info.packageName)
                                val old = previous.apps[info.packageName]
                                val snap = when {
                                    // Skipped while the quarantine applies; retryable, so it is read once the quarantine lifts.
                                    Reconciler.isQuarantined(previous, app, scanKey) -> base(info, app)
                                    !Reconciler.needsScan(old, app, scanKey) -> old!!
                                    else -> scanSlots.withPermit { read.incrementAndGet(); snapshot(info, app) }
                                }
                                onProgress(done.incrementAndGet(), inView.size, snap)
                                snap to Reconciler.change(old, snap, app, started)
                            }
                        }.awaitAll()
                    }
                    results.forEach { (snap, change) -> fresh[snap.packageName] = snap; change?.let(newChanges::add) }
                    // Checkpoint after reading, so a long first scan that is interrupted does not start over.
                    if (read.get() > readBefore) {
                        save(Reconciler.merge(previous, installed.values, fresh, newChanges, scanKey, previous.lastScanAt))
                    }
                }
                val next = Reconciler.merge(previous, installed.values, fresh, newChanges, scanKey, System.currentTimeMillis())
                save(next)
                Log.i(TAG, "scan: ${inView.size} apps, ${read.get()} read, ${System.currentTimeMillis() - started} ms")
                result(next, includeSystem)
            }
        }

    /** Must be called with [mutex] held. */
    private fun save(state: StoredState) {
        store.save(state)
        memory = state
        cached = null
        _revision.value++
    }

    /** Must be called with [mutex] held. */
    private fun state(): StoredState = memory ?: store.load().let {
        if (it is LoadResult.Corrupt) {
            Log.w(TAG, "stored results were unreadable and were set aside")
            it.state.copy(recoveredFromCorruption = true)
        } else {
            it.state
        }
    }.also { memory = it }

    /** Must be called with [mutex] held. Reused until the stored state or the scope changes. */
    private fun result(state: StoredState, includeSystem: Boolean): ScanResult {
        cached?.let { (rev, sys, r) -> if (rev == _revision.value && sys == includeSystem) return r }
        val apps = state.apps.values.filter { includeSystem || it.shownByDefault }
        val names = apps.mapTo(HashSet()) { it.packageName }
        return ScanResult(
            reports = apps.map(::report),
            changes = state.changes.filter { it.packageName in names },
            lastScanAt = state.lastScanAt,
            catalogVersion = catalog.version,
            notice = if (state.recoveredFromCorruption) "Saved scan history couldn't be read, so TrackerLens started fresh." else null,
        ).also { cached = Triple(_revision.value, includeSystem, it) }
    }

    private suspend fun snapshot(info: PackageInfo, app: InstalledApp): AppSnapshot {
        val context = currentCoroutineContext()
        markers.begin(info.packageName, info.longVersionCode)
        val scan = try {
            scanner.scan(inventory.apkFiles(info), shouldStop = { !context.isActive })
        } finally {
            markers.end(info.packageName)
        }
        return base(info, app).copy(
            trackerIds = scan.trackers.keys,
            // Resource-only packages declare no code, so finding no DEX in them is complete, not a failure.
            scanComplete = scan.complete && (scan.dexFilesRead > 0 || !inventory.hasCode(info)),
            retryable = scan.retryable,
            evidence = scan.trackers.mapValues { it.value.exampleClass },
        )
    }

    /** Platform facts only; used directly for a quarantined APK (marked incomplete). */
    private fun base(info: PackageInfo, app: InstalledApp): AppSnapshot =
        AppSnapshot(
            packageName = info.packageName,
            label = inventory.label(info),
            // Shown in notifications and lists, and set by the other app: cleaned like labels.
            versionName = info.versionName?.let { TextSafety.clean(it, MAX_VERSION_CHARS) },
            versionCode = app.versionCode,
            lastUpdateTime = app.lastUpdateTime,
            permissions = inventory.requestedPermissions(info),
            scanComplete = false,
            isSystem = app.isSystem,
            shownByDefault = app.shownByDefault,
            scanKey = scanKey,
        )

    private fun report(s: AppSnapshot) = AppReport(
        snapshot = s,
        trackers = s.trackerIds.mapNotNull(trackersById::get).sortedBy { it.name },
        dangerousPermissions = s.permissions.filter(inventory::isDangerous).sorted(),
    )

    private companion object {
        /** Each in-flight scan holds one DEX file in memory; two keeps peak memory low on cheap phones. */
        const val PARALLEL_SCANS = 2
        const val CHECKPOINT_EVERY = 25
        const val MAX_VERSION_CHARS = 64

        /** Bump whenever scanning or completeness logic changes, so stored results are recomputed. */
        const val SCANNER_VERSION = 7
        const val TAG = "TrackerLens"
    }
}
