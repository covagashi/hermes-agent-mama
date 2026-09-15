plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

// FakeGateway (WS de pruebas), fixtures y builders compartidos.
// B5 lo arranca también como proceso independiente con `./gradlew :testing:run`
// en 0.0.0.0:8399 (el emulador lo alcanza vía 10.0.2.2).
kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

application {
    mainClass.set("ai.hermes.mama.testing.Main")
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

// Los guiones JSON viven en testing/scripts/ (no en src/main/resources) para
// compartir directorio con fixtures/; aquí se empaquetan al classpath.
tasks.processResources {
    from("scripts") {
        into("scripts")
        include("*.json")
    }
}

// Atajos para elegir guion/bind sin `--args`:
//   ./gradlew :testing:run -Pscript=approval -Pport=8399 -Phost=0.0.0.0
tasks.named<JavaExec>("run") {
    val extraArgs =
        buildList {
            providers.gradleProperty("script").orNull?.let { add("--script=$it") }
            providers.gradleProperty("port").orNull?.let { add("--port=$it") }
            providers.gradleProperty("host").orNull?.let { add("--host=$it") }
        }
    if (extraArgs.isNotEmpty()) {
        args(extraArgs)
    }
}

dependencies {
    implementation(project(":core-contract"))
    implementation(project(":core-gateway"))

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coroutines.core)
    implementation(libs.okhttp)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.websockets)

    testImplementation(libs.mockwebserver)
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
