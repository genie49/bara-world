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
    api(libs.springdoc.openapi.webmvc.ui)
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation(libs.logstash.logback.encoder)

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(libs.mockk)
}
