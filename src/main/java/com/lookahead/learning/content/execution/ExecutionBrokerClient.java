package com.lookahead.learning.content.execution;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** Trusted fixed-origin forwarding only; the API never compiles or runs learner source. */
public final class ExecutionBrokerClient {
    static final int MAX_BYTES = 512 * 1024;
    private final ExecutionProperties properties;
    private final ObjectMapper mapper;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    ExecutionBrokerClient(ExecutionProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
    }

    JsonNode request(String method, String path, UUID owner, String idempotencyKey, byte[] body) {
        if (!properties.enabled()) throw new ExecutionFailure(503, "EXECUTION_DISABLED");
        boolean allowed = method.equals("GET") && path.equals("/v1/capabilities")
                || method.equals("POST") && path.equals("/v1/jobs")
                || (method.equals("GET") || method.equals("DELETE")) && path.matches("/v1/jobs/[0-9a-f-]{36}");
        if (!allowed || body.length > MAX_BYTES) throw new ExecutionFailure(400, "EXECUTION_REQUEST_INVALID");
        var builder = HttpRequest.newBuilder(URI.create(properties.brokerOrigin() + path))
                .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + properties.brokerToken())
                .header("X-Execution-Owner", owner.toString()).header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .method(method, body.length == 0 ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        if (idempotencyKey != null) builder.header("Idempotency-Key", idempotencyKey);
        try {
            var pending = client.sendAsync(builder.build(), ignored -> new BoundedBody());
            HttpResponse<byte[]> response;
            try {
                // Bound the entire exchange, including a broker that slowly drips its body.
                response = pending.get(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception exception) {
                pending.cancel(true);
                throw exception;
            }
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                throw switch (status) {
                    case 404 -> new ExecutionFailure(404, "EXECUTION_NOT_FOUND");
                    case 409 -> new ExecutionFailure(409, "EXECUTION_CONFLICT");
                    case 413 -> new ExecutionFailure(413, "EXECUTION_TOO_LARGE");
                    case 429 -> new ExecutionFailure(429, "EXECUTION_BUSY");
                    default -> new ExecutionFailure(503, "EXECUTION_UNAVAILABLE");
                };
            }
            JsonNode result = mapper.readTree(response.body());
            if (!result.isObject() || !"execution/v1".equals(result.path("schemaVersion").asText())) {
                throw new ExecutionFailure(503, "EXECUTION_UNAVAILABLE");
            }
            return result;
        } catch (ExecutionFailure exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ExecutionFailure(503, "EXECUTION_UNAVAILABLE");
        } catch (Exception exception) {
            // Do not expose upstream bodies, token values, addresses, source or transport diagnostics.
            throw new ExecutionFailure(503, "EXECUTION_UNAVAILABLE");
        }
    }

    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
        @Override public void onNext(List<ByteBuffer> buffers) {
            for (var buffer : buffers) {
                if (buffer.remaining() > MAX_BYTES - bytes.size()) {
                    subscription.cancel();
                    result.completeExceptionally(new IllegalStateException("Execution response exceeds limit"));
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable error) { result.completeExceptionally(error); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
