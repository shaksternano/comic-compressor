plugins {
    kotlin("jvm") version "1.9.23"
}

group = "io.github.shaksternano.comiccompressor"
version = "1.0.0"

repositories {
    mavenCentral()
    @Suppress("DEPRECATION")
    jcenter()
}

dependencies {
    implementation("commons-cli:commons-cli:1.7.0")
    implementation("com.fewlaps.slimjpg:slimjpg:1.3.3")
    implementation("me.tongfei:progressbar:0.10.1")

    testImplementation(kotlin("test"))
}

tasks {
    test {
        useJUnitPlatform()
    }
}

kotlin {
    jvmToolchain(17)
}
