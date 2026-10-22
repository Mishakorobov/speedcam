package com.speedcam.core.data

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

/** What [CameraDatabaseInstaller.installIfNeeded] did. */
enum class DatabaseInstallOutcome {
    /** The database was not there and has now been written. */
    INSTALLED,

    /** A usable database was already present; nothing was touched. */
    ALREADY_PRESENT,

    /** Something was at the target path but it was not a readable database, so it was replaced. */
    REPAIRED,

    /** The APK carries no bundled database. The app starts with an empty one. */
    NO_ASSET,
}

/**
 * Puts the camera database that ships inside the APK onto the device.
 *
 * The original application solves the same problem the same way: `assets/overview/map.dat`
 * (3.46 MB) is copied out of the APK into app storage on first run by `SystemHelper.l()`,
 * called from `NavApplication`'s initialiser. Shipping a bootstrap map is what let them make
 * the app usable the moment it is installed rather than forcing a download first — there are
 * three orphaned strings in their `strings.xml` (`Download the main map` and friends) left
 * over from when that download *was* mandatory.
 *
 * Two deliberate differences from the original:
 *
 *  - **The copy is verified.** Theirs is a plain byte copy with the `IOException` swallowed
 *    and logged as "tttt Error restoring resources", and the shipped asset is never checksum
 *    checked at all. A truncated install would leave the app silently warning about nothing,
 *    which is the worst possible failure for this app. Here the staging file must look like a
 *    SQLite database before it is allowed to replace anything.
 *  - **An existing database is never clobbered.** The user's rows in `override_` live in this
 *    same file, and the original's version bump re-copies unconditionally, which would discard
 *    them. A bundle upgrade is handled by Room migrations, and refreshing from upstream is
 *    Stage 8's job.
 *
 * The asset source is a function rather than an [android.content.res.AssetManager] so that the
 * whole installation path is testable on a plain JVM.
 */
