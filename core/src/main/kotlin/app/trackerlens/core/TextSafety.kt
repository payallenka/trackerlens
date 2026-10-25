package app.trackerlens.core

/** Text that comes from other apps (labels) and is stored or displayed, including in notifications. */
object TextSafety {
    /**
     * Removes control, formatting (e.g. bidi overrides) and line/paragraph separator characters, which
     * could fake extra lines or reorder text, and truncates on code-point boundaries.
     */
    fun clean(raw: String, maxCodePoints: Int, fallback: String = ""): String {
        val out = StringBuilder()
        var count = 0
        var i = 0
        while (i < raw.length && count < maxCodePoints) {
            val cp = raw.codePointAt(i)
            when (Character.getType(cp)) {
                Character.CONTROL.toInt(), Character.FORMAT.toInt(),
                Character.LINE_SEPARATOR.toInt(), Character.PARAGRAPH_SEPARATOR.toInt(),
                -> Unit
                else -> { out.appendCodePoint(cp); count++ }
            }
            i += Character.charCount(cp)
        }
        return out.toString().trim().ifEmpty { fallback }
    }
}
