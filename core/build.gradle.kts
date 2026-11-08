import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.1.0"
}

group = "fr.tachoperso"
version = "1.0"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// Lecture d'une vraie carte depuis un PC (PC/SC) : ./gradlew -p core dumpCard -Pout=chemin.DDD
tasks.register<JavaExec>("dumpCard") {
    group = "tacho"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("fr.tachoperso.core.PcscDump")
    args(project.findProperty("out")?.toString() ?: "carte.DDD")
}

tasks.register<JavaExec>("inspect") {
    group = "tacho"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("fr.tachoperso.core.InspectKt")
    args(project.findProperty("file")?.toString() ?: "carte.DDD")
}
