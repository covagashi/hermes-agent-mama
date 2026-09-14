import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.jlleitschuh.gradle.ktlint.KtlintExtension

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.room) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.roborazzi) apply false
}

// ktlint + detekt se aplican a TODOS los módulos desde la raíz (ROADMAP §4).
// La configuración vive en .editorconfig (ktlint) y config/detekt.yml (detekt).
val ktlintPluginId =
    libs.plugins.ktlint
        .get()
        .pluginId
val detektPluginId =
    libs.plugins.detekt
        .get()
        .pluginId
val ktlintVersion = libs.versions.ktlint

allprojects {
    apply(plugin = ktlintPluginId)
    apply(plugin = detektPluginId)

    extensions.configure<KtlintExtension> {
        version.set(ktlintVersion)
        android.set(true)
        outputToConsole.set(true)
        coloredOutput.set(true)
        ignoreFailures.set(false)
    }

    extensions.configure<DetektExtension> {
        buildUponDefaultConfig = true
        config.from(rootProject.layout.projectDirectory.file("config/detekt.yml"))
        parallel = true
    }
}
