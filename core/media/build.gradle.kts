plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.speedcam.core.media"
    compileSdk = 36

    defaultConfig { minSdk = 26 }

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

dependencies {
    // AlertDecision and Hazard appear in MediaAlertEngine's public API, so this is api.
    api(project(":core:detect"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit5)
    testImplementation(libs.kotlin.test.junit)
    testImplementation("org.json:json:20240303")
    testRuntimeOnly(libs.junit5.platform.launcher)
}

// Android modules have no plain `test` task; unit tests run per variant.
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
