package com.bara.api

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class BaraApiApplication

fun main(args: Array<String>) {
    runApplication<BaraApiApplication>(*args)
}
