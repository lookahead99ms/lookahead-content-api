package com.lookahead.learning.content.security;

import com.lookahead.learning.content.service.AccountUserDetailsService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@SpringBootTest(classes = AccountSecurityIntegrationTest.SecurityTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.name=security-test", "app.deployment-environment=production"})
@ActiveProfiles({"accounts", "prod"})
class ProductionAccountSecurityIntegrationTest {
    @LocalServerPort private int port;
    @Autowired private ObjectMapper mapper;
    @Autowired private SecurityFilterChain accountSecurity;
    @Autowired private AccountUserDetailsService users;

    @Test
    void existingSyntheticCredentialsCannotAuthenticateOutsideLocalTest() throws Exception {
        assertThat(accountSecurity.getFilters()).noneMatch(UsernamePasswordAuthenticationFilter.class::isInstance);
        var client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        var csrf = client.send(HttpRequest.newBuilder(uri("/api/v1/auth/csrf")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(csrf.statusCode()).isEqualTo(200);
        String token = mapper.readTree(csrf.body()).path("data").path("token").asText();
        var login = client.send(HttpRequest.newBuilder(uri("/api/v1/auth/login"))
                .header("X-CSRF-TOKEN", token).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("username=learner01&password=synthetic-test-password")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(login.statusCode()).isEqualTo(401);
        assertThat(login.body()).contains("AUTHENTICATION_REQUIRED");
        verify(users, never()).loadUserByUsername(anyString());
        assertThat(client.send(HttpRequest.newBuilder(uri("/api/v1/auth/me")).GET().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
    }

    @Test
    void operationalHealthRemainsPublic() throws Exception {
        var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(uri("/actuator/health")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("UP");
    }

    private URI uri(String path) { return URI.create("http://localhost:" + port + path); }
}
