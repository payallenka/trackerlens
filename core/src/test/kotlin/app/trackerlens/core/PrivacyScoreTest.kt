package app.trackerlens.core

import org.junit.Assert.assertEquals
import org.junit.Test

class PrivacyScoreTest {
    @Test fun scoreWeighsTrackersByCategoryAndIgnoresPermissions() {
        val ads = TrackerDef("a", "Ads", listOf("Advertisement", "Analytics"), codeSignatures = listOf("x"))
        val crash = TrackerDef("c", "Crash", listOf("Crash reporting"), codeSignatures = listOf("y"))
        assertEquals(100, PrivacyScore.of(emptyList()))
        assertEquals(Risk.None, PrivacyScore.risk(100, 0))
        assertEquals(85, PrivacyScore.of(listOf(ads)))
        assertEquals(Risk.Low, PrivacyScore.risk(97, 1))
        assertEquals(82, PrivacyScore.of(listOf(ads, crash)))
        assertEquals(Risk.Medium, PrivacyScore.risk(82, 2))
        assertEquals(0, PrivacyScore.of(List(10) { ads }))
        assertEquals(Risk.High, PrivacyScore.risk(0, 10))
    }
}
