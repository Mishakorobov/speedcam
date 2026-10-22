package com.speedcam.app.map

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class MbTilesValidationTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `isValidMbTiles returns false for missing or zero-length file`() {
        val nonExistent = File(tempDir, "missing.mbtiles")
        assertFalse(MbTilesDatabase.isValidMbTiles(nonExistent))

        val emptyFile = File(tempDir, "empty.mbtiles").apply { writeBytes(ByteArray(0)) }
        assertFalse(MbTilesDatabase.isValidMbTiles(emptyFile))
    }

    @Test
    fun `isValidMbTiles returns false for small non-sqlite files`() {
        val smallFile = File(tempDir, "small.mbtiles").apply { writeText("just a tiny text") }
        assertFalse(MbTilesDatabase.isValidMbTiles(smallFile))
    }
}
