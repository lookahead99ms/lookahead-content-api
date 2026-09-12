package com.lookahead.learning.content.execution;

import com.lookahead.learning.content.security.AccountPrincipal;
import com.lookahead.learning.content.security.LocalAuthorAccess;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;

class ExecutionAdapterTest {
    private final ObjectMapper mapper = JsonMapper.builder().build();
    private static final String TOKEN = "synthetic-execution-broker-token-for-testing";
    private final AccountPrincipal author = new AccountPrincipal(LocalAuthorAccess.ACCOUNT_ID, LocalAuthorAccess.USERNAME, "Author", "unused", true);
    private MockEnvironment local() {
        var environment = new MockEnvironment().withProperty("app.deployment-environment", "local")
                .withProperty("app.local-test.author-enabled", "true");
        environment.setActiveProfiles("accounts", "local-test");
        return environment;
    }
    private MockHttpServletRequest request() {
        var request = new MockHttpServletRequest();
        request.addHeader("X-LookAhead-Account", author.accountId().toString());
        request.addHeader("Idempotency-Key", UUID.randomUUID().toString());
        request.addHeader("Authorization", "Bearer untrusted-browser-token");
        request.addHeader("X-Execution-Owner", UUID.randomUUID().toString());
        request.addHeader("Cookie", "secret-browser-cookie");
        return request;
    }
    private ExecutionController controller(ExecutionProperties properties, MockEnvironment environment) {
        return new ExecutionController(new LocalAuthorAccess(environment), properties,
                new ExecutionConfiguration().executionBrokerClient(properties, environment, mapper), mapper);
    }
    private byte[] body(String source) {
        return mapper.writeValueAsBytes(Map.of("problemId", "algorithmic-two-sum", "language", "python", "source", source,
                "contractVersion", "two-sum/v1", "fixtureVersion", "visible/v1"));
    }
    private void fails(Runnable action, int status) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ExecutionFailure.class, failure -> assertThat(failure.status).isEqualTo(status));
    }

    @Test void disabledAuthorCapabilityAndOwnershipGuardsDoNotReachBroker() {
        var controller = controller(new ExecutionProperties(false, null, null), local());
        String disabled = mapper.writeValueAsString(controller.capabilities(author, request()).getBody());
        assertThat(disabled).contains("\"enabled\":false", "\"sourceBytes\":65536");
        var other = new AccountPrincipal(UUID.randomUUID(), "author", "Author", "unused", true);
        fails(() -> controller.capabilities(other, request()), 403);
        fails(() -> controller.capabilities(null, request()), 403);
        var mismatch = request(); mismatch.removeHeader("X-LookAhead-Account"); mismatch.addHeader("X-LookAhead-Account", UUID.randomUUID().toString());
        fails(() -> controller.capabilities(author, mismatch), 409);
        var missing = request(); missing.removeHeader("X-LookAhead-Account");
        fails(() -> controller.capabilities(author, missing), 400);
        fails(() -> controller.status("../../secrets", author, request()), 400);
        fails(() -> controller.cancel(UUID.randomUUID().toString(), author, request()), 503);
    }

    @Test void productionAndUnsafeOriginsFailClosedAndSecretsAreRedacted() {
        for (String value : new String[]{"http://evil.example:4340", "http://127.0.0.1:4340/path", "http://token@localhost:4340", "file:///tmp/socket", "http:localhost", "http://localhost:65536"}) {
            assertThatThrownBy(() -> new ExecutionProperties(true, URI.create(value), TOKEN)).isInstanceOf(IllegalStateException.class);
        }
        for (String token : new String[]{TOKEN + "\u0001", TOKEN + "é", TOKEN + "\n", "short"}) {
            assertThatThrownBy(() -> new ExecutionProperties(true, URI.create("http://127.0.0.1:4340"), token)).isInstanceOf(IllegalStateException.class);
        }
        var properties = new ExecutionProperties(true, URI.create("http://127.0.0.1:4340"), TOKEN);
        assertThat(properties.toString()).doesNotContain(TOKEN);
        var production = local(); production.setActiveProfiles("accounts", "local-test", "production");
        assertThatThrownBy(() -> controller(properties, production)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> controller(properties, local().withProperty("app.local-test.author-enabled", "false"))).isInstanceOf(IllegalStateException.class);
    }

    @Test void realHttpForwardingUsesOnlyTrustedOwnerTokenAndBoundedJobContract() throws Exception {
        var captured = new AtomicReference<com.sun.net.httpserver.Headers>();
        var source = new AtomicReference<String>(); var path = new AtomicReference<String>();
        var count = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/", exchange -> {
            captured.set(exchange.getRequestHeaders()); path.set(exchange.getRequestURI().toString()); count.incrementAndGet();
            source.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"schemaVersion\":\"execution/v1\",\"enabled\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response); exchange.close();
        });
        server.start();
        try {
            var properties = new ExecutionProperties(true, URI.create("http://127.0.0.1:" + server.getAddress().getPort()), TOKEN);
            var controller = controller(properties, local()); var request = request(); request.setContent(body("def two_sum(nums, target): return []"));
            controller.submit(author, request);
            assertThat(path.get()).isEqualTo("/v1/jobs");
            assertThat(source.get()).contains("two_sum");
            assertThat(captured.get().getFirst("Authorization")).isEqualTo("Bearer " + TOKEN);
            assertThat(captured.get().getFirst("X-Execution-Owner")).isEqualTo(author.accountId().toString());
            assertThat(captured.get().getFirst("Cookie")).isNull();
            assertThat(captured.get().getFirst("Idempotency-Key")).isEqualTo(request.getHeader("Idempotency-Key"));
            var tooBig = request(); tooBig.setContent(body("é".repeat(32769))); fails(() -> controller.submit(author, tooBig), 413);
            var envelope = request(); envelope.setContent(new byte[ExecutionBrokerClient.MAX_BYTES + 1]); fails(() -> controller.submit(author, envelope), 413);
            var wrong = request(); wrong.setContent("{\"source\":\"x\"}".getBytes()); fails(() -> controller.submit(author, wrong), 400);
            var key = request(); key.removeHeader("Idempotency-Key"); key.setContent(body("x")); fails(() -> controller.submit(author, key), 400);
            assertThat(count.get()).isEqualTo(1);
            controller.status(UUID.randomUUID().toString(), author, request());
            controller.cancel(UUID.randomUUID().toString(), author, request());
            assertThat(count.get()).isEqualTo(3);
        } finally { server.stop(0); }
    }

    @Test void brokerErrorsAndOversizedResponsesAreSafe() throws Exception {
        var status = new AtomicInteger(409);
        var payload = new AtomicReference<>("private diagnostics token".getBytes());
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/capabilities", exchange -> {
            byte[] bytes = payload.get(); exchange.sendResponseHeaders(status.get(), bytes.length);
            try { exchange.getResponseBody().write(bytes); } finally { exchange.close(); }
        });
        server.start();
        try {
            var controller = controller(new ExecutionProperties(true, URI.create("http://127.0.0.1:" + server.getAddress().getPort()), TOKEN), local());
            for (int value : new int[]{404, 409, 413, 429}) { status.set(value); fails(() -> controller.capabilities(author, request()), value); }
            status.set(401); fails(() -> controller.capabilities(author, request()), 503);
            status.set(200); payload.set(new byte[ExecutionBrokerClient.MAX_BYTES + 1]); fails(() -> controller.capabilities(author, request()), 503);
            payload.set("not json".getBytes()); fails(() -> controller.capabilities(author, request()), 503);
            assertThat(mapper.writeValueAsString(controller.failure(new ExecutionFailure(503, "EXECUTION_UNAVAILABLE")).getBody())).doesNotContain(TOKEN, "private diagnostics");
        } finally { server.stop(0); }
    }

    @Test void entireSlowDrippingResponseHasTenSecondDeadline() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/capabilities", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try {
                for (int i = 0; i < 30; i++) { exchange.getResponseBody().write(' '); exchange.getResponseBody().flush(); Thread.sleep(500); }
            } catch (Exception ignored) { /* the timed out client closes its stream */ }
            finally { exchange.close(); }
        });
        server.start();
        try {
            var controller = controller(new ExecutionProperties(true, URI.create("http://127.0.0.1:" + server.getAddress().getPort()), TOKEN), local());
            long start = System.nanoTime();
            fails(() -> controller.capabilities(author, request()), 503);
            assertThat(java.time.Duration.ofNanos(System.nanoTime() - start).toMillis()).isBetween(9000L, 12500L);
        } finally { server.stop(0); }
    }
}
