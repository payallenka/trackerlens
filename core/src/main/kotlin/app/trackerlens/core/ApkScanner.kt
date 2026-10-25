package app.trackerlens.core

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipFile

data class ApkScan(
    val trackers: Map<String, TrackerMatcher.Match>,
    val classCount: Int,
    val dexFilesRead: Int,
    /** Per-file problems; a scan with failures still reports what the readable DEX files contained. */
    val failures: List<String>,
    /** At least one failure may not happen again (I/O, memory), so reading again is worthwhile. */
    val retryable: Boolean = false,
) {
    val complete: Boolean get() = failures.isEmpty()
}

/** Scans base and split APKs for tracker code. Never executes or modifies the scanned app. */
class ApkScanner(
    private val matcher: TrackerMatcher,
    private val maxDexBytes: Int = MAX_DEX_BYTES,
    private val maxBytesPerApp: Long = MAX_BYTES_PER_APP,
) {

    /** [shouldStop] is checked between DEX files so a cancelled scan stops promptly. */
    fun scan(apks: List<File>, shouldStop: () -> Boolean = { false }): ApkScan {
        val trackers = LinkedHashMap<String, TrackerMatcher.Match>()
        val failures = BoundedList(MAX_FAILURES)
        var classCount = 0
        var dexFiles = 0
        var retryable = false
        var budget = maxBytesPerApp
        for (apk in apks) {
            try {
                ZipFile(apk).use { zip ->
                    for (entry in zip.entries()) {
                        if (!DEX_ENTRY.matches(entry.name)) continue
                        val where = "${apk.name.take(64)}!${entry.name.take(64)}"
                        if (shouldStop()) {
                            failures += "$where: scan stopped"
                            retryable = true
                            return ApkScan(trackers, classCount, dexFiles, failures.toList(), retryable)
                        }
                        if (budget <= 0) {
                            // Bounds total decompression for apps with many huge code files (zip bombs).
                            failures += "$where: app exceeds $maxBytesPerApp bytes of code, skipped"
                            continue
                        }
                        try {
                            val bytes = zip.getInputStream(entry).use { readBounded(it, entry.size) }
                            if (bytes == null) {
                                failures += "$where: larger than $maxDexBytes bytes, skipped"
                                continue
                            }
                            budget -= bytes.size
                            classCount += DexClassReader.forEachClassName(bytes) { chars, len -> matcher.matchInto(trackers, chars, len) }
                            dexFiles++
                        } catch (e: DexFormatException) {
                            failures += "$where: ${e.message}"
                        }
                    }
                }
            } catch (e: IOException) {
                failures += "${apk.name}: ${e.message ?: e.javaClass.simpleName}"
                retryable = true
            } catch (e: SecurityException) {
                failures += "${apk.name}: not readable"
            } catch (e: RuntimeException) {
                // Malformed archives can make java.util.zip throw unchecked exceptions; contain them per APK.
                failures += "${apk.name}: ${e.javaClass.simpleName}"
            } catch (e: OutOfMemoryError) {
                // One hostile or huge APK must not abort the scan of every other app.
                failures += "${apk.name}: out of memory"
                retryable = true
            }
        }
        return ApkScan(trackers, classCount, dexFiles, failures.toList(), retryable)
    }

    /** Keeps the first [max] failure messages plus a count, so a crafted archive can't grow it without limit. */
    private class BoundedList(private val max: Int) {
        private val items = ArrayList<String>()
        private var dropped = 0
        operator fun plusAssign(s: String) { if (items.size < max) items += s else dropped++ }
        fun toList(): List<String> = if (dropped == 0) items.toList() else items + "... and $dropped more"
    }

    /**
     * Reads an entry with a hard limit that does not trust the archive's declared size (it can be
     * missing or false). Returns null if the content exceeds [maxDexBytes].
     */
    internal fun readBounded(input: InputStream, declaredSize: Long): ByteArray? {
        if (declaredSize > maxDexBytes) return null
        var buf = ByteArray(if (declaredSize in 1..maxDexBytes) declaredSize.toInt() else minOf(INITIAL_BUFFER, maxDexBytes))
        var n = 0
        while (true) {
            if (n == buf.size) {
                // Probe one byte: with a truthful declared size the stream ends here and buf is returned uncopied.
                val extra = input.read()
                if (extra == -1) return buf
                if (n >= maxDexBytes) return null
                buf = buf.copyOf(minOf(maxDexBytes, maxOf(n * 2, n + 1)))
                buf[n++] = extra.toByte()
            }
            val r = input.read(buf, n, buf.size - n)
            if (r == -1) break
            n += r
        }
        return if (n == buf.size) buf else buf.copyOf(n)
    }

    companion object {
        private val DEX_ENTRY = Regex("classes\\d*\\.dex")
        private const val INITIAL_BUFFER = 1 shl 20
        private const val MAX_FAILURES = 20

        /** Largest real DEX seen in testing was 14 MB (Zoom); 48 MB leaves room while bounding memory (2 scans in parallel). */
        const val MAX_DEX_BYTES = 48 * 1024 * 1024

        /** Zoom's 16 DEX files total about 150 MB; 512 MB bounds decompression work per app. */
        const val MAX_BYTES_PER_APP = 512L * 1024 * 1024
    }
}
