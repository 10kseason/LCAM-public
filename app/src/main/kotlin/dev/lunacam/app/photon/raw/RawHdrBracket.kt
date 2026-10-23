package dev.lunacam.app.photon.raw

/** Baseline first for a usable fallback. Vary shutter only: extra ISO is not extra light. */
internal fun rawHdrBracket(referenceNs: Long, minimumNs: Long, maximumNs: Long): List<Long>? {
    if (minimumNs <= 0 || maximumNs < minimumNs || referenceNs !in minimumNs..maximumNs) return null
    val short = (referenceNs / 4).coerceAtLeast(minimumNs)
    val long = (referenceNs.coerceAtMost(Long.MAX_VALUE / 4) * 4)
        .coerceAtMost(minOf(maximumNs, 80_000_000L))
    if (referenceNs.toDouble() / short < 1.8 || long.toDouble() / referenceNs < 1.8) return null
    return listOf(referenceNs, short, long)
}
