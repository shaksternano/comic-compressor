plugins {
    kotlin("jvm") version "2.0.21"
    id("com.gradleup.shadow") version "8.3.5"
    application
}

group = "io.github.shaksternano.comiccompressor"
version = "1.0.0"
base.archivesName.set("comic-compressor")

repositories {
    mavenCentral()
    maven("https://jitpack.io")
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("commons-cli:commons-cli:1.9.0")
    implementation("com.github.fewlaps:slim-jpg:1.3.3")
    implementation("me.tongfei:progressbar:0.10.1")

    testImplementation(kotlin("test"))
}

tasks {
    jar {
        enabled = false
    }

    shadowJar {
        archiveClassifier.set("")
        mergeServiceFiles()
        manifest {
            attributes(
                mapOf(
                    "Main-Class" to "${project.group}.MainKt",
                )
            )
        }
        dependsOn(distTar, distZip)
    }

    build {
        dependsOn(shadowJar)
    }

    test {
        useJUnitPlatform()
    }
}

application {
    mainClass.set("${project.group}.MainKt")
}

kotlin {
    jvmToolchain(17)
}
