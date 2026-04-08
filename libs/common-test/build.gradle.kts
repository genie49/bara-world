plugins {
    id("bara-kotlin-library")
    alias(libs.plugins.spring.dependency.management)
}

dependencyManagement {
    imports {
        mavenBom(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES)
    }
}

dependencies {
    api(libs.testcontainers.core)
    api(libs.testcontainers.junit.jupiter)
    api("org.springframework.boot:spring-boot-starter-test")
    implementation("org.springframework.data:spring-data-mongodb")
    implementation("org.mongodb:mongodb-driver-sync")
}
