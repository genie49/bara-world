package com.bara.common.logging

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.context.annotation.Bean

@AutoConfiguration
class LoggingAutoConfiguration {

    @Bean
    fun correlationIdFilter(): CorrelationIdFilter = CorrelationIdFilter()

    @Bean
    fun requestLoggingFilter(): RequestLoggingFilter = RequestLoggingFilter()
}
