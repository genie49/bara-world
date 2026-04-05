package com.bara.auth

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class BaraAuthApplication

fun main(args: Array<String>) {
    runApplication<BaraAuthApplication>(*args)
}
