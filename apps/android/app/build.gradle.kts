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

android {
    namespace = "ai.hermes.mama"
    compileSdk = 35 // ROADMAP §4: minSdk 29, targetSdk 35

    defaultConfig {
        applicationId = "ai.hermes.mama"
        minSdk = 29
        targetSdk = 35
        versionCode = ciVersionCode
        versionName = "0.0.1"

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

    buildTypes {
        release {
            // R8/ProGuard y la firma por secretos de CI llegan en J1/J2.
            isMinifyEnabled = false
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
    implementation(project(":feature-chat"))
    implementation(project(":feature-browser"))
    implementation(project(":feature-voice"))
    implementation(project(":feature-settings"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.timber)

    implementation(platform(libs.compose.bom))
    implementation(libs.bundles.compose.ui)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.rules)
    testImplementation(project(":testing"))
}

tasks.withType<Test>().configureEach {
    // Robolectric (SmokeTest) corre con JUnit4: las reglas oficiales de Compose UI Test
    // son junit4. Los módulos JVM/feature usan JUnit 5 (useJUnitPlatform).
    useJUnit()
}
