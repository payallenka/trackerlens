package app.trackerlens.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerMatcherTest {
    private val matcher = TrackerMatcher(testCatalog)

    @Test fun matchesOnlyAtSegmentBoundaries() {
        val m = matcher.match(listOf("com.facebook.adsx.Thing", "com.google.firebase.analytics.FirebaseAnalytics"))
        assertEquals(setOf("fa"), m.keys)
        assertEquals("com.google.firebase.analytics.FirebaseAnalytics", m.getValue("fa").exampleClass)
    }

    @Test fun classSignaturesMatchInnerClassesButNotLookalikes() {
        assertEquals(setOf("cls"), matcher.match(listOf("com.example.sdk.Tracker\$Inner")).keys)
        assertEquals(setOf("cls"), matcher.match(listOf("com.example.sdk.Tracker")).keys)
        assertTrue(matcher.match(listOf("com.example.sdk.TrackerX")).isEmpty())
    }

    @Test fun evidenceIsBounded() {
        val long = "com.facebook.ads." + "x".repeat(1_000)
        assertEquals(200, matcher.match(listOf(long)).getValue("fb").exampleClass.length)
    }

    @Test fun deepNamesAreLinear() {
        val deep = List(200_000) { "a" }.joinToString(".")
        val started = System.nanoTime()
        assertTrue(matcher.match(listOf(deep)).isEmpty())
        assertTrue("took too long", System.nanoTime() - started < 1_000_000_000L)
    }
}
