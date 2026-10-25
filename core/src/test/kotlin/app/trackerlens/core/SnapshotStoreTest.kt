package app.trackerlens.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class SnapshotStoreTest {
    private val dir = Files.createTempDirectory("store").toFile().apply { deleteOnExit() }
    private val file = dir.resolve("snapshots.json")

    @Test fun roundTripsAndLeavesNoTempFile() {
        val store = SnapshotStore(file)
        assertTrue(store.load() is LoadResult.Missing)
        val state = StoredState(scanKey = "k", apps = mapOf("p" to snapshot(trackers = setOf("a"))), lastScanAt = 3)
        store.save(state)
        assertEquals(state, (store.load() as LoadResult.Loaded).state)
        assertFalse(dir.resolve("snapshots.json.tmp").exists())
    }

    @Test fun corruptFileIsSetAsideNotOverwritten() {
        file.writeText("{ not json")
        val r = SnapshotStore(file).load()
        assertTrue(r is LoadResult.Corrupt)
        assertEquals("{ not json", (r as LoadResult.Corrupt).backup.readText())
        assertFalse(file.exists())
        assertTrue(r.state.apps.isEmpty())
    }

    @Test fun corruptBackupsAreBounded() {
        repeat(5) {
            file.writeText("{ broken $it")
            assertTrue(SnapshotStore(file).load() is LoadResult.Corrupt)
            Thread.sleep(2)
        }
        assertEquals(3, dir.listFiles { f -> f.name.startsWith("snapshots.json.corrupt-") }!!.size)
    }

    @Test fun newerSchemaIsKeptOutsideTheRotation() {
        file.writeText("""{"schemaVersion":99}""")
        val r = SnapshotStore(file).load()
        assertTrue(r is LoadResult.Corrupt)
        assertTrue(dir.listFiles { f -> f.name.startsWith("snapshots.json.schema-99-") }!!.size == 1)
        assertFalse(file.exists())
    }

    @Test fun newerSchemaThatNoLongerDecodesIsStillPreserved() {
        file.writeText("""{"schemaVersion":5,"apps":"a different shape"}""")
        assertTrue(SnapshotStore(file).load() is LoadResult.Corrupt)
        assertEquals(1, dir.listFiles { f -> f.name.startsWith("snapshots.json.schema-5-") }!!.size)
        assertEquals(0, dir.listFiles { f -> f.name.contains(".corrupt-") }!!.size)
    }

    @Test fun loadsFilesWrittenWithFewerFields() {
        file.writeText("""{"scanKey":"k","apps":{"p":{"packageName":"p","versionCode":3}},"changes":[{"detectedAt":1,"packageName":"p"}]}""")
        val s = (SnapshotStore(file).load() as LoadResult.Loaded).state
        assertEquals(3L, s.apps.getValue("p").versionCode)
        assertFalse("unknown completeness must mean rescan", s.apps.getValue("p").scanComplete)
        assertEquals(1, s.changes.size)
    }
}
