import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
}

group = "org.greamples"
version = rootProject.version

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

kotlin {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_25)
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
