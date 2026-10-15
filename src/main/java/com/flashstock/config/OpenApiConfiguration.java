package com.flashstock.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(info = @Info(title = "FlashStock API", version = "v1", description = "Transactional product inventory and reservation API"),
        security = @SecurityRequirement(name = "developmentBasic"))
@SecurityScheme(name = "developmentBasic", type = SecuritySchemeType.HTTP, scheme = "basic", description = "Development-only HTTP Basic authentication")
public class OpenApiConfiguration { }
