package dev.lunacam.app.photon.raw

/** A busy camera, revocation or delayed surface must not permanently remove an optical lens. */
internal fun rawLensIsIncompatible(error: Exception): Boolean =
    error is IllegalStateException && error.message in setOf(
        "RAW session failed",
        "No bounded preview size",
        "Physical preview metadata missing"
    )
