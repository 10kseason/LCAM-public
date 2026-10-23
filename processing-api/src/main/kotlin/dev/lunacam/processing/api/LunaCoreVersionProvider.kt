package dev.lunacam.processing.api

interface LunaCoreVersionProvider {
    fun version(): String
}

fun formatLunaCoreVersion(version: String): String {
    require(version.isNotBlank()) { "LunaCore version must not be blank" }
    return "LunaCore $version"
}
