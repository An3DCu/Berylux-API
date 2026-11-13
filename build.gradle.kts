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
    implementation(libs.jackson.module.kotlin)
    implementation(libs.kotlin.reflect)

    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.kotlin.test.junit5)
    testRuntimeOnly(libs.junit.platform.launcher)

    detektPlugins(libs.detekt.formatting)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

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
