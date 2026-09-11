package com.lookahead.learning.content.security;

import com.lookahead.learning.content.config.AccountSecurityConfig;
import com.lookahead.learning.content.controller.AuthController;
import com.lookahead.learning.content.dto.AccountView;
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
import tools.jackson.databind.ObjectMapper;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;
import org.mockito.MockMakers;
import static org.mockito.Mockito.when;

@SpringBootTest(classes = AccountSecurityIntegrationTest.SecurityTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.name=security-test", "server.servlet.session.cookie.http-only=true",
                "server.servlet.session.cookie.same-site=lax", "app.deployment-environment=local"})
@ActiveProfiles({"accounts", "local-test"})
class AccountSecurityIntegrationTest {
    private static final UUID ACCOUNT_ID = UUID.fromString("53b362b5-57a6-41f1-8e8d-c413160e142a");
    @LocalServerPort private int port;
    @Autowired private ObjectMapper mapper;

    @Configuration
    @EnableAutoConfiguration(excludeName = {
            "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
            "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"})
    @Import({AccountSecurityConfig.class, AuthController.class, com.lookahead.learning.content.security.LocalAuthorAccess.class, LocalTestSeedGuard.class})
    static class SecurityTestApplication {
        @Bean AccountUserDetailsService users() {
            var users = mock(AccountUserDetailsService.class, withSettings().mockMaker(MockMakers.SUBCLASS));
            String hash = PasswordEncoderFactories.createDelegatingPasswordEncoder().encode("synthetic-test-password");
            when(users.loadUserByUsername(anyString())).thenAnswer(invocation -> {
                if (!"learner01".equals(invocation.getArgument(0))) throw new UsernameNotFoundException("Invalid credentials");
                return new AccountPrincipal(ACCOUNT_ID, "learner01", "Synthetic Learner 01", hash, true);
            });
            when(users.accountView(any())).thenAnswer(invocation -> {
                AccountPrincipal principal = invocation.getArgument(0);
                return new AccountView(principal.accountId(), principal.getUsername(),
                        principal.displayName(), Set.of("learn:hands-on-dsa"));
            });
            return users;
        }
    }

    @Test
    void csrfTokenIsPublicButMeRequiresAuthentication() throws Exception {
        var client = client();
        var csrf = request(client, "GET", "/csrf", null, null);
        assertThat(csrf.statusCode()).isEqualTo(200);
        assertThat(csrf.body()).contains("X-CSRF-TOKEN", "parameterName");
        assertThat(csrf.headers().allValues("set-cookie").toString()).contains("HttpOnly", "SameSite=Lax");
        var me = request(client, "GET", "/me", null, null);
        assertThat(me.statusCode()).isEqualTo(401);
        assertThat(me.body()).contains("AUTHENTICATION_REQUIRED");
    }

    @Test
    void missingCsrfAndInvalidCredentialsAreRejectedWithoutRedirect() throws Exception {
        var client = client();
        var missing = request(client, "POST", "/login", "username=learner01&password=synthetic-test-password", null);
        assertThat(missing.statusCode()).isEqualTo(403);
        assertThat(missing.body()).contains("CSRF_INVALID");
        var invalid = request(client, "POST", "/login", "username=learner01&password=wrong", token(client));
        assertThat(invalid.statusCode()).isEqualTo(401);
        assertThat(invalid.body()).contains("INVALID_CREDENTIALS");
        assertThat(request(client, "GET", "/me", null, null).statusCode()).isEqualTo(401);
    }

    @Test
    void loginRotatesSessionAndCsrfThenLogoutInvalidatesSession() throws Exception {
        var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        var client = HttpClient.newBuilder().cookieHandler(cookies).build();
        String beforeToken = token(client);
        String beforeSession = sessionId(cookies);
        var login = request(client, "POST", "/login", "username=learner01&password=synthetic-test-password", beforeToken);
        assertThat(login.statusCode()).isEqualTo(200);
        assertThat(login.body()).contains(ACCOUNT_ID.toString(), "topicGrants").doesNotContain("password");
        assertThat(sessionId(cookies)).isNotEqualTo(beforeSession);
        assertThat(request(client, "GET", "/me", null, null).statusCode()).isEqualTo(200);
        assertThat(request(client, "POST", "/logout", "", beforeToken).statusCode()).isEqualTo(403);
        var logout = request(client, "POST", "/logout", "", token(client));
        assertThat(logout.statusCode()).isEqualTo(204);
        assertThat(logout.body()).isEmpty();
        assertThat(request(client, "GET", "/me", null, null).statusCode()).isEqualTo(401);
    }

    @Test
    void anotherBrowserDoesNotInheritAuthenticatedSession() throws Exception {
        var first = client();
        assertThat(request(first, "POST", "/login", "username=learner01&password=synthetic-test-password", token(first))
                .statusCode()).isEqualTo(200);
        assertThat(request(client(), "GET", "/me", null, null).statusCode()).isEqualTo(401);
    }

    private HttpClient client() {
        return HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
    }

    private String token(HttpClient client) throws Exception {
        return mapper.readTree(request(client, "GET", "/csrf", null, null).body()).path("data").path("token").asText();
    }

    private String sessionId(CookieManager cookies) {
        return cookies.getCookieStore().getCookies().stream().filter(cookie -> cookie.getName().equals("JSESSIONID"))
                .findFirst().orElseThrow().getValue();
    }

    private HttpResponse<String> request(HttpClient client, String method, String path, String body, String token) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/auth" + path));
        if (token != null) builder.header("X-CSRF-TOKEN", token);
        if (body != null) builder.header("Content-Type", "application/x-www-form-urlencoded");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
