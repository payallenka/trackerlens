package app.trackerlens.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class TrackerDef(
    val id: String,
    val name: String,
    val categories: List<String> = emptyList(),
    val website: String? = null,
    /** Java package or class names; a class matches when it equals or starts with one at a `.`/`$` boundary. */
    val codeSignatures: List<String>,
)

@Serializable
data class TrackerCatalog(
    val version: String,
    val source: String,
    val license: String,
    val trackers: List<TrackerDef>,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun parse(text: String): TrackerCatalog = json.decodeFromString(serializer(), text)
    }
}

/**
 * Matches class names against catalog signatures with a character trie: one walk per class name,
 * linear in its length, with no allocation. Signatures match only at `.`/`$` boundaries, so
 * `com.facebook.ads` matches `com.facebook.ads.AdView` but not `com.facebook.adsx.Thing`.
 */
class TrackerMatcher(catalog: TrackerCatalog) {
    private class Node {
        val next = HashMap<Char, Node>(4)
        var trackers: List<TrackerDef> = emptyList()
        var signature: String = ""
    }

    private val root = Node()

    init {
        for (t in catalog.trackers) for (sig in t.codeSignatures) {
            val key = sig.trim().trimEnd('.')
            if (key.isEmpty()) continue
            var node = root
            for (c in key) node = node.next.getOrPut(c) { Node() }
            node.trackers += t
            node.signature = key
        }
    }

    /** Returns tracker id -> the first matching class seen (kept as evidence for the UI). */
    fun match(classNames: Iterable<String>): Map<String, Match> {
        val found = LinkedHashMap<String, Match>()
        for (cls in classNames) matchInto(found, cls.toCharArray(), cls.length)
        return found
    }

    /** Matches one class name held in [chars]; a String is created only for a new match's evidence. */
    fun matchInto(found: MutableMap<String, Match>, chars: CharArray, length: Int) {
        var node = root
        for (i in 0..length) {
            if (i == length || chars[i] == '.' || chars[i] == '$') {
                for (t in node.trackers) {
                    if (t.id !in found) found[t.id] = Match(t, node.signature, String(chars, 0, minOf(length, MAX_EVIDENCE_CHARS)))
                }
            }
            if (i == length) break
            node = node.next[chars[i]] ?: break
        }
    }

    data class Match(val tracker: TrackerDef, val signature: String, val exampleClass: String)

    private companion object {
        /** Evidence is stored and displayed; keep it bounded whatever the input. */
        const val MAX_EVIDENCE_CHARS = 200
    }
}
