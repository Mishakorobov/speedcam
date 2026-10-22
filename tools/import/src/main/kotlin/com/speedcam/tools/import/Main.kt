package com.speedcam.tools.import

import com.speedcam.core.detect.Camera
import com.speedcam.core.detect.SectionRecord
import com.speedcam.tools.import.db.DatabaseVerifier
import com.speedcam.tools.import.db.DatabaseWriter
import com.speedcam.tools.import.db.ManifestGenerator
import com.speedcam.tools.import.source.BoundingBox
import com.speedcam.tools.import.source.ContraCamParser
import com.speedcam.tools.import.source.OverpassClient
import com.speedcam.tools.import.source.SpeedCamParser
import com.speedcam.tools.import.source.pbf.PbfImporter
import java.io.File
import java.time.Instant
import kotlin.system.exitProcess

/**
 * Entry point for the offline database builder.
 *
 * Reads the configured sources, merges overlapping records, and writes a single SQLite file
 * whose schema matches Room's exactly. See Plan.md §8 for what Stage 1 delivers.
 */
fun main(args: Array<String>) {
    val options = parseArgs(args)

    if (options.help || options.isEmpty) {
        println(ImportOptions.USAGE)
        return
    }

    if (options.verify != null) {
        val dbFile = File(options.verify)
        val manifestFile = options.manifest?.let { File(it) }
        val result = DatabaseVerifier.verify(dbFile, manifestFile)
        println(result.formatReport())
        if (!result.ok) {
            exitProcess(1)
        }
        return
    }

    // Standalone manifest generation for an existing database
    if (options.hasNoSources && options.output != null && options.manifest != null) {
        val dbFile = File(options.output)
        val manifestFile = File(options.manifest)
        val manifest =
            ManifestGenerator.writeManifest(
                dbFile = dbFile,
                manifestFile = manifestFile,
                version = options.version,
                downloadUrl = options.downloadUrl,
            )
        println(
            "Generated manifest for ${dbFile.name} at ${manifestFile.absolutePath}: " +
                "version=${manifest.version}, sha256=${manifest.sha256}, cameras=${manifest.cameraCount}",
        )
        return
    }

    val outputPath =
        options.output ?: run {
            System.err.println("error: --output is required\n")
            System.err.println(ImportOptions.USAGE)
            exitProcess(2)
        }

    runImport(options, outputPath)
}

private fun runImport(
    options: Options,
    outputPath: String,
) {
    val cameras = mutableListOf<Camera>()
    val sections = mutableListOf<SectionRecord>()

    for (file in options.speedcamFiles) {
        System.err.println("Parsing SpeedCam: ${file.name}")
        val parsed = SpeedCamParser.parse(file)
        System.err.println("  ${parsed.size} records")
        cameras += parsed
    }

    for (file in options.contracamFiles) {
        System.err.println("Parsing ContraCam: ${file.name}")
        val parsed = ContraCamParser.parse(file)
        System.err.println("  ${parsed.size} records")
        cameras += parsed
    }

    for (file in options.pbfFiles) {
        System.err.println(
            "Reading PBF: ${file.name} " +
                "(${"%.0f".format(file.length() / 1024.0 / 1024.0)} MB)",
        )
        val result = PbfImporter().import(file) { System.err.println("  $it") }
        cameras += result.cameras
        sections += result.sections

        System.err.println(
            "  ${result.cameras.size} cameras, ${result.sections.size} sections, " +
                "%.0fM nodes scanned".format(result.nodesScanned / 1e6),
        )
        if (result.truncated) {
            System.err.println("  WARNING: the file ended mid-blob; only the complete prefix was read")
        }
    }

    for (area in options.areas) {
        val bbox = bboxFor(area)
        System.err.println("Fetching Overpass for $area (${bbox.describe()}), tiled...")
        fetchOverpass(bbox, cameras, sections)
    }

    for (bbox in options.bboxes) {
        System.err.println("Fetching Overpass for ${bbox.describe()}, tiled...")
        fetchOverpass(bbox, cameras, sections)
    }

    require(cameras.isNotEmpty()) { "no cameras parsed from any source" }

    System.err.println("Deduplicating ${cameras.size} records...")
    val merged = Deduplicator().merge(cameras)
    val collapsed = cameras.size - merged.size
    System.err.println("  ${merged.size} unique cameras ($collapsed duplicates collapsed)")

    val output = writeDatabase(outputPath, merged, sections)

    val sizeMb = output.length() / 1024.0 / 1024.0
    println("Wrote ${output.absolutePath} (${"%.1f".format(sizeMb)} MB)")
    println("${merged.size} cameras, ${sections.size} average-speed sections.")
    println("OpenStreetMap data is (c) OpenStreetMap contributors, licensed ODbL 1.0.")

    if (options.manifest != null) {
        val manifestFile = File(options.manifest)
        val manifest =
            ManifestGenerator.writeManifest(
                dbFile = output,
                manifestFile = manifestFile,
                version = options.version,
                downloadUrl = options.downloadUrl,
                cameraCount = merged.size,
                sectionCount = sections.size,
            )
        println("Generated manifest at ${manifestFile.absolutePath} (sha256: ${manifest.sha256})")
    }
}

