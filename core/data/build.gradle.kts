plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.speedcam.core.data"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
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
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    api(project(":core:detect"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    api(libs.androidx.room.runtime)
    api(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit5)
    testImplementation(libs.kotlin.test.junit)
    testRuntimeOnly(libs.junit5.platform.launcher)

    // Lets RealDatabaseDetectionTest open data/russia.db directly on the JVM. Room's generated
    // DAO needs a device or an emulator, so the end-to-end test reads the same SQL through
    // JDBC instead. Test-only: sqlite-jdbc must never reach the APK.
    testImplementation(libs.sqlite.jdbc)
}

// Android modules have no plain `test` task; unit tests run per variant.
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // The database lives at <root>/data while the module directory is the working directory.
    // The test locates it by walking upwards, so this is only a convenience for reporting.
    testLogging {
        events("skipped")
        showStandardStreams = false
    }
}
