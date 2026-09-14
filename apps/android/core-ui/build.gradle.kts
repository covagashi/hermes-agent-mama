plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.roborazzi)
}

// Sistema de diseño "Hermes para mamá" (C1): MamaTheme + componentes grandes.
// A diferencia de los core-* JVM puros, éste es un módulo Android porque el tema
// y los componentes necesitan Compose; lo consumen :app y todos los feature-*.
android {
    namespace = "ai.hermes.mama.core.ui"
    compileSdk = 35 // ROADMAP §4: minSdk 29, targetSdk 35

    defaultConfig {
        minSdk = 29
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        // Robolectric/Roborazzi necesitan los recursos (fuentes, strings) en classpath.
        unitTests.isIncludeAndroidResources = true
    }

    lint {
        lintConfig = rootProject.file("config/lint.xml")
        warningsAsErrors = true
        abortOnError = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

// Referencias de capturas en apps/android/screenshots/ (ROADMAP §7.1):
//   ./gradlew :core-ui:recordRoborazzi        → graba/actualiza PNGs
//   ./gradlew :core-ui:verifyRoborazzi        → compara contra las referencias
roborazzi {
    outputDir.set(rootProject.layout.projectDirectory.dir("screenshots"))
}

dependencies {
    // api: los componentes exponen tipos de Compose (Modifier, ImageVector, Color…)
    // en su firma pública; los feature-* los necesitan en su classpath de compilación.
    api(platform(libs.compose.bom))
    api(libs.compose.ui)
    api(libs.compose.ui.graphics)
    api(libs.compose.material3)

    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material.icons.extended)

    debugImplementation(libs.compose.ui.tooling)
    // Declara ComponentActivity en el manifiesto debug → los tests Compose de
    // Robolectric (createAndroidComposeRule) pueden lanzarla.
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.activity.compose)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    // AccessibilityChecks.enable() (espresso-accessibility) + ATF directo en JVM.
    testImplementation(libs.espresso.core)
    testImplementation(libs.espresso.accessibility)
    testImplementation(libs.a11y.test.framework)
    testRuntimeOnly(libs.junit.vintage.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    // JUnit Platform: Jupiter (tests nuevos) + Vintage (JUnit4: Robolectric/Roborazzi).
    useJUnitPlatform()
    // Los filePath relativos de captureRoboImage se resuelven contra
    // roborazzi.output.dir (= screenshots/ al grabar/verificar), no contra el cwd.
    systemProperty(
        "roborazzi.record.filePathStrategy",
        "relativePathFromRoborazziContextOutputDirectory",
    )
}
