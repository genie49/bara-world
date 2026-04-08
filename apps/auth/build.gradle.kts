import org.springframework.boot.gradle.tasks.run.BootRun

plugins {
    id("bara-spring-boot")
}

dependencies {
    implementation(project(":libs:common"))
    implementation(libs.kotlin.reflect)
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.data.mongodb)
    implementation(libs.spring.boot.starter.data.redis)
    implementation(libs.java.jwt)
    implementation(libs.google.api.client)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.mockk)
    testImplementation(libs.springmockk)
}

// ── e2eTest source set ────────────────────────────────────────────
val e2eTestSourceSet = sourceSets.create("e2eTest") {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}

configurations[e2eTestSourceSet.implementationConfigurationName].extendsFrom(configurations.implementation.get())
configurations[e2eTestSourceSet.runtimeOnlyConfigurationName].extendsFrom(configurations.runtimeOnly.get())

dependencies {
    "e2eTestImplementation"(project(":libs:common-test"))
}

tasks.register<Test>("e2eTest") {
    description = "Runs E2E tests"
    group = "verification"
    testClassesDirs = e2eTestSourceSet.output.classesDirs
    classpath = e2eTestSourceSet.runtimeClasspath
    useJUnitPlatform()
    // Allow reflection access to java.net.HttpURLConnection for PATCH method support
    jvmArgs("--add-opens", "java.base/java.net=ALL-UNNAMED")
}
// ──────────────────────────────────────────────────────────────────

tasks.named<BootRun>("bootRun") {
    val envFile = rootProject.file(".env")
    if (envFile.exists()) {
        envFile.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .forEach { line ->
                val idx = line.indexOf('=')
                if (idx > 0) {
                    val key = line.substring(0, idx).trim()
                    val value = line.substring(idx + 1).trim().trim('"', '\'')
                    environment(key, value)
                }
            }
    }
}