class CameraDatabaseInstaller(
    private val target: File,
    private val assetSource: () -> InputStream?,
) {
    /**
     * Installs the bundled database unless a usable one is already there.
     *
     * Writes to a staging file first and renames, because a rename within one directory is
     * atomic: the app can never observe a partially written database, even if it is killed
     * mid-install.
     *
     * @throws IOException if the bundle is present but cannot be written. Failing loudly is
     *   deliberate — an app that cannot read its database would otherwise start normally and
     *   never warn about anything.
     */
    fun installIfNeeded(): DatabaseInstallOutcome {
        if (looksLikeSqliteDatabase(target)) return DatabaseInstallOutcome.ALREADY_PRESENT

        return attemptInstall().getOrElse { cause ->
            throw IOException(
                cause.message ?: "could not install the camera database at $target",
                cause,
            )
        }
    }

    /**
     * The body of the install, reporting failure as a value rather than by throwing.
     *
     * Split out so that [installIfNeeded] has a single exit point for the error case: a
     * straight-line sequence of `throw`s here would be four separate failure modes to read,
     * and detekt rightly caps the throw count in one function.
     */
    private fun attemptInstall(): Result<DatabaseInstallOutcome> =
        runCatching {
            val source = assetSource() ?: return@runCatching DatabaseInstallOutcome.NO_ASSET

            val parent =
                target.parentFile
                    ?: error("the database path has no parent directory: $target")
            if (!parent.isDirectory && !parent.mkdirs()) {
                error("could not create the database directory: $parent")
            }

            val staging = File(parent, target.name + STAGING_SUFFIX)
            val superseded = File(parent, target.name + SUPERSEDED_SUFFIX)

            source.use { input ->
                FileOutputStream(staging).use { out ->
                    input.copyTo(out, BUFFER_BYTES)
                    // Flush to disk before the rename, so a crash cannot leave a renamed but
                    // still-empty file in place.
                    out.fd.sync()
                }
            }

            if (!looksLikeSqliteDatabase(staging)) {
                staging.delete()
                error("the bundled database at $ASSET_PATH is not a readable SQLite file")
            }

            // Move any existing file aside rather than deleting it: a rename within one directory
            // is atomic, so the app never sees a missing or half-written database.
            val hadPrevious = target.exists()
            if (hadPrevious) {
                superseded.delete()
                check(target.renameTo(superseded)) {
                    "could not move the existing database aside: $target"
                }
                File(parent, target.name + "-wal").delete()
                File(parent, target.name + "-shm").delete()
            }

            if (!staging.renameTo(target)) {
                // Put the old file back rather than leaving the app with no database at all.
                if (hadPrevious) superseded.renameTo(target)
                staging.delete()
                error("could not install the database at $target")
            }

            superseded.delete()
            if (hadPrevious) DatabaseInstallOutcome.REPAIRED else DatabaseInstallOutcome.INSTALLED
        }

    /**
     * Forcibly replaces the current database with a fresh copy from APK assets,
     * resetting user data or recovering from corrupted state.
     */
    fun forceInstallFromAssets(): Result<DatabaseInstallOutcome> = attemptInstall()

    /**
     * Atomically replaces the current database with [candidate].
     *
     * The existing database is preserved as a `.superseded` backup file until explicitly
     * discarded with [discardSupersededBackup] or restored with [rollback].
     *
     * Any existing WAL/SHM sidecars of the current database are cleared so they do not
     * corrupt the newly installed file.
     */
    fun replaceWith(candidate: File): Result<DatabaseInstallOutcome> =
        runCatching {
            require(looksLikeSqliteDatabase(candidate)) {
                "The candidate file is not a valid SQLite database: $candidate"
            }

            val parent =
                target.parentFile
                    ?: error("the database path has no parent directory: $target")
            if (!parent.isDirectory && !parent.mkdirs()) {
                error("could not create the database directory: $parent")
            }

            val superseded = File(parent, target.name + SUPERSEDED_SUFFIX)
            val hadPrevious = target.exists()

            val targetWal = File(parent, target.name + "-wal")
            val targetShm = File(parent, target.name + "-shm")

            if (hadPrevious) {
                superseded.delete()
                check(target.renameTo(superseded)) {
                    "could not move the existing database aside to $superseded"
                }
                targetWal.delete()
                targetShm.delete()
            }

            val staging = File(parent, target.name + STAGING_SUFFIX)
            staging.delete()

            val placed =
                if (candidate.parentFile == parent) {
                    candidate.renameTo(target)
                } else {
                    candidate.inputStream().use { input ->
                        FileOutputStream(staging).use { out ->
                            input.copyTo(out, BUFFER_BYTES)
                            out.fd.sync()
                        }
                    }
                    val renamed = staging.renameTo(target)
                    staging.delete()
                    renamed
                }

            if (!placed || !looksLikeSqliteDatabase(target)) {
                target.delete()
                if (hadPrevious) superseded.renameTo(target)
                error("could not place or verify the candidate database at $target; rolled back")
            }

            if (hadPrevious) DatabaseInstallOutcome.REPAIRED else DatabaseInstallOutcome.INSTALLED
        }

    /**
     * Restores the previous database from `.superseded` if one exists.
     *
     * Returns true if a backup was present and successfully restored.
     */
    fun rollback(): Boolean {
        val parent = target.parentFile ?: return false
        val superseded = File(parent, target.name + SUPERSEDED_SUFFIX)
        if (!superseded.exists() || !looksLikeSqliteDatabase(superseded)) return false

        target.delete()
        File(parent, target.name + "-wal").delete()
        File(parent, target.name + "-shm").delete()
        return superseded.renameTo(target)
    }

    /** Discards the `.superseded` backup once the replacement has been verified. */
    fun discardSupersededBackup(): Boolean {
        val parent = target.parentFile ?: return false
        return File(parent, target.name + SUPERSEDED_SUFFIX).delete()
    }

    /** Whether a `.superseded` backup exists. */
    fun hasSupersededBackup(): Boolean {
        val parent = target.parentFile ?: return false
        return File(parent, target.name + SUPERSEDED_SUFFIX).exists()
    }

    companion object {
        /** Kept as a constant so the Gradle task and the installer cannot drift apart. */
        const val ASSET_FILE_NAME = "speedcam.db"

        /** Where the bundled database lives inside the APK, under the generated assets dir. */
        const val ASSET_PATH = "databases/$ASSET_FILE_NAME"

        private const val STAGING_SUFFIX = ".install"
        private const val SUPERSEDED_SUFFIX = ".superseded"
        private const val BUFFER_BYTES = 64 * 1024
        private const val HEADER_BYTES = 16

        /** The 16-byte file header every SQLite database starts with. */
        private val SQLITE_MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

        /** Builds an installer reading from the APK's assets. */
        fun forContext(context: Context): CameraDatabaseInstaller {
            val app = context.applicationContext
            return CameraDatabaseInstaller(
                target = app.getDatabasePath(SpeedCamDatabase.NAME),
                assetSource = {
                    try {
                        app.assets.open(ASSET_PATH)
                    } catch (_: IOException) {
                        null
                    }
                },
            )
        }

        /**
         * Cheap structural check: does this file begin with a SQLite header?
         *
         * Deliberately not a full integrity check — that is what Room's identity hash and the
         * importer's own `PRAGMA integrity_check` are for. This only has to catch the failure
         * that actually happens on a device: a truncated or absent copy.
         */
        fun looksLikeSqliteDatabase(file: File): Boolean {
            if (!file.isFile || file.length() < HEADER_BYTES) return false
            return try {
                FileInputStream(file).use { input ->
                    val header = ByteArray(HEADER_BYTES)
                    input.read(header) == HEADER_BYTES && header.contentEquals(SQLITE_MAGIC)
                }
            } catch (_: IOException) {
                false
            }
        }
    }
}
