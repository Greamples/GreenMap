import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    application
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
    implementation(project(":protocol"))
    implementation(libs.gson)
    testImplementation(kotlin("test"))
}

application {
    mainClass = "org.greamples.greenmap.renderer.RenderCliKt"
}

val minecraftVersion = libs.versions.minecraft.get()
// Ванильный клиентский jar, который Loom уже скачал: из него строится единая палитра.
val vanillaClientJar = gradle.gradleUserHomeDir.resolve("caches/fabric-loom/$minecraftVersion/minecraft-client.jar")

tasks.test {
    useJUnitPlatform()
    systemProperty("greenmap.clientJar", vanillaClientJar.absolutePath)
}

// Рендер того, что выгрузил `./gradlew runExportRegion`, в PNG.
tasks.register<JavaExec>("renderExport") {
    group = "greenmap"
    description = "Renders build/export/*.gmap into build/export/png"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = application.mainClass
    val exportDir = rootProject.layout.buildDirectory.dir("export").get().asFile
    args("--client-jar", vanillaClientJar.absolutePath, "--out", exportDir.resolve("png").absolutePath, exportDir.absolutePath)
}
