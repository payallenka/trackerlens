package app.trackerlens.core

import kotlinx.serialization.Serializable

// Every field has a default so files written by older or newer versions still load (schema evolution).
@Serializable
data class AppSnapshot(
    val packageName: String,
    val label: String = packageName,
    val versionName: String? = null,
    val versionCode: Long = 0,
    val lastUpdateTime: Long = 0,
    val trackerIds: Set<String> = emptySet(),
    val permissions: Set<String> = emptySet(),
    /** False if any code could not be read; such a snapshot is never used to report tracker changes. */
    val scanComplete: Boolean = false,
    /** An incomplete read failed for a reason that may not recur (I/O, memory), so it is read again. */
    val retryable: Boolean = true,
    /** Tracker id -> one class found in the APK that matched, shown to the user as evidence. */
    val evidence: Map<String, String> = emptyMap(),
    /** Preinstalled with the phone (possibly updated since). */
    val isSystem: Boolean = false,
    /** Listed without "Include system apps": user-installed, or a system app the user has updated. */
    val shownByDefault: Boolean = true,
    /** Catalog and scanner version this snapshot was matched with; tracker results are only compared within one key. */
    val scanKey: String = "",
)

@Serializable
data class AppChange(
    val detectedAt: Long,
    val packageName: String,
    val label: String = packageName,
    val fromVersion: String? = null,
    val toVersion: String? = null,
    val addedTrackers: Set<String> = emptySet(),
    val removedTrackers: Set<String> = emptySet(),
    val addedPermissions: Set<String> = emptySet(),
    val removedPermissions: Set<String> = emptySet(),
) {
    val isWorse: Boolean get() = addedTrackers.isNotEmpty() || addedPermissions.isNotEmpty()
    val isEmpty: Boolean get() =
        addedTrackers.isEmpty() && removedTrackers.isEmpty() && addedPermissions.isEmpty() && removedPermissions.isEmpty()
}

object SnapshotDiff {
    /**
     * Compares two scans of the same app. Tracker differences are reported only when
     * [trackersComparable] (see [Reconciler.change]); permissions come from the platform and are
     * always comparable.
     */
    fun diff(old: AppSnapshot, new: AppSnapshot, now: Long, trackersComparable: Boolean): AppChange =
        AppChange(
            detectedAt = now,
            packageName = new.packageName,
            label = new.label,
            fromVersion = old.versionName,
            toVersion = new.versionName,
            addedTrackers = if (trackersComparable) (new.trackerIds - old.trackerIds).bounded() else emptySet(),
            removedTrackers = if (trackersComparable) (old.trackerIds - new.trackerIds).bounded() else emptySet(),
            addedPermissions = (new.permissions - old.permissions).bounded(),
            removedPermissions = (old.permissions - new.permissions).bounded(),
        )

    /** Keeps each stored history entry small however many permissions an app churns through. */
    private fun Set<String>.bounded(): Set<String> = if (size <= MAX_ITEMS_PER_CHANGE) this else take(MAX_ITEMS_PER_CHANGE).toSet()

    const val MAX_ITEMS_PER_CHANGE = 64
}

/** [Unknown]: the code could not be fully read and no trackers were found, so nothing can be concluded. */
enum class Risk { None, Low, Medium, High, Unknown }

/**
 * Tracker-based privacy score: 100 means no known trackers. Each tracker costs points by its most
 * invasive category, so an ad network weighs more than a crash reporter. Permissions are shown
 * separately and not scored, because many are legitimate (a messenger needs the microphone).
 */
object PrivacyScore {
    private val categoryWeight = mapOf(
        "Advertisement" to 15,
        "Profiling" to 15,
        "Location" to 15,
        "Identification" to 10,
        "Analytics" to 8,
        "Crash reporting" to 3,
    )
    private const val UNCATEGORISED_WEIGHT = 5

    fun weight(tracker: TrackerDef): Int =
        tracker.categories.maxOfOrNull { categoryWeight[it] ?: UNCATEGORISED_WEIGHT } ?: UNCATEGORISED_WEIGHT

    fun of(trackers: Collection<TrackerDef>): Int = (100 - trackers.sumOf(::weight)).coerceIn(0, 100)

    fun risk(score: Int, trackerCount: Int): Risk = when {
        trackerCount == 0 -> Risk.None
        score >= 85 -> Risk.Low
        score >= 60 -> Risk.Medium
        else -> Risk.High
    }
}
