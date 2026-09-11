package com.lookahead.learning.content.config;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.Profiles;

import java.nio.file.Path;
import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;

class EnvironmentConfigurationTest {
    @TempDir
    Path secretDirectory;

    @Test
    void standaloneConfigResolvesProfileSecretImportWithoutEnvironmentVariables() throws Exception {
        Path secrets = Files.createDirectory(secretDirectory.resolve("secrets"));
        Files.writeString(secrets.resolve("spring.datasource.password"), "synthetic-config-test-only");
        Path properties = secretDirectory.resolve("application.properties");
        Files.writeString(properties, "spring.profiles.active=local-accounts\n"
                + "LOOKAHEAD_SECRETS_DIRECTORY=" + secrets + "/\n"
                + "spring.config.import=configtree:" + secrets + "/\n"
                + "app.local-test.seed-enabled=true\n");
        new ApplicationContextRunner()
                .withPropertyValues("spring.config.additional-location=" + properties.toUri())
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var env = context.getEnvironment();
                    assertThat(env.getActiveProfiles()).contains("local-accounts", "accounts", "local-test");
                    assertThat(env.getProperty("spring.datasource.password")).isEqualTo("synthetic-config-test-only");
                    assertThat(env.getProperty("app.deployment-environment")).isEqualTo("local");
                });
    }

    private ApplicationContextRunner environment(String profile) {
        return new ApplicationContextRunner()
                .withPropertyValues("spring.profiles.active=" + profile,
                        "LOOKAHEAD_SECRETS_DIRECTORY=" + secretDirectory + "/")
                .withInitializer(new ConfigDataApplicationContextInitializer());
    }

    @Test
    void localAccountsExpandLocalTestProfilesWithoutEnablingSeeds() {
        environment("local-accounts").run(context -> {
            assertThat(context).hasNotFailed();
            var env = context.getEnvironment();
            assertThat(env.getActiveProfiles()).contains("local-accounts", "local", "accounts", "local-test");
            assertThat(env.getProperty("app.deployment-environment")).isEqualTo("local");
            assertThat(env.getProperty("app.local-test.seed-enabled", Boolean.class)).isFalse();
            assertThat(env.getProperty("server.servlet.session.cookie.secure", Boolean.class)).isFalse();
        });
    }

    @Test
    void sharedDevHasSecureCookiesWithoutSyntheticIdentity() {
        environment("dev").run(context -> {
            assertThat(context).hasNotFailed();
            var env = context.getEnvironment();
            assertThat(env.getActiveProfiles()).contains("dev", "accounts");
            assertThat(env.acceptsProfiles(Profiles.of("local", "local-test"))).isFalse();
            assertThat(env.getProperty("app.deployment-environment")).isEqualTo("development");
            assertThat(env.getProperty("app.local-test.seed-enabled", Boolean.class)).isFalse();
            assertThat(env.getProperty("server.servlet.session.cookie.secure", Boolean.class)).isTrue();
        });
    }

    @Test
    void productionExpandsAccountsWithSecureCookiesAndNoLocalLoginProfile() {
        environment("production").run(context -> {
            assertThat(context).hasNotFailed();
            var env = context.getEnvironment();
            assertThat(env.getActiveProfiles()).contains("production", "prod", "accounts");
            assertThat(env.acceptsProfiles(Profiles.of("local-test"))).isFalse();
            assertThat(env.getProperty("app.deployment-environment")).isEqualTo("production");
            assertThat(env.getProperty("app.local-test.seed-enabled", Boolean.class)).isFalse();
            assertThat(env.getProperty("server.servlet.session.cookie.secure", Boolean.class)).isTrue();
        });
    }

    @Test
    void configuredPoolControlsDoNotOverrideRuntimeRole() {
        environment("dev").withUserConfiguration(AccountDatabaseConfiguration.class)
                .withPropertyValues("spring.datasource.url=jdbc:postgresql://localhost:5432/not-connected",
                        "spring.datasource.username=lookahead_app", "spring.datasource.password=test-only-password",
                        "spring.datasource.hikari.maximum-pool-size=3",
                        "spring.datasource.hikari.connection-timeout=4000",
                        "spring.datasource.hikari.validation-timeout=1000",
                        "spring.datasource.hikari.username=postgres")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var source = context.getBean(HikariDataSource.class);
                    assertThat(source.getMaximumPoolSize()).isEqualTo(3);
                    assertThat(source.getConnectionTimeout()).isEqualTo(4000);
                    assertThat(source.getValidationTimeout()).isEqualTo(1000);
                    assertThat(source.getUsername()).isEqualTo("lookahead_app");
                });
    }
}
