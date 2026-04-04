plugins {
    id("org.jetbrains.kotlin.jvm")
}

group = "com.bara"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
}

tasks.withType<Test> {
    useJUnitPlatform()
}
