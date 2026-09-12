package com.lookahead.learning.content.execution;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import tools.jackson.databind.ObjectMapper;

@Configuration
@Profile("accounts")
@EnableConfigurationProperties(ExecutionProperties.class)
public class ExecutionConfiguration {
    @Bean ExecutionBrokerClient executionBrokerClient(ExecutionProperties properties, Environment environment, ObjectMapper mapper) {
        if (properties.enabled() && !(environment.acceptsProfiles(Profiles.of("accounts & local-test"))
                && !environment.acceptsProfiles(Profiles.of("prod", "production"))
                && "local".equals(environment.getProperty("app.deployment-environment"))
                && environment.getProperty("app.local-test.author-enabled", Boolean.class, false))) {
            throw new IllegalStateException("Execution requires the explicitly enabled local author account profile");
        }
        return new ExecutionBrokerClient(properties, mapper);
    }
}
