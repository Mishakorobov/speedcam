import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.nio.file.Files
import java.nio.file.StandardCopyOption

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.speedcam.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.speedcam.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Signed with a debug key for personal sideloading. Replace before any other use.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    lint {
        disable += "InvalidFragmentVersionForActivityResult"
    }
}

/**
 * Stages the camera database built by `tools/import` into the APK's assets.
 *
 * Declared as a class in the build script rather than as a `doLast` closure. A closure
 * defined in a Gradle Kotlin DSL script captures the script instance as soon as it touches a
 * script-level `val`, and the configuration cache rejects those references outright. A task
 * class has no such problem: its action is a method, and everything it needs arrives through
 * properties.
 *
 * All inputs are wired in from outside, so the name `speedcam.db` lives here rather than in
 * the copy configuration. That matches what `CameraDatabaseInstaller` looks for.
 */
abstract class StageCameraDatabaseTask : DefaultTask() {
    @get:InputFile
    @get:Optional
    abstract val source: RegularFileProperty

    @get:OutputFile
    abstract val target: RegularFileProperty

    @TaskAction
    fun stage() {
        val from: File? = source.orNull?.asFile
        val to: File = target.get().asFile

        if (from == null || !from.isFile) {
            // Loudly, because the alternative failure is invisible: the app would install,
            // open an empty database and never warn about anything.
            logger.warn(
                "WARNING: Camera database source is missing, so no camera database will be bundled and the app " +
                    "will start with zero cameras. Run tools\\import\\update-osm.bat to build it.",
            )
            Files.deleteIfExists(to.toPath())
            return
        }

        Files.createDirectories(to.parentFile.toPath())
        copyOf(from, to)
        logger.lifecycle("Staged ${to.name} (${Files.size(to.toPath())} bytes) into the APK")
    }

    /**
     * Copies the database, refusing to do so if rows are still sitting in a write-ahead log.
     *
     * The importer leaves the database in WAL mode, where recent writes live in a `-wal`
     * sidecar until they are checkpointed. Copying only the main file would therefore produce
     * a structurally valid database that is silently missing its newest cameras - and a valid
     * header is all the installer's check looks at, so nothing downstream would catch it. A
     * zero-length `-wal` is normal and means everything has been folded in.
     */
    private fun copyOf(
        from: File,
        to: File,
    ) {
        val wal = File(from.parentFile, from.name + "-wal")
        if (wal.isFile && wal.length() > 0L) {
            throw GradleException(
                "$wal is not empty, so ${from.name} is missing rows that exist only in the " +
                    "write-ahead log. Bundling it now would ship a database that looks valid " +
                    "but silently lacks its newest cameras. Checkpoint it first: run " +
                    "tools\\import\\update-osm.bat --verify, or delete the -wal file once you " +
                    "have confirmed the database is complete.",
            )
        }
        Files.copy(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}

val cameraDatabaseSource: File =
    rootProject.layout.projectDirectory
        .file("data/russia.db")
        .asFile
val cameraAssetsDir: File =
    layout.buildDirectory
        .dir("generated/cameraAssets")
        .get()
        .asFile
val stagedCameraDatabase: File = File(cameraAssetsDir, "databases/speedcam.db")

val stageCameraDatabase =
    tasks.register<StageCameraDatabaseTask>("stageCameraDatabase") {
        description = "Stages data/russia.db into the APK as an asset."
        group = "build"

        if (cameraDatabaseSource.isFile) {
            source.set(cameraDatabaseSource)
        }
        target.set(stagedCameraDatabase)
    }

android.sourceSets.getByName("main") {
    assets.srcDir(cameraAssetsDir)
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(stageCameraDatabase)
}

dependencies {
    implementation(project(":core:detect"))
    implementation(project(":core:data"))
    implementation(project(":core:media"))
    implementation(project(":service"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.car.app)
    implementation(libs.play.services.wearable)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.gson)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit5)
    testImplementation(libs.kotlin.test.junit)
    testRuntimeOnly(libs.junit5.platform.launcher)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
}

// Android modules have no plain `test` task; unit tests run per variant
// (testDebugUnitTest, testReleaseUnitTest), so configure every Test task instead.
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
