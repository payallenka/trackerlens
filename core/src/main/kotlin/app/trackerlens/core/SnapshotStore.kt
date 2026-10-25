package app.trackerlens.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

@Serializable
data class StoredState(
    val schemaVersion: Int = SCHEMA_VERSION,
    /** Catalog and scanner version of the latest scan; informational (each snapshot records its own). */
    val scanKey: String = "",
    val apps: Map<String, AppSnapshot> = emptyMap(),
    val changes: List<AppChange> = emptyList(),
    /** When the last completed scan finished, or 0 if there has never been one. */
    val lastScanAt: Long = 0,
    /** Package -> unfinished reads of one version; see [Reconciler.recordUnfinishedReads]. */
    val crashes: Map<String, CrashRecord> = emptyMap(),
    /** Earlier results were unreadable and set aside; kept until the user has been told. */
    val recoveredFromCorruption: Boolean = false,
) {
    companion object {
        const val SCHEMA_VERSION = 1
        const val MAX_CHANGES = 500
    }
}

@Serializable
data class CrashRecord(val versionCode: Long, val strikes: Int, val lastUpdateTime: Long = 0, val scanKey: String = "") {
    /** The one rule for whether this record still describes [app]: same install, same scanner. */
    fun appliesTo(app: InstalledApp, currentScanKey: String): Boolean =
        app.versionCode == versionCode &&
            (lastUpdateTime == 0L || app.lastUpdateTime == lastUpdateTime) &&
            (scanKey.isEmpty() || scanKey == currentScanKey)
}

sealed interface LoadResult {
    val state: StoredState

    data class Loaded(override val state: StoredState) : LoadResult
    data object Missing : LoadResult { override val state = StoredState() }

    /** The file could not be parsed; it was moved aside to [backup] so it is never silently overwritten. */
    data class Corrupt(val backup: File, val reason: String) : LoadResult { override val state = StoredState() }
}

/**
 * All scan results in one private JSON file. Writes are crash-safe: the new content is written and
 * flushed to a temporary file, then atomically renamed over the old one, so a crash or power loss
 * leaves either the old or the new file, never a half-written one.
 */
class SnapshotStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Read errors (I/O) are thrown so a caller never mistakes "unreadable right now" for "empty" and overwrites good data. */
    @Throws(IOException::class)
    fun load(): LoadResult {
        if (!file.exists()) return LoadResult.Missing
        val text = file.readText()
        return try {
            // Check the schema before decoding: a newer version's file may not decode at all, and must
            // be preserved (not rotated away as corrupt) so upgrading again can still use it.
            val schema = (json.parseToJsonElement(text) as? JsonObject)?.get("schemaVersion")?.jsonPrimitive?.intOrNull
            if (schema != null && schema > StoredState.SCHEMA_VERSION) return setAsideNewer(schema)
            LoadResult.Loaded(json.decodeFromString(StoredState.serializer(), text))
        } catch (e: SerializationException) {
            corrupt(e)
        } catch (e: IllegalArgumentException) {
            corrupt(e)
        }
    }

    @Throws(IOException::class)
    fun save(state: StoredState) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(json.encodeToString(StoredState.serializer(), state).encodeToByteArray())
            out.fd.sync()
        }
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    /** Moves the unreadable file to a timestamped backup, keeping the newest [KEEP_BACKUPS]. */
    private fun corrupt(e: Exception): LoadResult.Corrupt {
        val backup = File(file.parentFile, "${file.name}.corrupt-${System.currentTimeMillis()}")
        Files.move(file.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING)
        file.parentFile.listFiles { f -> f.name.startsWith("${file.name}.corrupt-") }.orEmpty()
            .sortedByDescending { it.name.substringAfterLast('-').toLongOrNull() ?: 0 }
            .drop(KEEP_BACKUPS).forEach { it.delete() }
        return LoadResult.Corrupt(backup, e.message ?: e.javaClass.simpleName)
    }

    /**
     * A file from a newer app version (after a downgrade) is kept under its own name, outside the
     * corrupt-file rotation, so upgrading again can still find it.
     */
    private fun setAsideNewer(schema: Int): LoadResult.Corrupt {
        val kept = File(file.parentFile, "${file.name}.schema-$schema-${System.currentTimeMillis()}")
        Files.move(file.toPath(), kept.toPath())
        file.parentFile.listFiles { f -> f.name.startsWith("${file.name}.schema-") }.orEmpty()
            .sortedByDescending { it.name.substringAfterLast('-').toLongOrNull() ?: 0 }
            .drop(KEEP_BACKUPS).forEach { it.delete() }
        return LoadResult.Corrupt(kept, "written by a newer version (schema $schema)")
    }

    private companion object {
        const val KEEP_BACKUPS = 3
    }
}
