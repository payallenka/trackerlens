package app.trackerlens.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class InFlightMarkersTest {
    private val dir = Files.createTempDirectory("inflight").toFile()
    private val markers = InFlightMarkers(dir)

    @Test fun unfinishedReadsSurviveUntilCleared() {
        markers.begin("com.ok", 1); markers.end("com.ok")
        markers.begin("com.crashed", 7)
        markers.begin("../../escape", 1)
        dir.resolve("com.garbage").writeText("not a number")
        assertEquals(mapOf("com.crashed" to 7L), markers.unfinished())
        assertEquals("reading does not clear", mapOf("com.crashed" to 7L), markers.unfinished())
        markers.clear()
        assertTrue(markers.unfinished().isEmpty())
    }
}
