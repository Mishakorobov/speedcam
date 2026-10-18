package com.speedcam.tools.import.source.pbf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.math.abs

/** A block holding exactly one untagged node, for framing tests. */
private fun singleNodeBlock(
    id: Long,
    lat: Long,
    lon: Long,
): ByteArray = PbfBuilder.field(2, PbfBuilder.denseNodes(listOf(id), listOf(lat), listOf(lon), listOf(0)))

/**
 * String table for an average-speed relation.
 *
 * Index 3 is repeated deliberately: "enforcement" is both the value of `type` and the key of
 * `enforcement`. Keys and values share one table, so a fixture that reused a single index for
 * both would hide a decoding bug.
 */
private val ASC_STRINGS =
    listOf(
        "", // 0
        "type", // 1
        "enforcement", // 2 - value of `type`
        "enforcement", // 3 - key of `enforcement`
        "average_speed", // 4
        "maxspeed", // 5
        "80", // 6
        "from", // 7
        "to", // 8
    )

/** Key/value indices for [ASC_STRINGS]: type, enforcement, maxspeed. */
private val ASC_KEYS = listOf(1, 2, 3, 4, 5, 6)

class ProtoReaderTest {
    @Test
    fun `varints round-trip across byte boundaries`() {
        val values = listOf(0L, 1L, 127L, 128L, 300L, 16383L, 16384L, 1_000_000_000L, Long.MAX_VALUE)
        for (v in values) {
            val encoded = PbfBuilder.varint(v)
            assertEquals(v, ProtoReader(encoded).readVarint(), "value $v")
        }
    }

    @Test
    fun `zigzag encoding maps the documented sequence`() {
        assertEquals(0L, ProtoReader.zigzagDecode(0))
        assertEquals(-1L, ProtoReader.zigzagDecode(1))
        assertEquals(1L, ProtoReader.zigzagDecode(2))
        assertEquals(-2L, ProtoReader.zigzagDecode(3))
        assertEquals(2L, ProtoReader.zigzagDecode(4))
    }

    @Test
    fun `packed sint64 arrays decode to their zigzag values`() {
        // Encoding is little: zigzag(-1), zigzag(0), zigzag(1) -> 1, 0, 2.
        val packed = byteArrayOf(1, 0, 2)
        val reader = ProtoReader(PbfBuilder.field(10, packed))
        val field = reader.next()
        assertEquals(10, field.number)
        assertEquals(listOf(-1L, 0L, 1L), reader.readPackedSint64().toList())
    }

    @Test
    fun `unknown fields are skipped rather than corrupting the stream`() {
        val bytes = PbfBuilder.varintField(999, 42L) + PbfBuilder.varintField(7, 7L)
        val reader = ProtoReader(bytes)

        reader.next().also {
            assertEquals(999, it.number)
            reader.skip(it.wireType)
        }
        reader.next().also {
            assertEquals(7, it.number)
            assertEquals(7L, reader.readVarint())
        }
    }

    @Test
    fun `a truncated buffer raises rather than returning garbage`() {
        // 300 encodes as [0xAC, 0x02]. Dropping the final byte leaves a varint whose
        // continuation bit is set but whose payload has run out.
        val encoded = PbfBuilder.varint(300)
        assertTrue(encoded.size >= 2, "expected a multi-byte varint for the test to be meaningful")

        val cut = encoded.copyOf(encoded.size - 1)

        var threw = false
        try {
            ProtoReader(cut).readVarint()
        } catch (_: java.io.IOException) {
            threw = true
        }
        assertTrue(threw, "a truncated varint must not decode silently")
    }
}

class PbfReaderTest {
    @TempDir
    lateinit var tempDir: Path

    /** 55.755327 N, 37.617274 E at the default 100-nanodegree granularity. */
    private val moscowLat = 557_553_270L
    private val moscowLon = 376_172_740L

    private fun writePbf(
        blocks: List<ByteArray>,
        name: String = "test.osm.pbf",
    ): File {
        val file = tempDir.resolve(name).toFile()
        file.writeBytes(PbfBuilder.file(blocks))
        return file
    }

    @Test
    fun `decodes dense nodes to exact degrees`() {
        // String table index 1 = "highway", 2 = "speed_camera".
        val block =
            PbfBuilder.primitiveBlock(
                strings = listOf("", "highway", "speed_camera"),
                groups =
                    listOf(
                        PbfBuilder.field(
                            2,
                            PbfBuilder.denseNodes(
                                ids = listOf(1001L, 1002L),
                                lats = listOf(moscowLat, moscowLat + 1_000L),
                                lons = listOf(moscowLon, moscowLon),
                                keysVals = listOf(1, 2, 0, 0),
                            ),
                        ),
                    ),
            )

        PbfReader(writePbf(listOf(block))).use { reader ->
            var seen = 0
            var firstLat = 0.0
            var firstLon = 0.0

            reader.scan { group, context ->
                group.dense?.forEach { d ->
                    for (i in d.ids.indices) {
                        if (seen == 0) {
                            firstLat = context.latitude(d.lat[i])
                            firstLon = context.longitude(d.lon[i])
                        }
                        seen++
                    }
                }
            }

            assertEquals(2, seen)
            // Exact round-trip through the nanodegree scaling.
            assertTrue(abs(firstLat - 55.7553270) < 1e-7, "latitude was $firstLat")
            assertTrue(abs(firstLon - 37.6172740) < 1e-7, "longitude was $firstLon")
            assertEquals(2L, reader.nodeCount)
        }
    }

