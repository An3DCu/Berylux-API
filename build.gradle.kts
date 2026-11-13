import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.DetektCreateBaselineTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.detekt)
}

group = "com.anadcurukovic.berylux"
version = "0.1.0"

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
        // Spring's nullability annotations become Kotlin types.
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

dependencies {
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.oauth2.resource.server)
    implementation(libs.bucket4j.core)
    implementation(libs.caffeine)
    implementation(libs.springdoc.openapi.webmvc.ui)
    implementation(libs.spring.boot.starter.data.jdbc)
    implementation(libs.spring.boot.starter.flyway)
    implementation(libs.flyway.database.postgresql)
    runtimeOnly(libs.postgresql)
    runtimeOnly(libs.bouncycastle.bcprov)
    // bootRun starts compose.yaml's PostgreSQL first; not in tests or in the jar.
    developmentOnly(libs.spring.boot.docker.compose)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.kotlin.reflect)

    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.spring.security.test)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)

    detektPlugins(libs.detekt.formatting)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // ./gradlew test -PupdateSpec writes the OpenAPI spec to docs/openapi.yaml instead of checking it.
    systemProperty("updateSpec", project.hasProperty("updateSpec"))
    systemProperty("specFile", file("docs/openapi.yaml").absolutePath)
}

// Locally the API docs are on; anywhere else they're off. The API reads its settings from .env,
// as Docker Compose does.
tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    systemProperty("spring.profiles.active", "local")
    val dotEnv = file(".env")
    if (dotEnv.exists()) {
        dotEnv.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && "=" in it }
            .forEach { line -> environment(line.substringBefore("=").trim(), line.substringAfter("=").trim()) }
    }
}

// One jar, the runnable one; the Dockerfile copies it.
tasks.named<Jar>("jar") { enabled = false }

detekt {
    buildUponDefaultConfig = true
    parallel = true
    config.setFrom(file("config/detekt/detekt.yml"))
}

// Without this, detekt takes the JDK it runs on as the target, which can be newer than it knows.
tasks.withType<Detekt>().configureEach { jvmTarget = "21" }
tasks.withType<DetektCreateBaselineTask>().configureEach { jvmTarget = "21" }

// detekt 1.23 runs on the Kotlin version it was built with, not the one the API uses.
configurations.matching { it.name == "detekt" }.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.kotlin") useVersion(libs.versions.detektKotlin.get())
    }
}
