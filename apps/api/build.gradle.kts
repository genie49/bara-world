plugins {
    id("bara-spring-boot")
}

dependencies {
    implementation(project(":libs:common"))
    implementation(libs.kotlin.reflect)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.data.mongodb)
    implementation(libs.spring.boot.starter.data.redis)
    implementation(libs.spring.kafka)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.kafka.test)
    testImplementation(libs.mockk)
    testImplementation(libs.springmockk)
    testImplementation(kotlin("test"))
}

// ── E2E Test source set ──

val e2eTestSourceSet: SourceSet = sourceSets.create("e2eTest") {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}

configurations[e2eTestSourceSet.implementationConfigurationName]
    .extendsFrom(configurations.implementation.get())
configurations[e2eTestSourceSet.runtimeOnlyConfigurationName]
    .extendsFrom(configurations.runtimeOnly.get())

dependencies {
    "e2eTestImplementation"(project(":libs:common-test"))
    // e2eTest 에서 SSE 클라이언트 사용
    "e2eTestImplementation"(libs.okhttp)
    "e2eTestImplementation"(libs.okhttp.sse)
}

tasks.register<Test>("e2eTest") {
    description = "Runs E2E tests"
    group = "verification"
    testClassesDirs = e2eTestSourceSet.output.classesDirs
    classpath = e2eTestSourceSet.runtimeClasspath
    useJUnitPlatform()
}
