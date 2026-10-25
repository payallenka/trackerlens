package app.trackerlens.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DexClassReaderTest {
    @Test fun readsDefinedClassNames() {
        val dex = fakeDex("Lcom/example/Main;", "Lcom/facebook/ads/AdView\$1;")
        assertEquals(listOf("com.example.Main", "com.facebook.ads.AdView\$1"), DexClassReader.definedClasses(dex))
    }

    @Test fun rejectsTruncatedAndForeignInput() {
        assertThrows(DexFormatException::class.java) { DexClassReader.definedClasses(ByteArray(10)) }
        val dex = fakeDex("La/B;")
        assertThrows(DexFormatException::class.java) { DexClassReader.definedClasses(dex.copyOf(0x75)) }
        dex[0] = 'x'.code.toByte()
        assertThrows(DexFormatException::class.java) { DexClassReader.definedClasses(dex) }
    }

    @Test fun rejectsBadIndexesAndOffsets() {
        assertThrows(DexFormatException::class.java) {
            DexClassReader.definedClasses(fakeDex(listOf("La/B;".toByteArray()), classDefTypes = listOf(5)))
        }
        val negativeStringOffset = fakeDex("La/B;").also { java.nio.ByteBuffer.wrap(it).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(0x70, -1) }
        assertThrows(DexFormatException::class.java) { DexClassReader.definedClasses(negativeStringOffset) }
    }

    @Test fun rejectsOverlongUleb128() {
        val dex = fakeDex("La/B;")
        val dataOff = java.nio.ByteBuffer.wrap(dex).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(0x70)
        for (i in 0 until 5) dex[dataOff + i] = 0x80.toByte()
        assertThrows(DexFormatException::class.java) { DexClassReader.definedClasses(dex) }
    }

    @Test fun skipsHostileDescriptorsWithoutFailing() {
        val huge = ("L" + "a/".repeat(5_000) + "B;").toByteArray()
        val nonAscii = "Lcom/exémple/A;".toByteArray()
        val noSemicolon = "Lcom/example/A".toByteArray()
        val primitive = "I".toByteArray()
        val dex = fakeDex(listOf(huge, nonAscii, noSemicolon, primitive, "Lok/Fine;".toByteArray()))
        assertEquals(listOf("ok.Fine"), DexClassReader.definedClasses(dex))
    }

    @Test fun manyTypesAliasingOneLongStringAreBoundedByFileSize() {
        // 200,000 distinct types all pointing at one ~1 KB name: decoding each would cost ~200 MB.
        val name = ("L" + "a/".repeat(500) + "B;").toByteArray()
        val n = 200_000
        val dex = fakeDex(listOf(name), classDefTypes = List(n) { it }, typeStrings = List(n) { 0 })
        val started = System.nanoTime()
        assertThrows(DexFormatException::class.java) { DexClassReader.forEachClassName(dex) { _, _ -> } }
        assertTrue("took too long", System.nanoTime() - started < 2_000_000_000L)
    }

    @Test fun failedDecodesAlsoCountAgainstTheBudget() {
        // Same attack with a name that fails late (non-ASCII just before the end) and is never delivered.
        val name = ("L" + "a/".repeat(500) + "\u00e9;").toByteArray()
        val n = 200_000
        val dex = fakeDex(listOf(name), classDefTypes = List(n) { it }, typeStrings = List(n) { 0 })
        assertThrows(DexFormatException::class.java) { DexClassReader.forEachClassName(dex) { _, _ -> } }
    }

    @Test fun decodesRepeatedTypeOnlyOnce() {
        // A hostile DEX pointing a million class_defs at one type must cost one decode, not a million.
        val dex = fakeDex(listOf("Lcom/facebook/ads/AdView;".toByteArray()), classDefTypes = List(1_000_000) { 0 })
        val started = System.nanoTime()
        assertEquals(listOf("com.facebook.ads.AdView"), DexClassReader.definedClasses(dex))
        assertTrue("took too long", System.nanoTime() - started < 2_000_000_000L)
    }
}
