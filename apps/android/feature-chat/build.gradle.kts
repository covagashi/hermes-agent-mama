plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.roborazzi)
}

// Pantallas Chats + Chat + Aprobaciones/Clarify (hito M2).
android {
    namespace = "ai.hermes.mama.feature.chat"
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

// Referencias de capturas en apps/android/screenshots/ (ROADMAP §7.1), igual
// que en :core-ui:
//   ./gradlew :feature-chat:recordRoborazzi  → graba/actualiza PNGs
//   ./gradlew :feature-chat:verifyRoborazzi  → compara contra las referencias
roborazzi {
    outputDir.set(rootProject.layout.projectDirectory.dir("screenshots"))
}

dependencies {
    implementation(project(":core-contract"))
    implementation(project(":core-gateway"))
    implementation(project(":core-ui"))

    implementation(libs.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    // E2: FileAttacher (suspend + Dispatchers inyectados) y logs §8.
    implementation(libs.coroutines.core)
    implementation(libs.timber)
    // E1: lectura de EXIF puro-Java (funciona también bajo Robolectric).
    implementation(libs.androidx.exifinterface)

    implementation(platform(libs.compose.bom))
    implementation(libs.bundles.compose.ui)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.turbine)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
    // E2: el path android.net.Uri/ContentResolver de FileAttacher se prueba con
    // Robolectric (JUnit4); la lógica pura corre con JUnit 5 sin Android.
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    // E1: ImageAttacher se prueba en JVM con sombras de Android (BitmapFactory,
    // ContentResolver/provider, ExifInterface) — Robolectric corre con JUnit4.
    testImplementation(libs.androidx.test.ext.junit)
    // E1: el transport fake de los tests inspecciona los frames JSON-RPC.
    testImplementation(libs.kotlinx.serialization.json)
    testRuntimeOnly(libs.junit.platform.launcher)
    // Vintage: permite tests JUnit4 en JVM (p. ej. Robolectric/Room de B6).
    testRuntimeOnly(libs.junit.vintage.engine)

    // Robolectric + Compose UI Test + Roborazzi (misma pila que :core-ui):
    // capturas a 390 dp, AccessibilityChecks y fuente 2.0×.
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.activity.compose)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.espresso.core)
    testImplementation(libs.espresso.accessibility)
    testImplementation(libs.a11y.test.framework)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // Los filePath relativos de captureRoboImage se resuelven contra
    // roborazzi.output.dir (= screenshots/ al grabar/verificar), no el cwd.
    systemProperty(
        "roborazzi.record.filePathStrategy",
        "relativePathFromRoborazziContextOutputDirectory",
    )
}
