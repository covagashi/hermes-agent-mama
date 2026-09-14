plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// Lógica del controlador de navegador: snapshot builder + mapeo de acciones (M5).
// JVM puro; el JS del snapshot vive en js/ con su suite Node/jsdom (F1).
//
// `src/main/assets/hermes_snapshot.js` se publica además en el classpath (raíz
// del jar) para que la app lo cargue con `SnapshotScript.load()` sin depender de
// Android assets — core-controller es un módulo JVM puro.
kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

sourceSets {
    main {
        resources.srcDir("src/main/assets")
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

dependencies {
    implementation(project(":core-contract"))

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coroutines.core)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.turbine)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
