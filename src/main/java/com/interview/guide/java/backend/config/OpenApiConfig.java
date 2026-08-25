package com.interview.guide.java.backend.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI interviewGuideOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Java Interview Guide API")
                .description("Backend services for the Java Interview Guide platform")
                .version("v1"));
    }
}
