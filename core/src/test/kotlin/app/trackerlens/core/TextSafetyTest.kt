package app.trackerlens.core

import org.junit.Assert.assertEquals
import org.junit.Test

class TextSafetyTest {
    @Test fun removesInjectedLinesAndBidiControls() {
        assertEquals("Camera+ Tracker: none", TextSafety.clean("Camera\n+ Tracker: none", 100))
        assertEquals("evil", TextSafety.clean("‮evil ", 100))
    }

    @Test fun truncatesOnCodePointsAndFallsBack() {
        assertEquals("ab😀", TextSafety.clean("ab😀😀", 3))
        assertEquals("Unnamed", TextSafety.clean("\u0000\n", 10, "Unnamed"))
    }
}
