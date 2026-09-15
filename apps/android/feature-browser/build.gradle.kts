plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Pantalla Navegador: WebView visible + overlay de progreso (hito M5).
android {
    namespace = "ai.hermes.mama.feature.browser"
    compileSdk = 35 // ROADMAP §4: minSdk 29, targetSdk 35

    defaultConfig {
        minSdk = 29
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Los fixtures HTML de F1 viajan como assets del APK de tests: el test
    // instrumentado los carga en un WebView real (Robolectric no ejecuta JS).
    sourceSets["androidTest"].assets.srcDir("../testing/fixtures/html")

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

dependencies {
    implementation(project(":core-contract"))
    implementation(project(":core-controller"))

    implementation(platform(libs.compose.bom))
    implementation(libs.bundles.compose.ui)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.webkit)

    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.turbine)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
    // Vintage: permite tests JUnit4 en JVM (p. ej. Robolectric).
    testRuntimeOnly(libs.junit.vintage.engine)

    androidTestImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    // F2: fixtures HTML servidas por MockWebServer + JSON para leer resultados §2.6.
    androidTestImplementation(libs.mockwebserver)
    androidTestImplementation(libs.kotlinx.serialization.json)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
