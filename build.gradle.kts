plugins {
    kotlin("jvm") version "1.9.23"
    id("com.github.johnrengelman.shadow") version "8.1.1"
    application
}

group = "io.github.shaksternano.comiccompressor"
version = "1.0.0"
base.archivesName.set("comic-compressor")

repositories {
    mavenCentral()
    @Suppress("DEPRECATION")
    jcenter()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
    implementation("commons-cli:commons-cli:1.7.0")
    implementation("com.fewlaps.slimjpg:slimjpg:1.3.3")
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
