plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.speedcam.service"
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
    api(project(":core:detect"))
    api(project(":core:data"))
    api(project(":core:media"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit5)
    testImplementation(libs.kotlin.test.junit)
    testRuntimeOnly(libs.junit5.platform.launcher)
}

// Android modules have no plain `test` task; unit tests run per variant.
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
