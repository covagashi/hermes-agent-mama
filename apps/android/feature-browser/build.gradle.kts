plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.roborazzi)
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

// Referencias de capturas en apps/android/screenshots/ (ROADMAP §7.1), igual
// que en :feature-chat:
//   ./gradlew :feature-browser:recordRoborazzi  → graba/actualiza PNGs
//   ./gradlew :feature-browser:verifyRoborazzi  → compara contra las referencias
roborazzi {
    outputDir.set(rootProject.layout.projectDirectory.dir("screenshots"))
}

dependencies {
    implementation(project(":core-contract"))
    implementation(project(":core-controller"))
    implementation(project(":core-gateway"))
    implementation(project(":core-ui"))

    implementation(libs.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    // G1: NotificationCompat/NotificationChannelCompat para el aviso de sistema.
    implementation(libs.androidx.core.ktx)
    // G1: re-descarga del DownloadListener con cookies/UA del WebView.
    implementation(libs.okhttp)

    implementation(platform(libs.compose.bom))
    implementation(libs.bundles.compose.ui)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.androidx.activity.compose)
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

    // F4 JVM: BrowserPaneController contra FakeGateway por sockets reales —
    // misma pila que ControllerSessionGatewayTest en :core-controller.
    testImplementation(project(":testing"))
    testImplementation(libs.okhttp)

    // Robolectric + Compose UI Test + Roborazzi (misma pila que :core-ui y
    // :feature-chat): capturas a 390 dp, AccessibilityChecks y fuente 2.0×.
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.activity.compose)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.espresso.core)
    testImplementation(libs.espresso.accessibility)
    testImplementation(libs.a11y.test.framework)

    androidTestImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    // F2: fixtures HTML servidas por MockWebServer + JSON para leer resultados §2.6.
    androidTestImplementation(libs.mockwebserver)
    androidTestImplementation(libs.kotlinx.serialization.json)
    // F4: flujo E2E con FakeGateway empotrado — misma pila que feature-chat:
    // la pantalla real en Compose + WebSocketTransport → GatewayClient.
    androidTestImplementation(libs.androidx.activity.compose)
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.espresso.accessibility)
    androidTestImplementation(libs.a11y.test.framework)
    // WebSocketTransport expone tipos okhttp en su firma (CookieJar/OkHttpClient).
    androidTestImplementation(libs.okhttp)
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
