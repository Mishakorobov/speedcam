plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

application {
    mainClass.set("com.speedcam.tools.import.MainKt")
}

dependencies {
    // Deliberately does NOT depend on :core:data. The importer runs on a desktop JVM with no
    // Android runtime, so it cannot use Room. It writes the SQLite file directly, which also
    // keeps Room's schema ownership in the app rather than the toolchain.
    implementation(project(":core:geo"))
    implementation(project(":core:detect"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.sqlite.jdbc)
    implementation(libs.gson)

    testImplementation(libs.junit5)
    testImplementation(libs.kotlin.test.junit)
    testRuntimeOnly(libs.junit5.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}

/**
 * Lets the update script drive a full-country import without quoting a long argument list.
 *
 * `--args=` on the command line would need every path quoted and then survive a Windows
 * batch round-trip, which is where paths with spaces go wrong. Passing the inputs as Gradle
 * properties keeps them intact and puts argument assembly here, next to the program that
 * parses them.
 *
 *   ./gradlew :tools:import:run -PosmDir=osm -Poutput=data/russia.db
 *   ./gradlew :tools:import:run -PosmDir=osm -Poutput=data/russia.db -PspeedcamFile=rus.txt
 *
 * With no properties at all the task falls back to the project's own `osm\` folder and
 * `data\russia.db`, so a plain `./gradlew :tools:import:run` does the expected thing and
 * writes to the same path update-osm.bat maintains.
 */
tasks.named<JavaExec>("run") {
    // gradleProperty returns the raw String; file() resolves it against the project so a
    // relative path and one containing spaces both work.
    val projectRoot = rootProject.projectDir

    val osmDir =
        providers.gradleProperty("osmDir").orNull?.let { file(it) }
            ?: projectRoot.resolve("osm")
    val output =
        providers.gradleProperty("output").orNull?.let { file(it) }
            ?: projectRoot.resolve("data").resolve("russia.db")
    val speedcamFile = providers.gradleProperty("speedcamFile").orNull?.let { file(it) }

    val extracts =
        fileTree(osmDir) { include("*-latest.osm.pbf") }
            .files
            .sortedBy { it.name }

    when {
        // Extracts present: build the database from them.
        extracts.isNotEmpty() -> {
            // Built to a staging path and moved into place afterwards, so a failure or a
            // cancelled run cannot leave a half-written file where a working database is
            // expected. update-osm.bat does the same and additionally checks integrity and
            // the schema hash; this is the floor, not the full check.
            val staging = File(output.absolutePath + ".staging")

            args("--output", staging.absolutePath)
            extracts.forEach { args("--osm-pbf", it.absolutePath) }

            if (speedcamFile != null) {
                if (!speedcamFile.isFile) throw GradleException("SpeedCam file not found: $speedcamFile")
                args("--speedcam", speedcamFile.absolutePath)
            }
            logger.lifecycle("Importing ${extracts.size} extract(s) from $osmDir into $output")

            doLast {
                if (!staging.isFile || staging.length() == 0L) {
                    throw GradleException("the importer produced no output; $output was left untouched")
                }
                // Read the size before the move: afterwards the staging path is gone and
                // reports 0.
                val bytes = staging.length()
                output.parentFile?.mkdirs()
                if (output.isFile) {
                    val backup = File(output.absolutePath + ".bak")
                    backup.delete()
                    if (!output.renameTo(backup)) {
                        throw GradleException("could not move the previous database aside to $backup")
                    }
                }
                if (!staging.renameTo(output)) {
                    throw GradleException("could not install $staging as $output")
                }
                logger.lifecycle("Installed ${output.absolutePath} ($bytes bytes)")
            }
        }

        // No extracts, but the caller named a source explicitly: leave the arguments alone
        // so --args or an online source still works.
        osmDir.exists() && providers.gradleProperty("osmDir").isPresent -> {
            throw GradleException("no *-latest.osm.pbf files found in $osmDir")
        }

        else ->
            logger.lifecycle(
                "No extracts in $osmDir - run tools\\import\\update-osm.bat to fetch them, " +
                    "or pass --args=... to use another source.",
            )
    }
}