    @Test
    fun `node tags are decoded with the per-node cursor`() {
        val block =
            PbfBuilder.primitiveBlock(
                strings = listOf("", "highway", "speed_camera", "maxspeed", "60", "direction", "180"),
                groups =
                    listOf(
                        PbfBuilder.field(
                            2,
                            PbfBuilder.denseNodes(
                                ids = listOf(1L, 2L),
                                lats = listOf(moscowLat, moscowLat),
                                lons = listOf(moscowLon, moscowLon),
                                // First node carries all three tags; the second carries none.
                                keysVals = listOf(1, 2, 3, 4, 5, 6, 0, 0),
                            ),
                        ),
                    ),
            )

        PbfReader(writePbf(listOf(block))).use { reader ->
            reader.scan { group, context ->
                val d = group.dense!!.single()
                val first = context.tagsOf(d.keysVals, 0)
                val second = context.tagsOf(d.keysVals, first.next)

                assertTrue(first.isCamera, "the first node is tagged as a speed camera")
                assertFalse(second.isCamera, "the second node carries no tags")
                assertEquals("60", first.map["maxspeed"])
                assertEquals("180", first.map["direction"])
            }
        }
    }

    @Test
    fun `relations decode ids, types and roles`() {
        // "", "type", "enforcement", "from", "to"
        val block =
            PbfBuilder.primitiveBlock(
                strings = listOf("", "type", "enforcement", "from", "to"),
                groups =
                    listOf(
                        PbfBuilder.field(
                            4,
                            PbfBuilder.relation(
                                id = 9001L,
                                keysVals = listOf(1, 2),
                                types = listOf(0, 0),
                                memberIds = listOf(501L, 502L),
                                roles = listOf(3, 4),
                            ),
                        ),
                    ),
            )

        PbfReader(writePbf(listOf(block))).use { reader ->
            var checked = false
            reader.scan { group, context ->
                val r = group.relations!!.single()
                assertEquals(9001L, r.id)
                assertEquals(listOf(501L, 502L), r.memberIds.toList())
                assertEquals("from", context.strings[r.roles[0]])
                assertEquals("to", context.strings[r.roles[1]])
                assertEquals("enforcement", context.relationTags(r.keysVals)["type"])
                checked = true
            }
            assertTrue(checked, "no relation was decoded")
        }
    }

    @Test
    fun `a complete file is not reported as truncated`() {
        val block =
            PbfBuilder.primitiveBlock(
                strings = listOf(""),
                groups = listOf(singleNodeBlock(1L, 1L, 1L)),
            )

        PbfReader(writePbf(listOf(block))).use { reader ->
            reader.scan { _, _ -> }
            assertFalse(reader.truncated, "a file that ends on a blob boundary is complete")
        }
    }

    @Test
    fun `a partial file is read up to the last complete blob and reported`() {
        val good =
            PbfBuilder.primitiveBlock(
                strings = listOf(""),
                groups = listOf(singleNodeBlock(1L, 1L, 1L)),
            )
        val file = tempDir.resolve("partial.osm.pbf").toFile()
        val complete = PbfBuilder.framedBlob("OSMData", good)
        // Keep the first blob plus half of the next blob header: a mid-blob cut.
        file.writeBytes(complete + ByteArray(7) { 0x41 })

        PbfReader(file).use { reader ->
            reader.scan { _, _ -> }
            assertTrue(reader.truncated, "a mid-blob cut must be reported, not silently accepted")
            assertEquals(1L, reader.blobsRead, "the complete prefix must still be decoded")
        }
    }

    @Test
    fun `several blobs in one file are all decoded`() {
        val block =
            PbfBuilder.primitiveBlock(
                strings = listOf(""),
                groups = listOf(singleNodeBlock(7L, 5L, 9L)),
            )

        PbfReader(writePbf(listOf(block, block, block), "multi.osm.pbf")).use { reader ->
            reader.scan { _, _ -> }
            assertEquals(3L, reader.blobsRead)
            assertEquals(3L, reader.nodeCount)
        }
    }
}

class PbfImporterTest {
    @TempDir
    lateinit var tempDir: Path

