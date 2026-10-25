package app.trackerlens.core

/** What the platform reports about one installed app, without reading its code. */
data class InstalledApp(
    val packageName: String,
    val versionCode: Long,
    val lastUpdateTime: Long,
    val isSystem: Boolean,
    /** Listed without "Include system apps". */
    val shownByDefault: Boolean = true,
)

/**
 * Decides which apps need reading and which differences are real app changes. Pure and
 * Android-free so every rule is unit-tested; the app layer only does the I/O.
 */
object Reconciler {

    /** The app itself changed since the snapshot (a reinstall of the same version also counts). */
    fun appUpdated(old: AppSnapshot, app: InstalledApp): Boolean =
        old.versionCode != app.versionCode || old.lastUpdateTime != app.lastUpdateTime

    /**
     * Read the APK again when there is no snapshot, the app changed, the snapshot was made with a
     * different catalog or scanner, or the previous read hit a temporary failure and may have missed
     * trackers. A read that failed deterministically (corrupt or oversized code) is not repeated until
     * the app updates.
     */
    fun needsScan(old: AppSnapshot?, app: InstalledApp, scanKey: String): Boolean =
        old == null || old.scanKey != scanKey || (!old.scanComplete && old.retryable) || appUpdated(old, app)

    /**
     * A change is recorded only when the app itself was updated. Tracker differences are trusted
     * only if both scans are complete and used the same catalog and scanner; otherwise a new
     * catalog (or a read error) would look like the app adding or removing trackers.
     */
    fun change(old: AppSnapshot?, new: AppSnapshot, app: InstalledApp, now: Long): AppChange? {
        if (old == null || !appUpdated(old, app)) return null
        val comparable = old.scanKey == new.scanKey && old.scanComplete && new.scanComplete
        return SnapshotDiff.diff(old, new, now, trackersComparable = comparable).takeUnless { it.isEmpty }
    }

    /**
     * Builds the next stored state. Snapshots and history are kept for every installed app, even
     * ones outside the current view (e.g. hidden system apps) or made with an older catalog, and
     * dropped only once the app is uninstalled. Every kept snapshot gets its system/scope flags
     * refreshed from the platform, so the view never filters on stale flags.
     */
    fun merge(
        previous: StoredState,
        installed: Collection<InstalledApp>,
        fresh: Map<String, AppSnapshot>,
        newChanges: List<AppChange>,
        scanKey: String,
        now: Long,
    ): StoredState {
        val byName = installed.associateBy { it.packageName }
        val apps = (previous.apps + fresh).mapNotNull { (pkg, snap) ->
            byName[pkg]?.let { pkg to snap.copy(isSystem = it.isSystem, shownByDefault = it.shownByDefault) }
        }.toMap()
        // A strike lasts for the exact install and scanner that failed; a finished read clears a single strike.
        val crashes = previous.crashes.filter { (pkg, c) ->
            val app = byName[pkg]
            app != null && c.appliesTo(app, scanKey) && (fresh[pkg] == null || c.strikes >= QUARANTINE_STRIKES)
        }
        return StoredState(
            scanKey = scanKey,
            apps = apps,
            changes = (previous.changes + newChanges).filter { it.packageName in byName }.takeLast(StoredState.MAX_CHANGES),
            lastScanAt = now,
            crashes = crashes,
            recoveredFromCorruption = previous.recoveredFromCorruption,
        )
    }

    /**
     * Records reads that never finished (the process died mid-read). One unfinished read may be an
     * ordinary kill (swiped away, low memory); the same version failing twice is treated as an APK
     * that crashes the scanner.
     */
    fun recordUnfinishedReads(
        state: StoredState,
        unfinished: Map<String, Long>,
        updateTimes: Map<String, Long> = emptyMap(),
        scanKey: String = "",
    ): StoredState {
        if (unfinished.isEmpty()) return state
        val crashes = state.crashes.toMutableMap()
        for ((pkg, vc) in unfinished) {
            val updated = updateTimes[pkg] ?: 0
            val prev = crashes[pkg]?.takeIf { it.versionCode == vc && it.lastUpdateTime == updated && it.scanKey == scanKey }
            crashes[pkg] = CrashRecord(vc, (prev?.strikes ?: 0) + 1, updated, scanKey)
        }
        return state.copy(crashes = crashes)
    }

    /** An app with one unfinished read; it is read alone next time so a second strike can only blame the real culprit. */
    fun isSuspect(state: StoredState, app: InstalledApp, scanKey: String): Boolean =
        state.crashes[app.packageName]?.let { it.appliesTo(app, scanKey) && it.strikes in 1 until QUARANTINE_STRIKES } == true

    /** Whether this exact app version crashed the scanner repeatedly and must not be read again. */
    fun isQuarantined(state: StoredState, app: InstalledApp, scanKey: String): Boolean =
        state.crashes[app.packageName]?.let { it.appliesTo(app, scanKey) && it.strikes >= QUARANTINE_STRIKES } == true

    const val QUARANTINE_STRIKES = 2
}

/** Which alerts the user still needs to see or be notified about; shared by the UI and the worker. */
object Alerts {
    fun unseen(changes: List<AppChange>, seenUntil: Long): List<AppChange> =
        changes.filter { it.isWorse && it.detectedAt > seenUntil }

    /** True when there is an unseen alert newer than the last notification. */
    fun shouldNotify(unseen: List<AppChange>, notifiedUntil: Long): Boolean =
        (unseen.maxOfOrNull { it.detectedAt } ?: 0) > notifiedUntil
}
