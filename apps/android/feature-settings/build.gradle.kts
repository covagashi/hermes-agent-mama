plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.roborazzi)
}

// Pantalla Conexión (servidor, usuario, contraseña) — C2.
android {
    namespace = "ai.hermes.mama.feature.settings"
    compileSdk = 35 // ROADMAP §4: minSdk 29, targetSdk 35

    defaultConfig {
        minSdk = 29
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

// Capturas de la pantalla Conexión en apps/android/screenshots/ (ROADMAP §7.1):
//   ./gradlew :feature-settings:recordRoborazzi  → graba/actualiza PNGs
//   ./gradlew :feature-settings:verifyRoborazzi  → compara contra las referencias
roborazzi {
    outputDir.set(rootProject.layout.projectDirectory.dir("screenshots"))
}

dependencies {
    implementation(project(":core-contract"))
    implementation(project(":core-gateway"))
    implementation(project(":core-ui"))
    // C2: ReadAloudSetting (la preferencia "Leer en voz alta" que consume D2).
    implementation(project(":feature-voice"))

    implementation(platform(libs.compose.bom))
    implementation(libs.bundles.compose.ui)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)
    // C2: HttpUrl del servidor normalizado / OkHttpClient inyectable del verifier.
    implementation(libs.okhttp)
    implementation(libs.coroutines.core)

    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.junit4)
    testImplementation(libs.turbine)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
    // Robolectric: el contrato del SecureStore (B3) se testea en JVM con prefs reales.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    // C2: capturas Roborazzi de la pantalla Conexión (src/test, Robolectric).
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testRuntimeOnly(libs.junit.platform.launcher)
    // Vintage: permite tests JUnit4 en JVM (p. ej. Robolectric).
    testRuntimeOnly(libs.junit.vintage.engine)
    // ComponentActivity la declara src/test/AndroidManifest.xml para todas las
    // variantes (convención de :core-ui) — no hace falta compose.ui-test-manifest.

    // El camino cifrado del SecureStore (AndroidKeyStore) sólo existe en
    // dispositivo/emulador — vive en src/androidTest (tarea B3).
    androidTestImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    // C2: tests de la pantalla Conexión contra el FakeGateway (compose UI test).
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.activity.compose)
    // C2: el FakeGateway de B5 se empotra en el APK de tests (se arranca en
    // @Before contra un puerto efímero de loopback — ROADMAP §5/B5).
    androidTestImplementation(project(":testing"))
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
