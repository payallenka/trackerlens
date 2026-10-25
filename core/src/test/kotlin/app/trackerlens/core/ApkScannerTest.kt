package app.trackerlens.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ApkScannerTest {
    private fun apk(vararg entries: Pair<String, ByteArray>): File {
        val f = File.createTempFile("test", ".apk").apply { deleteOnExit() }
        ZipOutputStream(f.outputStream()).use { z -> entries.forEach { (n, b) -> z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
        return f
    }

    @Test fun scansMultidexApkAndKeepsPartialResults() {
        val f = apk(
            "classes.dex" to fakeDex("Lcom/example/Main;"),
            "classes2.dex" to fakeDex("Lcom/facebook/ads/AdView;"),
            "classes3.dex" to ByteArray(4),
            "assets/classes.dex" to ByteArray(4),
        )
        val scan = ApkScanner(TrackerMatcher(testCatalog)).scan(listOf(f))
        assertEquals(setOf("fb"), scan.trackers.keys)
        assertEquals(2, scan.dexFilesRead)
        assertEquals(1, scan.failures.size)
        assertFalse(scan.complete)
    }

    @Test fun oversizedDexIsSkippedAndReported() {
        val f = apk("classes.dex" to fakeDex("Lcom/facebook/ads/AdView;"), "classes2.dex" to ByteArray(2_000))
        val scan = ApkScanner(TrackerMatcher(testCatalog), maxDexBytes = 1_000).scan(listOf(f))
        assertEquals(setOf("fb"), scan.trackers.keys)
        assertEquals(1, scan.failures.size)
    }

    @Test fun totalCodePerAppIsBudgeted() {
        val dex = fakeDex("Lcom/facebook/ads/AdView;")
        val f = apk("classes.dex" to dex, "classes2.dex" to dex, "classes3.dex" to dex)
        val scan = ApkScanner(TrackerMatcher(testCatalog), maxBytesPerApp = dex.size.toLong()).scan(listOf(f))
        assertEquals(1, scan.dexFilesRead)
        assertEquals(2, scan.failures.size)
    }

    @Test fun stopsWhenAsked() {
        val f = apk("classes.dex" to fakeDex("Lcom/facebook/ads/AdView;"))
        val scan = ApkScanner(TrackerMatcher(testCatalog)).scan(listOf(f), shouldStop = { true })
        assertEquals(0, scan.dexFilesRead)
        assertFalse(scan.complete)
        assertTrue(scan.retryable)
    }

    @Test fun readBoundedDoesNotTrustDeclaredSize() {
        val scanner = ApkScanner(TrackerMatcher(testCatalog), maxDexBytes = 100)
        val data = ByteArray(50) { it.toByte() }
        assertArrayEquals(data, scanner.readBounded(ByteArrayInputStream(data), declaredSize = 50))
        assertArrayEquals(data, scanner.readBounded(ByteArrayInputStream(data), declaredSize = -1))
        // Declared small but actually larger than the cap (a lying or zip-bomb entry).
        assertNull(scanner.readBounded(ByteArrayInputStream(ByteArray(500)), declaredSize = 10))
        assertNull(scanner.readBounded(ByteArrayInputStream(ByteArray(500)), declaredSize = -1))
        assertNull(scanner.readBounded(ByteArrayInputStream(data), declaredSize = 1_000))
    }

    @Test fun corruptArchiveIsAFailureNotACrash() {
        val f = File.createTempFile("bad", ".apk").apply { deleteOnExit(); writeBytes(ByteArray(100) { 0x50 }) }
        val scan = ApkScanner(TrackerMatcher(testCatalog)).scan(listOf(f, File("/does/not/exist.apk")))
        assertEquals(2, scan.failures.size)
        assertEquals(0, scan.dexFilesRead)
    }
}
