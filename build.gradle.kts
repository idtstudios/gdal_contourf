import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    application
    `java-library`
    `maven-publish`
    kotlin("jvm") version "1.3.50"
    id("com.github.johnrengelman.shadow") version "5.1.0"
    id("com.github.ben-manes.versions") version "0.27.0"
}

group = "com.github.brewin"
version = "1.2"

repositories {
    jcenter()
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib-jdk8"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.3.2")
    api("org.gdal:gdal:2.4.0")
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "1.8"
}

application {
    mainClassName = "com.github.brewin.gdal_contourf.GdalContourF"
}

/*tasks.withType<ShadowJar> {
    baseName = "gdal_contourf"
    classifier = ""
    version = ""
    destinationDir = File("dist")
}*/
