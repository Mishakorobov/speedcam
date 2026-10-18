import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.jlleitschuh.gradle.ktlint.KtlintExtension

// Top-level build file. Plugins are declared with `apply false` so individual modules opt in
// without repeating versions. Static analysis is wired in centrally so every module is
// covered without boilerplate.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.detekt) apply false
}

subprojects {
    // Applied dynamically rather than through each module's `plugins {}` block so that
    // every module is covered by one configuration.
    pluginManager.apply("org.jlleitschuh.gradle.ktlint")
    pluginManager.apply("io.gitlab.arturbosch.detekt")

    // The typed extension accessors (`ktlint { }`, `detekt { }`) are not available in the
    // root script for plugins applied this way, so they are configured explicitly by type.
    extensions.configure(KtlintExtension::class.java) {
        version.set("1.5.0")
        android.set(true)
        ignoreFailures.set(false)
        filter {
            exclude("**/build/**")
            exclude("**/generated/**")
            exclude("**/schemas/**")
        }
    }

    extensions.configure(DetektExtension::class.java) {
        buildUponDefaultConfig = true
        allRules = false
        config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
    }

    tasks.withType(io.gitlab.arturbosch.detekt.Detekt::class.java).configureEach {
        jvmTarget = "17"
        reports {
            html.required.set(true)
            xml.required.set(true)
            sarif.required.set(false)
            txt.required.set(false)
            md.required.set(false)
        }
    }
}
