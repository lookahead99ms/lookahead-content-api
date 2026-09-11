package com.lookahead.learning.content.controller;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("prod")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.cors.allowed-origins[0]=http://localhost:4316")
class OperationalContractIntegrationTest {
    @LocalServerPort
    private int port;

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/health", "/actuator/health/readiness", "/actuator/health/liveness"})
    void exposesHealthyProbesWithoutInternalDetails(String path) throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri(path)).GET().build());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"")
                .doesNotContain("\"components\"", "\"details\"");
        if (!path.equals("/actuator/health")) {
            assertThat(response.body()).isEqualTo("{\"status\":\"UP\"}");
        }
    }

    @Test
    void allowsConfiguredFrontendPreflight() throws Exception {
        HttpResponse<String> response = preflight("http://localhost:4316");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin"))
                .contains("http://localhost:4316");
        assertThat(response.headers().firstValue("Access-Control-Allow-Credentials")).contains("true");
    }

    @Test
    void rejectsUnconfiguredFrontendPreflight() throws Exception {
        HttpResponse<String> response = preflight("https://untrusted.example");

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
    }

    private HttpResponse<String> preflight(String origin) throws Exception {
        return send(HttpRequest.newBuilder(uri("/api/v1/status"))
                .header("Origin", origin)
                .header("Access-Control-Request-Method", "GET")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .build());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }
}
