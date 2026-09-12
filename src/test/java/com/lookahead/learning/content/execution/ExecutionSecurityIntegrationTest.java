package com.lookahead.learning.content.execution;

import com.lookahead.learning.content.config.AccountSecurityConfig;
import com.lookahead.learning.content.controller.AuthController;
import com.lookahead.learning.content.dto.AccountView;
import com.lookahead.learning.content.security.AccountPrincipal;
import com.lookahead.learning.content.security.LocalAuthorAccess;
import com.lookahead.learning.content.service.AccountUserDetailsService;
import com.lookahead.learning.content.validator.LocalTestSeedGuard;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.test.context.ActiveProfiles;
import org.mockito.MockMakers;
import tools.jackson.databind.ObjectMapper;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/** Exercises the real session and CSRF filters; the broker stays disabled. */
@SpringBootTest(classes = ExecutionSecurityIntegrationTest.Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.name=security-test", "app.deployment-environment=local",
                "app.local-test.author-enabled=true", "app.local-test.seed-enabled=true",
                "app.local-test.seed-password=synthetic-execution-session-password", "app.execution.enabled=false"})
@ActiveProfiles({"accounts", "local-test"})
class ExecutionSecurityIntegrationTest {
    private static final UUID LEARNER_ID = UUID.fromString("7a4b2238-5cd3-4e70-82ca-606fdc90eb99");
    private static final String PASSWORD = "synthetic-execution-session-password";
    @LocalServerPort private int port;
    @Autowired private ObjectMapper mapper;

    @Configuration
    @EnableAutoConfiguration(excludeName = {
            "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
            "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"})
    @Import({AccountSecurityConfig.class, AuthController.class, LocalAuthorAccess.class,
            LocalTestSeedGuard.class, ExecutionConfiguration.class, ExecutionController.class})
    static class Application {
        @Bean AccountUserDetailsService users() {
            var users = mock(AccountUserDetailsService.class, withSettings().mockMaker(MockMakers.SUBCLASS));
            String hash = PasswordEncoderFactories.createDelegatingPasswordEncoder().encode(PASSWORD);
            when(users.loadUserByUsername(anyString())).thenAnswer(invocation -> {
                String username = invocation.getArgument(0);
                if (LocalAuthorAccess.USERNAME.equals(username)) {
                    return new AccountPrincipal(LocalAuthorAccess.ACCOUNT_ID, username, "Author", hash, true);
                }
                // A misleading display name must never grant the author capability.
                if ("learner@example.test".equals(username)) {
                    return new AccountPrincipal(LEARNER_ID, username, "Author", hash, true);
                }
                throw new UsernameNotFoundException("Invalid credentials");
            });
            when(users.accountView(any())).thenAnswer(invocation -> {
                AccountPrincipal principal = invocation.getArgument(0);
                return new AccountView(principal.accountId(), principal.getUsername(), principal.displayName(), Set.of());
            });
            return users;
        }
    }

    @Test void anonymousAndOrdinaryLearnersCannotReadExecutionCapabilities() throws Exception {
        var anonymous = client();
        assertThat(send(anonymous, "GET", "/api/v1/executions/capabilities", null, null, null).statusCode()).isEqualTo(401);
        var learner = login("learner@example.test");
        var response = send(learner, "GET", "/api/v1/executions/capabilities", null, null, LEARNER_ID);
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("EXECUTION_AUTHOR_REQUIRED");
    }

    @Test void authorCapabilityRetainsDisabledSchemaAndRejectsChangedAccount() throws Exception {
        var client = login(LocalAuthorAccess.USERNAME);
        var response = send(client, "GET", "/api/v1/executions/capabilities", null, null, LocalAuthorAccess.ACCOUNT_ID);
        assertThat(response.statusCode()).isEqualTo(200);
        var capability = mapper.readTree(response.body()).path("data");
        assertThat(capability.path("enabled").asBoolean()).isFalse();
        assertThat(capability.path("limits").path("sourceBytes").asInt()).isEqualTo(65536);
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(send(client, "GET", "/api/v1/executions/capabilities", null, null, LEARNER_ID).statusCode()).isEqualTo(409);
    }

    @Test void authorMutationsStillRequireFreshCsrf() throws Exception {
        var client = login(LocalAuthorAccess.USERNAME);
        assertThat(send(client, "POST", "/api/v1/executions/jobs", "{}", null, LocalAuthorAccess.ACCOUNT_ID).statusCode()).isEqualTo(403);
        String jobPath = "/api/v1/executions/jobs/" + UUID.randomUUID();
        assertThat(send(client, "DELETE", jobPath, "", null, LocalAuthorAccess.ACCOUNT_ID).statusCode()).isEqualTo(403);
        // With valid CSRF the request reaches the disabled execution boundary, not a compiler.
        assertThat(send(client, "DELETE", jobPath, "", token(client), LocalAuthorAccess.ACCOUNT_ID).statusCode()).isEqualTo(503);
    }

    private HttpClient client() {
        return HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .connectTimeout(Duration.ofSeconds(3)).build();
    }
    private HttpClient login(String username) throws Exception {
        var client = client();
        String body = "username=" + URLEncoder.encode(username, StandardCharsets.UTF_8)
                + "&password=" + URLEncoder.encode(PASSWORD, StandardCharsets.UTF_8);
        assertThat(send(client, "POST", "/api/v1/auth/login", body, token(client), null).statusCode()).isEqualTo(200);
        return client;
    }
    private String token(HttpClient client) throws Exception {
        return mapper.readTree(send(client, "GET", "/api/v1/auth/csrf", null, null, null).body())
                .path("data").path("token").asText();
    }
    private HttpResponse<String> send(HttpClient client, String method, String path, String body,
                                      String csrf, UUID owner) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(5));
        if (csrf != null) request.header("X-CSRF-TOKEN", csrf);
        if (owner != null) request.header("X-LookAhead-Account", owner.toString());
        if (body != null) request.header("Content-Type", path.endsWith("/login") ? "application/x-www-form-urlencoded" : "application/json");
        return client.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