private fun fetchOverpass(
    bbox: BoundingBox,
    cameras: MutableList<Camera>,
    sections: MutableList<SectionRecord>,
) {
    OverpassClient().use { client ->
        cameras += client.fetchCameras(bbox)
        sections += client.fetchSections(bbox)
        reportFailedTiles(client)
    }
    System.err.println("  running total: ${cameras.size} cameras, ${sections.size} sections")
}

private fun reportFailedTiles(client: OverpassClient) {
    if (client.failedTiles.isEmpty()) return

    System.err.println("  WARNING: ${client.failedTiles.size} tile(s) failed and were skipped:")
    client.failedTiles.forEach { System.err.println("    ${it.describe()}") }
}

private fun writeDatabase(
    path: String,
    cameras: List<Camera>,
    sections: List<SectionRecord>,
): File {
    val output = File(path)
    DatabaseWriter.create(output.absolutePath).use { db ->
        db.open()
        db.createSchema()

        // replaceCameras partitions by each record's own source, so no grouping is needed here.
        System.err.println("  writing ${cameras.size} camera records")
        db.replaceCameras(cameras)
        db.replaceSections(sections)

        db.putMeta("built_at", Instant.now().toString())
        db.putMeta("camera_count", cameras.size.toString())
        db.putMeta("section_count", sections.size.toString())
        db.putMeta("sources", cameras.groupingBy { it.source }.eachCount().toString())
        db.putMeta("attribution", "Data (c) OpenStreetMap contributors (ODbL 1.0)")

        db.optimise()
    }
    return output
}

private data class Options(
    val help: Boolean = false,
    val speedcamFiles: List<File> = emptyList(),
    val contracamFiles: List<File> = emptyList(),
    val areas: List<String> = emptyList(),
    val bboxes: List<BoundingBox> = emptyList(),
    val pbfFiles: List<File> = emptyList(),
    val output: String? = null,
    val manifest: String? = null,
    val downloadUrl: String? = null,
    val version: String? = null,
    val verify: String? = null,
) {
    val hasNoSources: Boolean
        get() = speedcamFiles.isEmpty() && contracamFiles.isEmpty() && areas.isEmpty() && bboxes.isEmpty() && pbfFiles.isEmpty()

    val isEmpty: Boolean
        get() = hasNoSources && output == null && verify == null && manifest == null
}

private fun parseArgs(args: Array<String>): Options {
    var options = Options()
    var i = 0
    while (i < args.size) {
        when (val arg = args[i]) {
            "--${ImportOptions.KEY_HELP}", "-h" -> options = options.copy(help = true)
            "--${ImportOptions.KEY_OUTPUT}" -> {
                options = options.copy(output = args.getOrNull(++i))
            }
            "--${ImportOptions.KEY_SPEEDCAM}" -> {
                val path = args.getOrNull(++i) ?: error("--speedcam needs a file path")
                options = options.copy(speedcamFiles = options.speedcamFiles + File(path))
            }
            "--${ImportOptions.KEY_CONTRACAM}" -> {
                val path = args.getOrNull(++i) ?: error("--contracam needs a file path")
                options = options.copy(contracamFiles = options.contracamFiles + File(path))
            }
            "--${ImportOptions.KEY_OVERPASS}" -> {
                val area = args.getOrNull(++i) ?: error("--osm-area needs a value")
                require(area in ImportOptions.Areas.ALL) {
                    "unknown area '$area'; expected one of ${ImportOptions.Areas.ALL}"
                }
                options = options.copy(areas = options.areas + area)
            }
            "--${ImportOptions.KEY_BBOX}" -> {
                val text = args.getOrNull(++i) ?: error("--osm-bbox needs south,west,north,east")
                options = options.copy(bboxes = options.bboxes + ImportOptions.parseBbox(text))
            }
            "--${ImportOptions.KEY_PBF}" -> {
                val path = args.getOrNull(++i) ?: error("--osm-pbf needs a file path")
                options = options.copy(pbfFiles = options.pbfFiles + File(path))
            }
            "--${ImportOptions.KEY_MANIFEST}" -> {
                options = options.copy(manifest = args.getOrNull(++i) ?: error("--manifest needs a file path"))
            }
            "--${ImportOptions.KEY_DOWNLOAD_URL}" -> {
                options = options.copy(downloadUrl = args.getOrNull(++i) ?: error("--download-url needs a URL"))
            }
            "--${ImportOptions.KEY_VERSION}" -> {
                options = options.copy(version = args.getOrNull(++i) ?: error("--version needs a version string"))
            }
            "--${ImportOptions.KEY_VERIFY}" -> {
                options = options.copy(verify = args.getOrNull(++i) ?: error("--verify needs a database file path"))
            }
            else -> error("unknown argument: $arg")
        }
        i++
    }
    return options
}

private fun bboxFor(area: String): BoundingBox =
    when (area) {
        ImportOptions.Areas.RUSSIA -> BoundingBox.RUSSIA
        else -> error("unhandled area: $area")
    }
