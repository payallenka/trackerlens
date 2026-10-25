package app.trackerlens.core

import java.io.File

/**
 * Crash detection for the scanner. A marker file is written before an APK is read and removed
 * afterwards. If the process dies mid-read (e.g. a hostile APK triggers a native crash or the OS
 * kills the app), the marker survives and that package version is quarantined on the next run,
 * so one bad app cannot make every scan fail forever.
 */
class InFlightMarkers(private val dir: File) {
    private val safeName = Regex("[A-Za-z0-9._]{1,255}")

    fun begin(packageName: String, versionCode: Long) {
        if (!safeName.matches(packageName)) return
        dir.mkdirs()
        File(dir, packageName).writeText(versionCode.toString())
    }

    fun end(packageName: String) {
        if (safeName.matches(packageName)) File(dir, packageName).delete()
    }

    /** Packages whose read never finished, with the version being read. Unreadable markers are skipped. */
    fun unfinished(): Map<String, Long> =
        dir.listFiles().orEmpty().mapNotNull { f ->
            runCatching { f.readText().trim().toLongOrNull() }.getOrNull()?.let { f.name to it }
        }.toMap()

    /** Call only after the unfinished reads have been saved, so a crash in between can't lose them. */
    fun clear() {
        dir.listFiles().orEmpty().forEach { it.delete() }
    }
}
