package com.speedcam.core.data

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Tests the first-run installation path against the real database.
 *
 * The interesting failures here are all about a half-written or missing file rather than bad
 * logic, and none of them would throw at the call site: an app whose camera database silently
 * failed to install starts normally and never warns. These tests use the actual
 * `data/russia.db` that `tools/import` produced, because the point is to prove that the real
 * file survives the copy, not that a synthetic stand-in does.
 */
class CameraDatabaseInstallerTest {
    @TempDir
    lateinit var workDir: File

    private lateinit var parentDir: File

    private lateinit var target: File

    @BeforeEach
    fun setUp() {
        parentDir = File(workDir, "databases")
        target = File(parentDir, CameraDatabaseInstaller.ASSET_FILE_NAME)
    }

    @AfterEach
    fun tearDown() {
        // Nothing to clean: @TempDir removes the tree.
    }

    /**
     * The real imported database, locating it by walking up to the project root.
     *
     * Skips the calling test when the database has not been built, because it is gitignored
     * and absent on a fresh clone.
     */
    private fun realDatabase(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "data/russia.db")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        assumeTrue(false, "data/russia.db is not present; run tools/import/update-osm.bat")
        throw AssertionError("assumeTrue should have aborted the test")
    }

    private fun installerFrom(source: File?) = CameraDatabaseInstaller(target) { source?.inputStream() }

    // ---------------------------------------------------------- header detection

    @Test
    fun `the real database is recognised as a SQLite file`() {
        val real = realDatabase()
        assertTrue(
            CameraDatabaseInstaller.looksLikeSqliteDatabase(real),
            "the imported database must have a SQLite header",
        )
    }

    @Test
    fun `an absent file is not a database`() {
        assertFalse(CameraDatabaseInstaller.looksLikeSqliteDatabase(File(workDir, "absent.db")))
    }

    @Test
    fun `an empty file is not a database`() {
        val empty = File(workDir, "empty.db")
        empty.createNewFile()
        assertFalse(CameraDatabaseInstaller.looksLikeSqliteDatabase(empty))
    }

    @Test
    fun `a truncated file is not a database`() {
        val real = realDatabase()
        val truncated = File(workDir, "truncated.db")
        real.inputStream().use { input ->
            FileOutputStream(truncated).use { out ->
                val head = ByteArray(8)
                input.read(head)
                out.write(head)
            }
        }
        assertFalse(
            CameraDatabaseInstaller.looksLikeSqliteDatabase(truncated),
            "a file too short to hold a header must be rejected",
        )
    }

    @Test
    fun `a file that is not SQLite at all is rejected`() {
        val junk = File(workDir, "junk.db")
        junk.writeText("this is not a database, it is just some text that is long enough")
        assertFalse(CameraDatabaseInstaller.looksLikeSqliteDatabase(junk))
    }

    // --------------------------------------------------------------- installation

    @Test
    fun `installing the real database writes it into place`() {
        val real = realDatabase()

        val outcome = installerFrom(real).installIfNeeded()
        assertEquals(DatabaseInstallOutcome.INSTALLED, outcome)

        assertTrue(target.isFile, "the database should have been created at $target")
        assertEquals(real.length(), target.length(), "the installed copy differs in size from the source")
        assertTrue(CameraDatabaseInstaller.looksLikeSqliteDatabase(target))
    }

    @Test
    fun `installing twice is a no-op the second time`() {
        val real = realDatabase()
        installerFrom(real).installIfNeeded()

        // Write a sentinel so a second, clobbering install would be visible.
        FileOutputStream(target, true).use { it.write("SENTINEL".toByteArray()) }
        val expectedLength = target.length()

        val outcome = installerFrom(real).installIfNeeded()
        assertEquals(DatabaseInstallOutcome.ALREADY_PRESENT, outcome)
        assertEquals(
            expectedLength,
            target.length(),
            "an existing database must never be overwritten; it holds the user's override_ rows",
        )
    }

    @Test
    fun `a corrupt existing file is replaced`() {
        val real = realDatabase()

        parentDir.mkdirs()
        target.writeText("corrupt")

        val outcome = installerFrom(real).installIfNeeded()
        assertEquals(DatabaseInstallOutcome.REPAIRED, outcome)
        assertTrue(CameraDatabaseInstaller.looksLikeSqliteDatabase(target))
        assertEquals(real.length(), target.length())
    }

    @Test
    fun `an empty existing file is replaced`() {
        val real = realDatabase()

        parentDir.mkdirs()
        target.createNewFile()

        assertEquals(DatabaseInstallOutcome.REPAIRED, installerFrom(real).installIfNeeded())
        assertTrue(CameraDatabaseInstaller.looksLikeSqliteDatabase(target))
    }

    @Test
    fun `a missing asset leaves the app with no database rather than a broken one`() {
        val outcome = installerFrom(null).installIfNeeded()
        assertEquals(DatabaseInstallOutcome.NO_ASSET, outcome)
        assertFalse(target.exists(), "nothing should have been written when there is no asset")
    }

    @Test
    fun `a bundle that is not a database is rejected and leaves nothing behind`() {
        val junk = File(workDir, "junk-source.db")
        junk.writeText("not a database at all, but long enough to pass a size check")

        val failure = runCatching { installerFrom(junk).installIfNeeded() }.exceptionOrNull()
        assertTrue(failure is IOException, "expected an IOException but got $failure")

        assertFalse(target.exists(), "a rejected bundle must not be installed")
        assertNoLeftovers()
    }

    @Test
    fun `a truncated bundle is rejected before it can replace a good database`() {
        val real = realDatabase()
        installerFrom(real).installIfNeeded()
        val goodLength = target.length()

        val truncated = File(workDir, "short.db")
        real.inputStream().use { input ->
            FileOutputStream(truncated).use { out ->
                val head = ByteArray(4)
                input.read(head)
                out.write(head)
            }
        }

        // The good database is still valid, so installation short-circuits and the bad bundle
        // is never opened at all. This is the outcome that matters: a bad bundle must never
        // destroy a working install.
        assertEquals(DatabaseInstallOutcome.ALREADY_PRESENT, installerFrom(truncated).installIfNeeded())
        assertEquals(goodLength, target.length())
        assertTrue(CameraDatabaseInstaller.looksLikeSqliteDatabase(target))
    }

    @Test
    fun `no staging or superseded files are left behind`() {
        val real = realDatabase()

        installerFrom(real).installIfNeeded()
        assertNoLeftovers()

        // And again through the repair path, which is the one that moves a file aside.
        target.writeText("corrupt")
        installerFrom(real).installIfNeeded()
        assertNoLeftovers()
    }

    @Test
    fun `the database directory is created when it does not exist`() {
        val real = realDatabase()
        assertFalse(parentDir.exists())

        assertEquals(DatabaseInstallOutcome.INSTALLED, installerFrom(real).installIfNeeded())
        assertTrue(parentDir.isDirectory)
    }

    private fun assertNoLeftovers() {
        val leftovers =
            parentDir.listFiles()?.filter { it.name.endsWith(".install") || it.name.endsWith(".superseded") }
        assertTrue(
            leftovers.isNullOrEmpty(),
            "installation left temporary files behind: ${leftovers?.joinToString { it.name }}",
        )
    }

    @Test
    fun `the asset path is the file name inside a databases directory`() {
        val expected = "databases/${CameraDatabaseInstaller.ASSET_FILE_NAME}"
        assertEquals(expected, CameraDatabaseInstaller.ASSET_PATH)
        assertNotNull(CameraDatabaseInstaller.ASSET_FILE_NAME)
    }
}
