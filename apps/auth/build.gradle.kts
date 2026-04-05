plugins {
    id("bara-spring-boot")
}

dependencies {
    implementation(project(":libs:common"))
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.data.mongodb)
    implementation(libs.spring.boot.starter.data.redis)
    implementation(libs.java.jwt)
    implementation(libs.google.api.client)
    implementation(libs.spring.dotenv)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.mockk)
}
