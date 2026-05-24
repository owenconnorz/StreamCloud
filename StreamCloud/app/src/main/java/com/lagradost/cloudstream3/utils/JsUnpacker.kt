package com.lagradost.cloudstream3.utils

/**
 * Stub for P,A,C,K,E,D JavaScript unpacker.
 * Only needs to exist for binary compatibility — real unpacking is not needed in stubs.
 */
class JsUnpacker(private val packedJS: String?) {
    fun unpack(): String? {
        // Minimal stub: just return null so callers fall back to the raw string.
        return null
    }

    fun detect(): Boolean = packedJS?.startsWith("eval(") == true
}
