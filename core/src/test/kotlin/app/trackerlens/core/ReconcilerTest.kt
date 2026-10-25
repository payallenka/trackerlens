package app.trackerlens.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconcilerTest {
    @Test fun needsScanRules() {
        val app = installed(versionCode = 1, updated = 10)
        val done = snapshot(versionCode = 1, updated = 10)
        assertTrue(Reconciler.needsScan(null, app, "k"))
        assertFalse(Reconciler.needsScan(done, app, "k"))
        assertTrue("catalog/scanner changed", Reconciler.needsScan(done, app, "k2"))
        assertTrue("temporary failure is retried", Reconciler.needsScan(done.copy(scanComplete = false), app, "k"))
        assertFalse("permanent failure is not retried", Reconciler.needsScan(done.copy(scanComplete = false, retryable = false), app, "k"))
        assertTrue("updated", Reconciler.needsScan(done, installed(versionCode = 2, updated = 20), "k"))
        assertTrue("reinstalled same version", Reconciler.needsScan(done, installed(versionCode = 1, updated = 11), "k"))
    }

    @Test fun changeOnlyWhenTheAppItselfUpdated() {
        val old = snapshot(versionCode = 1, trackers = setOf("a"), permissions = setOf("CAMERA"))
        val sameApp = installed(versionCode = 1)
        assertNull(Reconciler.change(old, old.copy(trackerIds = setOf("a", "b"), scanKey = "k2"), sameApp, now = 5))
        assertNull(Reconciler.change(null, old, sameApp, 5))

        val upd = installed(versionCode = 2)
        val new = snapshot(versionCode = 2, trackers = setOf("a", "b"), permissions = setOf("CAMERA", "LOCATION"))
        val c = Reconciler.change(old, new, upd, 5)!!
        assertEquals(setOf("b"), c.addedTrackers)
        assertEquals(setOf("LOCATION"), c.addedPermissions)
        assertTrue(c.isWorse)
    }

    @Test fun trackerChangesNeedSameCatalogAndCompleteScans() {
        val old = snapshot(versionCode = 1, trackers = setOf("a"), permissions = setOf("CAMERA"))
        val new = snapshot(versionCode = 2, trackers = setOf("a", "b"), permissions = setOf("CAMERA", "LOCATION"))
        val upd = installed(versionCode = 2)
        val afterCatalogUpdate = Reconciler.change(old, new.copy(scanKey = "k2"), upd, now = 5)!!
        assertTrue(afterCatalogUpdate.addedTrackers.isEmpty())
        assertEquals(setOf("LOCATION"), afterCatalogUpdate.addedPermissions)
        val partial = Reconciler.change(old, new.copy(scanComplete = false, trackerIds = emptySet()), upd, 5)!!
        assertTrue(partial.removedTrackers.isEmpty())
        assertNull(Reconciler.change(old, new.copy(permissions = old.permissions, scanKey = "k2"), upd, now = 5))
    }

    @Test fun mergeKeepsHiddenAppsDropsUninstalledOnesAndRefreshesFlags() {
        val previous = StoredState(
            scanKey = "k",
            apps = mapOf(
                "user" to snapshot("user"),
                "sys" to snapshot("sys", shownByDefault = false),
                "chrome" to snapshot("chrome", shownByDefault = true),
                "gone" to snapshot("gone"),
            ),
            changes = listOf(AppChange(1, "sys"), AppChange(2, "gone")),
        )
        // Chrome's updates were uninstalled: it is now a plain system app, hidden by default.
        val installedNow = listOf(installed("user"), installed("sys", system = true), installed("chrome", system = true, shown = false))
        val fresh = mapOf("user" to snapshot("user", trackers = setOf("a")))
        val next = Reconciler.merge(previous, installedNow, fresh, listOf(AppChange(3, "user")), "k", now = 9)
        assertEquals(setOf("user", "sys", "chrome"), next.apps.keys)
        assertEquals(setOf("a"), next.apps.getValue("user").trackerIds)
        assertFalse("scope refreshed for apps outside the view", next.apps.getValue("chrome").shownByDefault)
        assertTrue(next.apps.getValue("chrome").isSystem)
        assertEquals(listOf("sys", "user"), next.changes.map { it.packageName })
        assertEquals(9, next.lastScanAt)
    }

    @Test fun mergeKeepsSnapshotsFromAnOldCatalogAndBoundsHistory() {
        // An interrupted scan after a catalog update must not lose apps it hasn't re-read yet.
        val previous = StoredState(scanKey = "old", apps = mapOf("a" to snapshot("a", scanKey = "old")), changes = List(600) { AppChange(it.toLong(), "a") })
        val next = Reconciler.merge(previous, listOf(installed("a")), emptyMap(), emptyList(), "new", 1)
        assertEquals("old", next.apps.getValue("a").scanKey)
        assertTrue("and it will be re-read", Reconciler.needsScan(next.apps.getValue("a"), installed("a"), "new"))
        assertEquals(StoredState.MAX_CHANGES, next.changes.size)
        assertEquals(599L, next.changes.last().detectedAt)
    }

    @Test fun quarantineNeedsTwoUnfinishedReadsOfTheSameVersion() {
        val app = installed("bad", versionCode = 7)
        var s = Reconciler.recordUnfinishedReads(StoredState(), mapOf("bad" to 7L))
        assertFalse("one interrupted read may be an ordinary process kill", Reconciler.isQuarantined(s, app, ""))
        s = Reconciler.recordUnfinishedReads(s, mapOf("bad" to 7L))
        assertTrue(Reconciler.isQuarantined(s, app, ""))
        assertFalse("a new version is read again", Reconciler.isQuarantined(s, installed("bad", versionCode = 8), ""))
        val restarted = Reconciler.recordUnfinishedReads(Reconciler.recordUnfinishedReads(StoredState(), mapOf("bad" to 7L)), mapOf("bad" to 8L))
        assertEquals("strikes reset on a new version", 1, restarted.crashes.getValue("bad").strikes)
    }

    @Test fun mergeClearsOneStrikeAfterACleanReadButKeepsQuarantine() {
        val oneStrike = Reconciler.recordUnfinishedReads(StoredState(scanKey = "k"), mapOf("a" to 1L, "b" to 1L))
        val twoStrikes = Reconciler.recordUnfinishedReads(oneStrike, mapOf("b" to 1L))
        val fresh = mapOf("a" to snapshot("a"), "b" to snapshot("b", complete = false))
        val next = Reconciler.merge(twoStrikes, listOf(installed("a"), installed("b")), fresh, emptyList(), "k", 1)
        assertEquals(setOf("b"), next.crashes.keys)
        val afterUpdate = Reconciler.merge(next, listOf(installed("a"), installed("b", versionCode = 2)), emptyMap(), emptyList(), "k", 2)
        assertTrue("quarantine ends when the app updates", afterUpdate.crashes.isEmpty())
    }

    @Test fun unseenAlertsAndNotificationRules() {
        val changes = listOf(
            AppChange(10, "a", addedTrackers = setOf("t")),
            AppChange(20, "b", removedTrackers = setOf("t")),
            AppChange(30, "c", addedPermissions = setOf("CAMERA")),
        )
        val unseen = Alerts.unseen(changes, seenUntil = 10)
        assertEquals("only worse changes after the seen mark", listOf("c"), unseen.map { it.packageName })
        assertTrue(Alerts.shouldNotify(unseen, notifiedUntil = 10))
        assertFalse(Alerts.shouldNotify(unseen, notifiedUntil = 30))
        assertFalse(Alerts.shouldNotify(emptyList(), notifiedUntil = 0))
    }

    @Test fun oneStrikeMakesASuspectAndReinstallResetsIt() {
        val app = installed("x", versionCode = 3, updated = 100)
        val s = Reconciler.recordUnfinishedReads(StoredState(), mapOf("x" to 3L), mapOf("x" to 100L))
        assertTrue(Reconciler.isSuspect(s, app, ""))
        assertFalse(Reconciler.isQuarantined(s, app, ""))
        val q = Reconciler.recordUnfinishedReads(s, mapOf("x" to 3L), mapOf("x" to 100L))
        assertTrue(Reconciler.isQuarantined(q, app, ""))
        assertFalse(Reconciler.isSuspect(q, app, ""))
        val reinstalled = Reconciler.merge(q, listOf(installed("x", versionCode = 3, updated = 200)), emptyMap(), emptyList(), "", 1)
        assertTrue("reinstalling the same version clears the quarantine", reinstalled.crashes.isEmpty())
        val keyed = Reconciler.recordUnfinishedReads(Reconciler.recordUnfinishedReads(StoredState(), mapOf("x" to 3L), mapOf("x" to 100L), "s1"), mapOf("x" to 3L), mapOf("x" to 100L), "s1")
        assertTrue(Reconciler.isQuarantined(keyed, app, "s1"))
        val newScanner = Reconciler.merge(keyed, listOf(app), emptyMap(), emptyList(), "s2", 1)
        assertTrue("a scanner change lifts the quarantine", newScanner.crashes.isEmpty())
    }

    @Test fun quarantineLiftsEndToEndAfterReinstallOrScannerChange() {
        // The sequence the scan loop actually runs: quarantined, then the user reinstalls (or the scanner changes).
        val app = installed("x", versionCode = 3, updated = 100)
        var s = StoredState()
        repeat(2) { s = Reconciler.recordUnfinishedReads(s, mapOf("x" to 3L), mapOf("x" to 100L), "s1") }
        assertTrue(Reconciler.isQuarantined(s, app, "s1"))
        val skipped = snapshot("x", versionCode = 3, updated = 100, complete = false, scanKey = "s1")
        s = Reconciler.merge(s.copy(apps = mapOf("x" to skipped)), listOf(app), mapOf("x" to skipped), emptyList(), "s1", 1)

        val reinstalled = installed("x", versionCode = 3, updated = 200)
        assertFalse("reinstall lifts the quarantine", Reconciler.isQuarantined(s, reinstalled, "s1"))
        assertTrue("and the app is read again", Reconciler.needsScan(s.apps.getValue("x"), reinstalled, "s1"))

        assertFalse("a scanner change lifts it too", Reconciler.isQuarantined(s, app, "s2"))
        assertTrue(Reconciler.needsScan(s.apps.getValue("x"), app, "s2"))
    }
}
