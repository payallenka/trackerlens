package app.trackerlens.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.BitSet
import kotlin.math.abs

class DexFormatException(message: String) : Exception(message)

/**
 * Reads the names of classes *defined* in one DEX file (class_defs), as dotted Java names.
 *
 * Only the header, type_ids, string_ids and the referenced string data are touched, so this
 * is cheap enough to run over every installed app. The input is an untrusted third-party file,
 * so every offset is bounds-checked, each type is decoded at most once, and descriptor length is
 * capped: a hostile app must not be able to exhaust memory or CPU and block the scan.
 */
object DexClassReader {
    private const val HEADER_SIZE = 0x70
    private const val CLASS_DEF_SIZE = 32

    /** Real class descriptors are well under 300 bytes; anything longer is not a genuine SDK class. */
    const val MAX_DESCRIPTOR_BYTES = 1024

    /** Convenience for tests and small inputs: collects every defined class name. */
    fun definedClasses(dex: ByteArray): List<String> {
        val out = ArrayList<String>()
        forEachClassName(dex) { chars, len -> out += String(chars, 0, len) }
        return out
    }

    /**
     * Streams each defined class name as dotted characters in a reused buffer; nothing is retained,
     * so memory stays constant however many classes the file declares. Total decoding work is capped
     * at the file's own size, which also covers many entries aliasing one long string. Returns the
     * number of names delivered.
     */
    fun forEachClassName(dex: ByteArray, visit: (chars: CharArray, length: Int) -> Unit): Int {
        if (dex.size < HEADER_SIZE) throw DexFormatException("file shorter than DEX header")
        if (dex[0] != 'd'.code.toByte() || dex[1] != 'e'.code.toByte() || dex[2] != 'x'.code.toByte() || dex[3] != '\n'.code.toByte()) {
            throw DexFormatException("missing dex magic")
        }
        val buf = ByteBuffer.wrap(dex).order(ByteOrder.LITTLE_ENDIAN)
        val stringIdsSize = buf.getInt(0x38).toLong() and 0xffffffffL
        val stringIdsOff = buf.getInt(0x3c)
        val typeIdsSize = buf.getInt(0x40).toLong() and 0xffffffffL
        val typeIdsOff = buf.getInt(0x44)
        val classDefsSize = buf.getInt(0x60).toLong() and 0xffffffffL
        val classDefsOff = buf.getInt(0x64)

        checkTable(dex, stringIdsOff, stringIdsSize, 4, "string_ids")
        checkTable(dex, typeIdsOff, typeIdsSize, 4, "type_ids")
        checkTable(dex, classDefsOff, classDefsSize, CLASS_DEF_SIZE, "class_defs")

        // A valid DEX defines each type at most once; seen-tracking stops repeated entries costing anything.
        val seen = BitSet(typeIdsSize.toInt())
        val chars = CharArray(MAX_DESCRIPTOR_BYTES)
        var budget = dex.size.toLong()
        var delivered = 0
        for (i in 0 until classDefsSize.toInt()) {
            val typeIdx = buf.getInt(classDefsOff + i * CLASS_DEF_SIZE).toLong() and 0xffffffffL
            if (typeIdx >= typeIdsSize) throw DexFormatException("class_def $i has bad type index")
            if (seen.get(typeIdx.toInt())) continue
            seen.set(typeIdx.toInt())
            val stringIdx = buf.getInt(typeIdsOff + typeIdx.toInt() * 4).toLong() and 0xffffffffL
            if (stringIdx >= stringIdsSize) throw DexFormatException("type $typeIdx has bad string index")
            val len = readClassName(dex, buf.getInt(stringIdsOff + stringIdx.toInt() * 4), chars)
            // Charge every byte examined, including failed decodes, so aliased strings can't multiply the work.
            budget -= maxOf(abs(len), 1)
            if (budget < 0) throw DexFormatException("string data decoded beyond file size")
            if (len > 0) {
                visit(chars, len)
                delivered++
            }
        }
        return delivered
    }

    private fun checkTable(dex: ByteArray, off: Int, count: Long, entrySize: Int, name: String) {
        if (count == 0L) return
        val end = (off.toLong() and 0xffffffffL) + count * entrySize
        if (off < 0 || end > dex.size) throw DexFormatException("$name table out of bounds")
    }

    /**
     * Decodes a `Lcom/foo/Bar;` descriptor (string_data_item: uleb128 length, then MUTF-8) straight
     * to `com.foo.Bar` in [chars] and returns its length. For anything that is not a plain ASCII
     * class descriptor within [MAX_DESCRIPTOR_BYTES] (such entries can't match a signature) it
     * returns minus the number of bytes examined, so the caller can charge the work.
     */
    private fun readClassName(dex: ByteArray, off: Int, chars: CharArray): Int {
        if (off < 0 || off >= dex.size) throw DexFormatException("string data out of bounds")
        var p = off
        var lebBytes = 0
        while (p < dex.size && dex[p].toInt() and 0x80 != 0) {
            p++
            if (++lebBytes > 4) throw DexFormatException("bad uleb128 length")
        }
        p++
        if (p >= dex.size || dex[p] != 'L'.code.toByte()) return -(p - off + 1)
        val start = p + 1
        val limit = minOf(dex.size, start + MAX_DESCRIPTOR_BYTES)
        var n = 0
        var q = start
        while (q < limit) {
            val b = dex[q].toInt()
            when {
                b == ';'.code -> return if (n == 0 || q + 1 >= dex.size || dex[q + 1] != 0.toByte()) -(q - off + 1) else n
                b <= 0x20 || b >= 0x7f -> return -(q - off + 1) // NUL, control or non-ASCII: not a descriptor we can match
                b == '/'.code -> chars[n++] = '.'
                else -> chars[n++] = b.toChar()
            }
            q++
        }
        return -(q - off + 1) // unterminated or longer than the cap
    }
}
