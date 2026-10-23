import org.gradle.api.tasks.Exec

plugins {
    base
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.spotless)
}

spotless {
    kotlin {
        target(
            "app/src/**/*.kt",
            "camera-camera2/src/**/*.kt",
            "domain/src/**/*.kt",
            "feature-camera/src/**/*.kt",
            "feature-diagnostics/src/**/*.kt",
            "processing-api/src/**/*.kt",
            "processing-native/src/**/*.kt"
        )
        ktlint(libs.versions.ktlint.get())
    }
    kotlinGradle {
        target(
            "*.gradle.kts",
            "app/*.gradle.kts",
            "camera-camera2/*.gradle.kts",
            "domain/*.gradle.kts",
            "feature-camera/*.gradle.kts",
            "feature-diagnostics/*.gradle.kts",
            "processing-api/*.gradle.kts",
            "processing-native/*.gradle.kts"
        )
        ktlint(libs.versions.ktlint.get())
    }
}

val nativeSourceDirectory = layout.projectDirectory.dir("native/lunacore")
val nativeBuildDirectory = layout.buildDirectory.dir("native-host")
val isWindowsHost = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

val configureNativeSmoke by tasks.registering(Exec::class) {
    group = "verification"
    description = "Configures the host LunaCore smoke test."
    val generatorArguments =
        if (isWindowsHost) {
            listOf("-G", "Visual Studio 17 2022", "-A", "x64")
        } else {
            listOf("-G", "Ninja")
        }
    commandLine(
        listOf(
            "cmake",
            "-S",
            nativeSourceDirectory.asFile.absolutePath,
            "-B",
            nativeBuildDirectory.get().asFile.absolutePath
        ) +
            generatorArguments +
            listOf(
                "-DCMAKE_BUILD_TYPE=Release",
                "-DLUNACORE_BUILD_HOST_TESTS=ON"
            )
    )
}

val buildNativeSmoke by tasks.registering(Exec::class) {
    group = "verification"
    description = "Builds the host LunaCore smoke test."
    dependsOn(configureNativeSmoke)
    commandLine(
        "cmake",
        "--build",
        nativeBuildDirectory.get().asFile.absolutePath,
        "--config",
        "Release"
    )
}

val nativeSmokeTest by tasks.registering(Exec::class) {
    group = "verification"
    description = "Runs the host LunaCore version smoke test."
    dependsOn(buildNativeSmoke)
    commandLine(
        "ctest",
        "--test-dir",
        nativeBuildDirectory.get().asFile.absolutePath,
        "--build-config",
        "Release",
        "--output-on-failure"
    )
}

tasks.register("p0JvmTest") {
    group = "verification"
    description = "Runs the P0 JVM contract tests."
    dependsOn(":processing-api:test")
}

tasks.register("p1JvmTest") {
    group = "verification"
    description = "Runs the P1 domain and permission-flow JVM tests."
    dependsOn(":domain:test", ":feature-diagnostics:test")
}

tasks.register("p0Check") {
    group = "verification"
    description = "Runs the complete local P0 quality gate except Android assembly."
    dependsOn(
        "spotlessCheck",
        ":app:lintDebug",
        ":app:lintRelease",
        ":processing-api:test",
        nativeSmokeTest
    )
}

tasks.register("p2JvmTest") {
    group = "verification"
    description = "Runs the P2 preview-lifecycle JVM tests."
    dependsOn(":domain:test", ":feature-camera:testDebugUnitTest")
}

tasks.register("p2Check") {
    group = "verification"
    description = "Runs the complete local P2 quality gate except Android assembly."
    dependsOn(
        "spotlessCheck",
        ":app:lintDebug",
        ":app:lintRelease",
        ":domain:test",
        ":feature-camera:testDebugUnitTest",
        ":feature-diagnostics:test",
        ":processing-api:test",
        nativeSmokeTest
    )
}

tasks.register("p1Check") {
    group = "verification"
    description = "Runs the complete local P1 quality gate except Android assembly."
    dependsOn(
        "spotlessCheck",
        ":app:lintDebug",
        ":app:lintRelease",
        ":domain:test",
        ":feature-diagnostics:test",
        ":processing-api:test",
        nativeSmokeTest
    )
}
