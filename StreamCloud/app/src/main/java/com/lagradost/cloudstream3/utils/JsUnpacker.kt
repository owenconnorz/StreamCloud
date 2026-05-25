package com.lagradost.cloudstream3.utils

/**
 * P,A,C,K,E,D JavaScript unpacker.
 * Handles the eval(function(p,a,c,k,e,d){...}) obfuscation format used by
 * many video hosting sites (FileMoon, StreamWish, MixDrop, Mp4Upload, etc.).
 */
class JsUnpacker(private val packedJS: String?) {

    fun detect(): Boolean {
        val script = packedJS ?: return false
        return packedRegex.containsMatchIn(script)
    }

    fun unpack(): String? {
        val script = packedJS ?: return null
        val match = packedRegex.find(script) ?: return null
        return try {
            val packed = match.groupValues[1]
            val base   = match.groupValues[2].toIntOrNull() ?: return null
            val count  = match.groupValues[3].toIntOrNull() ?: return null
            val keys   = match.groupValues[4].split('|')
            if (keys.size != count) return null
            unpackPayload(packed, base, keys)
        } catch (_: Throwable) { null }
    }

    private fun unpackPayload(packed: String, base: Int, keys: List<String>): String {
        // Replace each \b word \b with its key (if the key is non-empty)
        return wordRegex.replace(packed) { m ->
            val word = m.value
            val idx  = decodeBase(word, base)
            if (idx in keys.indices && keys[idx].isNotEmpty()) keys[idx] else word
        }
    }

    /**
     * Decode a base-N encoded number from a string.
     * Alphabet: 0-9 a-z A-Z for bases > 36 (base62).
     */
    private fun decodeBase(str: String, base: Int): Int {
        var result = 0
        for (ch in str) {
            val digit = when {
                ch in '0'..'9' -> ch - '0'
                ch in 'a'..'z' -> ch - 'a' + 10
                ch in 'A'..'Z' -> ch - 'A' + 36
                else -> return -1
            }
            if (digit >= base) return -1
            result = result * base + digit
        }
        return result
    }

    companion object {
        // Matches: eval(function(p,a,c,k,e,...){...}('payload',base,count,'keys',...))
        private val packedRegex = Regex(
            """eval\s*\(\s*function\s*\(\s*p\s*,\s*a\s*,\s*c\s*,\s*k\s*,\s*e\s*,[\s\S]*?\)\s*""" +
            """\(\s*'([\s\S]*?)'\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*'([\s\S]*?)'[\s\S]*?\)\s*\)""",
            setOf(RegexOption.MULTILINE)
        )
        private val wordRegex = Regex("""\b\w+\b""")
    }
}
