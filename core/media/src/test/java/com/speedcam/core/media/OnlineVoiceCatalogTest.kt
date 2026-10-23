package com.speedcam.core.media

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class OnlineVoiceCatalogTest {
    @Test
    fun bundledCatalog_containsExpectedVoicePacks() {
        val packs = OnlineVoiceCatalog.BUNDLED_CATALOG
        assertFalse(packs.isEmpty())
        val elena = packs.firstOrNull { it.id == "elena_standard" }
        assertNotNull(elena)
        assertEquals("ru", elena?.language)
        assertEquals(48, elena?.clipCount)
    }

    @Test
    fun parseCatalogJson_parsesValidJsonSuccessfully() {
        val json =
            """
            {
              "packs": [
                {
                  "id": "test_pack",
                  "name": "Test Voice",
                  "author": "Tester",
                  "description": "Short description",
                  "language": "ru",
                  "downloadUrl": "https://example.com/test.zip",
                  "sizeMb": 2.5,
                  "clipCount": 35,
                  "license": "CC0"
                }
              ]
            }
            """.trimIndent()

        val parsed = OnlineVoiceCatalog.parseCatalogJson(json)
        assertEquals(1, parsed.size)
        val pack = parsed.first()
        assertEquals("test_pack", pack.id)
        assertEquals("Test Voice", pack.name)
        assertEquals(2.5f, pack.approximateSizeMb)
        assertEquals(35, pack.clipCount)
        assertEquals("CC0", pack.license)
    }

    @Test
    fun parseCatalogJson_onCorruptJson_returnsBundledCatalog() {
        val corrupt = "{ invalid json..."
        val result = OnlineVoiceCatalog.parseCatalogJson(corrupt)
        assertEquals(OnlineVoiceCatalog.BUNDLED_CATALOG.size, result.size)
    }
}
