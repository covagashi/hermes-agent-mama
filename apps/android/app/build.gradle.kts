import java.util.Base64

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// versionCode = número de run de CI (ROADMAP §4); en local siempre es 1.
val ciVersionCode =
    providers
        .environmentVariable("GITHUB_RUN_NUMBER")
        .map(String::toInt)
        .getOrElse(1)

// ── Firma release (ROADMAP §5, tarea J1) ─────────────────────────────────────
// El keystore de release NUNCA vive en el repo (AGENTS.md: repo público). Llega
// por variables de entorno, en cualquiera de estas dos formas:
//   ANDROID_KEYSTORE_FILE — ruta a un .jks ya materializado. Así lo hace CI:
//       decodifica ANDROID_KEYSTORE_B64 en $RUNNER_TEMP y pasa la ruta. En local
//       puede apuntar a un keystore de prueba fuera del repo (p. ej. /tmp/…).
//   ANDROID_KEYSTORE_B64 — el .jks codificado en base64; Gradle lo materializa
//       en build/signing/release-keystore.jks (build/ está en .gitignore).
// junto a ANDROID_KEYSTORE_PASSWORD, ANDROID_KEY_ALIAS y ANDROID_KEY_PASSWORD.
// Si falta cualquiera, el buildType release firma con el signingConfig de debug
// (fallback documentado): el APK/AAB queda firmado e instalable por sideload,
// pero NO es apto para Google Play.
val signingEnv = { name: String ->
    providers.environmentVariable(name).orNull?.takeIf(String::isNotBlank)
}

val releaseStoreFile: File? =
    signingEnv("ANDROID_KEYSTORE_FILE")?.let(::file)
        ?: signingEnv("ANDROID_KEYSTORE_B64")?.let { b64 ->
            val keystore =
                layout.buildDirectory
                    .dir("signing")
                    .get()
                    .asFile
                    .apply { mkdirs() }
                    .resolve("release-keystore.jks")
            val bytes = Base64.getMimeDecoder().decode(b64) // MIME: tolera saltos de línea
            if (!keystore.exists() || !keystore.readBytes().contentEquals(bytes)) {
                keystore.writeBytes(bytes)
            }
            keystore
        }

val releaseSigningAvailable =
    releaseStoreFile != null &&
        signingEnv("ANDROID_KEYSTORE_PASSWORD") != null &&
        signingEnv("ANDROID_KEY_ALIAS") != null &&
        signingEnv("ANDROID_KEY_PASSWORD") != null

android {
    namespace = "ai.hermes.mama"
    compileSdk = 35 // ROADMAP §4: minSdk 29, targetSdk 35

    defaultConfig {
        applicationId = "ai.hermes.mama"
        minSdk = 29
        targetSdk = 35
        versionCode = ciVersionCode
        // ROADMAP §4: versionName = 0.<hito>.<n> — hito M9, n = run de CI.
        versionName = "0.9.$ciVersionCode"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    flavorDimensions += "app"
    productFlavors {
        // dev: desarrollo y CI. Admite el extra `fake_script` (ver DevGateway) y
        // cleartext hacia el FakeGateway del emulador (ws://10.0.2.2:8399).
        create("dev") {
            applicationIdSuffix = ".dev"
            buildConfigField("String", "FAKE_GATEWAY_ENDPOINT", "\"ws://10.0.2.2:8399\"")
        }
        // mama: la usuaria. Sin override de endpoint ni cleartext.
        create("mama") {
            buildConfigField("String", "FAKE_GATEWAY_ENDPOINT", "\"\"")
        }
    }

    signingConfigs {
        // Sólo se crea cuando están TODAS las variables de entorno (ver cabecera
        // del fichero); si falta alguna, release cae a la firma de debug.
        if (releaseSigningAvailable) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = signingEnv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = signingEnv("ANDROID_KEY_ALIAS")
                keyPassword = signingEnv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // R8/ProGuard llega en J2 (endurecimiento).
            isMinifyEnabled = false
            // Fallback J1: sin secretos de firma, release se firma con la clave
            // de debug — instalable por sideload, no apto para Google Play.
            signingConfig =
                if (releaseSigningAvailable) {
                    signingConfigs.getByName("release")
                } else {
                    signingConfigs.getByName("debug")
                }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        // Robolectric necesita los recursos del binario en classpath.
        unitTests.isIncludeAndroidResources = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
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
    implementation(project(":core-gateway"))
    implementation(project(":core-controller"))
    // C4: el host dev (DevChatHost) monta SessionRepository + Room por generación.
    implementation(project(":core-storage"))
    implementation(project(":core-ui"))
    implementation(project(":feature-chat"))
    implementation(project(":feature-browser"))
    implementation(project(":feature-voice"))
    implementation(project(":feature-settings"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.coroutines.android)
    implementation(libs.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.timber)
    // El verifier de C2 (feature-settings) expone OkHttpClient/HttpUrl en su firma.
    implementation(libs.okhttp)

    implementation(platform(libs.compose.bom))
    implementation(libs.bundles.compose.ui)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.rules)
    testImplementation(project(":testing"))
    testRuntimeOnly(libs.junit.vintage.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    // JUnit Platform con ambos motores: Jupiter (JUnit 5, tests nuevos) y Vintage
    // (JUnit 4: SmokeTest de Robolectric y las reglas oficiales de Compose UI Test).
    useJUnitPlatform()
}
