plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

// SessionRepository + caché Room (B6). A diferencia de los core-* JVM puros
// (§1.1), éste es módulo Android: Room necesita Context y android.database.
// Los tests siguen siendo JVM gracias a Robolectric con Room in-memory (§5/B6).
android {
    namespace = "ai.hermes.mama.core.storage"
    compileSdk = 35 // ROADMAP §4: minSdk 29, targetSdk 35

    defaultConfig {
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        // Robolectric lee el manifest/recursos del binario en JVM.
        unitTests.isIncludeAndroidResources = true
    }

    lint {
        lintConfig = rootProject.file("config/lint.xml")
        warningsAsErrors = true
        abortOnError = true
    }
}

// Esquema exportado a core-storage/schemas/ (commiteado: base para migraciones).
room {
    schemaDirectory(project.file("schemas").absolutePath)
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    // api: SessionGateway expone DTOs del contrato (SessionListResult…) y
    // GatewayEvent en su firma; los consumidores los necesitan en classpath.
    api(project(":core-contract"))
    api(project(":core-gateway"))

    // api: las entidades/Daos son la superficie del módulo y su metaclass
    // referencia anotaciones de Room; Flow sale por la API del repositorio.
    api(libs.androidx.room.runtime)
    api(libs.coroutines.core)
    implementation(libs.androidx.room.ktx)
    implementation(libs.kotlinx.serialization.json)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit4)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    // Vintage: RobolectricTestRunner es JUnit 4 bajo JUnit Platform.
    testRuntimeOnly(libs.junit.vintage.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
