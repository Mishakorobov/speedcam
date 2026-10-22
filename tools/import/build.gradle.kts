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
 *   ./gradlew :tools:import:run -PosmDir=osm -Poutput=data/russia.db -Pmanifest=data/manifest.json
 */
tasks.named<JavaExec>("run") {
    val projectRoot = rootProject.projectDir

    val osmDir =
        providers.gradleProperty("osmDir").orNull?.let { rootProject.file(it) }
            ?: projectRoot.resolve("osm")
    val output =
        providers.gradleProperty("output").orNull?.let { rootProject.file(it) }
            ?: projectRoot.resolve("data").resolve("russia.db")
    val speedcamFile = providers.gradleProperty("speedcamFile").orNull?.let { rootProject.file(it) }
    val contracamFile = providers.gradleProperty("contracamFile").orNull?.let { rootProject.file(it) }
    val manifest = providers.gradleProperty("manifest").orNull?.let { rootProject.file(it) }
    val downloadUrl = providers.gradleProperty("downloadUrl").orNull
    val version = providers.gradleProperty("version").orNull

    val extracts =
        fileTree(osmDir) { include("*-latest.osm.pbf") }
            .files
            .sortedBy { it.name }

    when {
        // Extracts or custom source files present: build the database from them.
        extracts.isNotEmpty() || speedcamFile != null || contracamFile != null -> {
            val staging = File(output.absolutePath + ".staging")
            val stagingManifest = manifest?.let { File(it.absolutePath + ".staging") }

            args("--output", staging.absolutePath)
            extracts.forEach { args("--osm-pbf", it.absolutePath) }

            if (speedcamFile != null) {
                if (!speedcamFile.isFile) throw GradleException("SpeedCam file not found: $speedcamFile")
                args("--speedcam", speedcamFile.absolutePath)
            }
            if (contracamFile != null) {
                if (!contracamFile.isFile) throw GradleException("ContraCam file not found: $contracamFile")
                args("--contracam", contracamFile.absolutePath)
            }
            if (stagingManifest != null) {
                args("--manifest", stagingManifest.absolutePath)
            }
            if (downloadUrl != null) {
                args("--download-url", downloadUrl)
            }
            if (version != null) {
                args("--version", version)
            }

            logger.lifecycle("Importing ${extracts.size} extract(s) from $osmDir into $output")

            doLast {
                if (!staging.isFile || staging.length() == 0L) {
                    throw GradleException("the importer produced no output; $output was left untouched")
                }
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
                if (stagingManifest != null && stagingManifest.isFile) {
                    manifest.parentFile?.mkdirs()
                    if (manifest.isFile) manifest.delete()
                    if (!stagingManifest.renameTo(manifest)) {
                        throw GradleException("could not install manifest to $manifest")
                    }
                }
                logger.lifecycle("Installed ${output.absolutePath} ($bytes bytes)")
            }
        }

        osmDir.exists() && providers.gradleProperty("osmDir").isPresent -> {
            throw GradleException("no *-latest.osm.pbf files found in $osmDir")
        }

        else ->
            logger.lifecycle(
                "No extracts in $osmDir - run tools/import/update-osm or pass -PspeedcamFile=... / -PcontracamFile=...",
            )
    }
}

/**
 * Task to verify an existing database against Room schema hash and optional manifest.
 *
 * Usage:
 *   ./gradlew :tools:import:verifyDatabase -Pdb=data/russia.db -Pmanifest=data/manifest.json
 */
tasks.register<JavaExec>("verifyDatabase") {
    mainClass.set("com.speedcam.tools.import.MainKt")
    classpath = sourceSets["main"].runtimeClasspath

    val projectRoot = rootProject.projectDir
    val dbFile =
        providers.gradleProperty("db").orNull?.let { rootProject.file(it) }
            ?: projectRoot.resolve("data").resolve("russia.db")
    val manifestFile =
        providers.gradleProperty("manifest").orNull?.let { rootProject.file(it) }
            ?: projectRoot.resolve("data").resolve("manifest.json")

    args("--verify", dbFile.absolutePath)
    if (manifestFile.isFile) {
        args("--manifest", manifestFile.absolutePath)
    }
}

/**
 * Task to generate or regenerate a verified manifest.json for an existing database.
 *
 * Usage:
 *   ./gradlew :tools:import:generateManifest -Pdb=data/russia.db -Pmanifest=data/manifest.json
 */
tasks.register<JavaExec>("generateManifest") {
    mainClass.set("com.speedcam.tools.import.MainKt")
    classpath = sourceSets["main"].runtimeClasspath

    val projectRoot = rootProject.projectDir
    val dbFile =
        providers.gradleProperty("db").orNull?.let { rootProject.file(it) }
            ?: projectRoot.resolve("data").resolve("russia.db")
    val manifestFile =
        providers.gradleProperty("manifest").orNull?.let { rootProject.file(it) }
            ?: projectRoot.resolve("data").resolve("manifest.json")
    val downloadUrl = providers.gradleProperty("downloadUrl").orNull
    val version = providers.gradleProperty("version").orNull

    args("--output", dbFile.absolutePath)
    args("--manifest", manifestFile.absolutePath)
    if (downloadUrl != null) {
        args("--download-url", downloadUrl)
    }
    if (version != null) {
        args("--version", version)
    }
}
