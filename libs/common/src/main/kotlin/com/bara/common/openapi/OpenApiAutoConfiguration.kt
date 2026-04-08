package com.bara.common.openapi

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

@AutoConfiguration
@ConditionalOnClass(OpenAPI::class)
@EnableConfigurationProperties(OpenApiProperties::class)
class OpenApiAutoConfiguration {

    @Bean
    fun openApi(props: OpenApiProperties): OpenAPI =
        OpenAPI()
            .info(
                Info()
                    .title(props.title)
                    .version(props.version)
                    .description(props.description)
            )
            .components(
                Components().addSecuritySchemes(
                    "bearer",
                    SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                )
            )
            .addSecurityItem(SecurityRequirement().addList("bearer"))
}
