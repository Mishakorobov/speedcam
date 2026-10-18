package com.speedcam.core.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Guards the one place this project has to spell a constant twice.
 *
 * `CameraDatabaseInstaller` reads the database from a fixed path inside the APK, and
 * `app/build.gradle.kts` is what puts it there. A Gradle build script cannot import a
 * constant from a compiled Kotlin module, so the file name is written out in both places and
 * nothing else would notice when they drifted.
 *
 * If they drift, the build still succeeds and ships a working APK that contains no cameras —
 * `installIfNeeded` finds no asset, returns [DatabaseInstallOutcome.NO_ASSET], and the app
 * starts normally with an empty database and never warns about anything. That is the worst
 * failure this app can have, and it would be completely invisible, so the duplication is
 * pinned here instead.
 */
class BundledAssetNameTest {
    private val installerName = CameraDatabaseInstaller.ASSET_FILE_NAME

    /** Locates the repository root by walking up to the directory holding `settings.gradle.kts`. */
    private fun projectRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        throw AssertionError("could not locate the project root from ${System.getProperty("user.dir")}")
    }

    private fun appBuildScript(): String {
        val script = File(projectRoot(), "app/build.gradle.kts")
        assertTrue(script.isFile, "expected ${script.path} to exist")
        return script.readText()
    }

    @Test
    fun `the build script stages the file under the name the installer looks for`() {
        val script = appBuildScript()
        val stagedPath = """databases/$installerName"""

        assertTrue(
            script.contains("\"$stagedPath\""),
            "expected app/build.gradle.kts to stage the database at \"$stagedPath\", but it " +
                "does not, so the APK would ship with no cameras",
        )
    }

    @Test
    fun `the asset path the installer reads is the path the build script stages`() {
        // ASSET_PATH is "databases/<name>". Asserted rather than assumed, so that changing the
        // directory component alone also fails here.
        val expected = "databases/$installerName"
        assertEquals(expected, CameraDatabaseInstaller.ASSET_PATH)
    }

    @Test
    fun `the build script reads the database the importer produces`() {
        val script = appBuildScript()
        assertTrue(
            script.contains("data/russia.db"),
            "expected app/build.gradle.kts to read the database built by tools/import",
        )
    }
}
