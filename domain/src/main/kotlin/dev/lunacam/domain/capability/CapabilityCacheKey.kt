package dev.lunacam.domain.capability

import java.security.MessageDigest

object CapabilityCacheKey {
    fun create(schemaVersion: Int, sdkInt: Int, buildFingerprint: String): String =
        sha256("$schemaVersion\n$sdkInt\n$buildFingerprint")

    fun fingerprintHash(buildFingerprint: String): String = sha256(buildFingerprint).take(16)

    private fun sha256(value: String): String = MessageDigest
        .getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
}
