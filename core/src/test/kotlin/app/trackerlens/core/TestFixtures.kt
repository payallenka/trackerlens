package app.trackerlens.core

import java.nio.ByteBuffer
import java.nio.ByteOrder

private fun uleb128(n: Int): ByteArray {
    val out = ArrayList<Byte>()
    var v = n
    do {
        var b = v and 0x7f
        v = v ushr 7
        if (v != 0) b = b or 0x80
        out += b.toByte()
    } while (v != 0)
    return out.toByteArray()
}

/**
 * Builds a minimal DEX containing only the tables DexClassReader reads. [classDefTypes] lists the
 * type index of each class_def (defaults to one per descriptor), so hostile layouts can be built.
 */
fun fakeDex(
    descriptors: List<ByteArray>,
    classDefTypes: List<Int> = descriptors.indices.toList(),
    typeStrings: List<Int> = descriptors.indices.toList(),
): ByteArray {
    val n = descriptors.size
    val t = typeStrings.size
    val stringIdsOff = 0x70
    val typeIdsOff = stringIdsOff + n * 4
    val classDefsOff = typeIdsOff + t * 4
    val dataOff = classDefsOff + classDefTypes.size * 32
    val data = descriptors.map { uleb128(it.size) + it + 0.toByte() }
    val buf = ByteBuffer.allocate(dataOff + data.sumOf { it.size }).order(ByteOrder.LITTLE_ENDIAN)
    buf.put("dex\n035\u0000".toByteArray())
    buf.putInt(0x38, n).putInt(0x3c, stringIdsOff).putInt(0x40, t).putInt(0x44, typeIdsOff)
    buf.putInt(0x60, classDefTypes.size).putInt(0x64, classDefsOff)
    var p = dataOff
    data.forEachIndexed { i, bytes ->
        buf.putInt(stringIdsOff + i * 4, p)
        buf.position(p); buf.put(bytes); p += bytes.size
    }
    typeStrings.forEachIndexed { i, s -> buf.putInt(typeIdsOff + i * 4, s) }
    classDefTypes.forEachIndexed { i, t -> buf.putInt(classDefsOff + i * 32, t) }
    return buf.array()
}

fun fakeDex(vararg descriptors: String) = fakeDex(descriptors.map { it.toByteArray() })

val testCatalog = TrackerCatalog(
    version = "test", source = "test", license = "test",
    trackers = listOf(
        TrackerDef("fb", "Facebook Ads", listOf("Advertisement"), codeSignatures = listOf("com.facebook.ads.")),
        TrackerDef("fa", "Firebase Analytics", listOf("Analytics"), codeSignatures = listOf("com.google.firebase.analytics", "com.google.android.gms.measurement.")),
        TrackerDef("cls", "Class signature", listOf("Analytics"), codeSignatures = listOf("com.example.sdk.Tracker")),
    ),
)

fun snapshot(
    pkg: String = "p", versionCode: Long = 1, updated: Long = 0, trackers: Set<String> = emptySet(),
    permissions: Set<String> = emptySet(), complete: Boolean = true, shownByDefault: Boolean = true, scanKey: String = "k",
) = AppSnapshot(pkg, pkg, "v$versionCode", versionCode, updated, trackers, permissions, complete, shownByDefault = shownByDefault, scanKey = scanKey)

fun installed(pkg: String = "p", versionCode: Long = 1, updated: Long = 0, system: Boolean = false, shown: Boolean = !system) =
    InstalledApp(pkg, versionCode, updated, system, shown)
