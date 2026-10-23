package dev.lunacam.app.photon.raw

/** Only metered request zero is a fallback; arrival order and image count are not identity. */
internal fun rawHdrBaselineTimestamp(
    resultIndices: Map<Long, Int>,
    imageTimestamps: Set<Long>
): Long? {
    val timestamp = resultIndices.filterValues { it == 0 }.keys.singleOrNull() ?: return null
    return timestamp.takeIf { it > 0 && it in imageTimestamps }
}