    private fun write(
        blocks: List<ByteArray>,
        name: String,
    ): File {
        val f = tempDir.resolve(name).toFile()
        f.writeBytes(PbfBuilder.file(blocks))
        return f
    }

    @Test
    fun `extracts a speed camera node with its tags`() {
        val block =
            PbfBuilder.primitiveBlock(
                strings = listOf("", "highway", "speed_camera", "maxspeed", "60", "operator", "ГИБДД"),
                groups =
                    listOf(
                        PbfBuilder.field(
                            2,
                            PbfBuilder.denseNodes(
                                ids = listOf(4242L),
                                lats = listOf(557_553_270L),
                                lons = listOf(376_172_740L),
                                keysVals = listOf(1, 2, 3, 4, 5, 6, 0),
                            ),
                        ),
                    ),
            )

        val result = PbfImporter().import(write(listOf(block), "one.osm.pbf"))

        assertEquals(1, result.cameras.size)
        val c = result.cameras.single()
        assertEquals("node/4242", c.externalId)
        assertEquals(60, c.limitKmh)
        assertEquals("ГИБДД", c.operator)
        assertTrue(abs(c.position.lat - 55.7553270) < 1e-7, "latitude was ${c.position.lat}")
        assertNull(c.facingDeg, "no direction tag means no facing")
    }

    @Test
    fun `an average-speed relation resolves its endpoint nodes`() {
        // The relation appears BEFORE its member nodes, which is exactly the ordering that
        // forces the two-pass design.
        // Index 3 is used twice on purpose: "enforcement" is both the value of the `type`
        // tag and the key of the `enforcement` tag. Keys and values share one string
        // table, so a fixture that reused a single index for both would hide a bug.
        val strings =
            listOf(
                "",
                "type", // 1
                "enforcement", // 2 - value of `type`
                "enforcement", // 3 - key of `enforcement`
                "average_speed", // 4
                "maxspeed", // 5
                "80", // 6
                "from", // 7
                "to", // 8
            )

        val relationBlock =
            PbfBuilder.primitiveBlock(
                strings = strings,
                groups =
                    listOf(
                        PbfBuilder.field(
                            4,
                            PbfBuilder.relation(
                                id = 9001L,
                                keysVals = listOf(1, 2, 3, 4, 5, 6),
                                types = listOf(0, 0),
                                memberIds = listOf(501L, 502L),
                                roles = listOf(7, 8),
                            ),
                        ),
                    ),
            )

        val nodeBlock =
            PbfBuilder.primitiveBlock(
                strings = strings,
                groups =
                    listOf(
                        PbfBuilder.field(
                            2,
                            PbfBuilder.denseNodes(
                                ids = listOf(501L, 502L),
                                lats = listOf(551_000_000L, 551_200_000L),
                                lons = listOf(374_000_000L, 374_200_000L),
                                keysVals = listOf(0, 0),
                            ),
                        ),
                    ),
            )

        val result = PbfImporter().import(write(listOf(relationBlock, nodeBlock), "asc.osm.pbf"))

        assertEquals(1, result.sections.size, "the relation must resolve against the second pass")
        val s = result.sections.single()
        assertEquals(80, s.limitKmh)
        assertTrue(abs(s.from.lat - 55.1) < 1e-7, "from latitude was ${s.from.lat}")
        assertTrue(abs(s.to.lat - 55.12) < 1e-7, "to latitude was ${s.to.lat}")
        assertTrue(s.lengthM > 0)
    }

    @Test
    fun `a relation whose endpoints are missing yields nothing rather than a guess`() {
        val block =
            PbfBuilder.primitiveBlock(
                strings = ASC_STRINGS,
                groups =
                    listOf(
                        PbfBuilder.field(
                            4,
                            PbfBuilder.relation(9001L, ASC_KEYS, listOf(0, 0), listOf(501L, 502L), listOf(7, 8)),
                        ),
                    ),
            )

        val result = PbfImporter().import(write(listOf(block), "dangling.osm.pbf"))
        assertTrue(result.sections.isEmpty(), "unresolvable endpoints must not produce a corridor")
    }

    @Test
    fun `nodes that are not cameras are ignored`() {
        val block =
            PbfBuilder.primitiveBlock(
                strings = listOf("", "highway", "traffic_signals", "amenity", "cafe"),
                groups =
                    listOf(
                        PbfBuilder.field(
                            2,
                            PbfBuilder.denseNodes(
                                ids = listOf(1L, 2L),
                                lats = listOf(551_000_000L, 551_000_000L),
                                lons = listOf(374_000_000L, 374_000_000L),
                                keysVals = listOf(1, 2, 0, 3, 4, 0),
                            ),
                        ),
                    ),
            )

        val result = PbfImporter().import(write(listOf(block), "nocam.osm.pbf"))
        assertTrue(result.cameras.isEmpty(), "only highway=speed_camera counts")
        assertEquals(2L, result.nodesScanned)
    }
}
