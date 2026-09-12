package com.lookahead.learning.content.execution;

import com.lookahead.learning.content.dto.ApiResponse;
import com.lookahead.learning.content.security.AccountPrincipal;
import com.lookahead.learning.content.security.LocalAuthorAccess;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@Profile("accounts")
@RequestMapping("/api/v1/executions")
public class ExecutionController {
    private final LocalAuthorAccess author;
    private final ExecutionProperties properties;
    private final ExecutionBrokerClient broker;
    private final ObjectMapper mapper;

    public ExecutionController(LocalAuthorAccess author, ExecutionProperties properties, ExecutionBrokerClient broker, ObjectMapper mapper) {
        this.author = author; this.properties = properties; this.broker = broker; this.mapper = mapper;
    }

    @GetMapping("/capabilities")
    public ResponseEntity<?> capabilities(@AuthenticationPrincipal AccountPrincipal principal, HttpServletRequest request) {
        UUID owner = owner(principal, request);
        return success(properties.enabled() ? broker.request("GET", "/v1/capabilities", owner, null, new byte[0])
                : Map.of("schemaVersion", "execution/v1", "enabled", false, "problems", java.util.List.of(), "limits", Map.of("sourceBytes", 65536)));
    }

    @PostMapping("/jobs")
    public ResponseEntity<?> submit(@AuthenticationPrincipal AccountPrincipal principal, HttpServletRequest request) {
        UUID owner = owner(principal, request);
        String key = uuid(request.getHeader("Idempotency-Key"));
        if (request.getContentLengthLong() > ExecutionBrokerClient.MAX_BYTES) throw new ExecutionFailure(413, "EXECUTION_TOO_LARGE");
        byte[] body;
        try { body = request.getInputStream().readNBytes(ExecutionBrokerClient.MAX_BYTES + 1); }
        catch (java.io.IOException exception) { throw new ExecutionFailure(400, "EXECUTION_REQUEST_INVALID"); }
        if (body.length > ExecutionBrokerClient.MAX_BYTES) throw new ExecutionFailure(413, "EXECUTION_TOO_LARGE");
        JsonNode job;
        try { job = mapper.readTree(body); }
        catch (Exception exception) { throw new ExecutionFailure(400, "EXECUTION_REQUEST_INVALID"); }
        Set<String> fields = Set.of("problemId", "language", "source", "contractVersion", "fixtureVersion");
        if (job == null || !job.isObject() || job.size() != fields.size() || !job.propertyNames().equals(fields)
                || fields.stream().anyMatch(field -> !job.path(field).isString())
                || !"algorithmic-two-sum".equals(job.path("problemId").asText())
                || !Set.of("java", "python", "go").contains(job.path("language").asText())
                || !"two-sum/v1".equals(job.path("contractVersion").asText())
                || !"visible/v1".equals(job.path("fixtureVersion").asText())) {
            throw new ExecutionFailure(400, "EXECUTION_REQUEST_INVALID");
        }
        if (job.path("source").asText().getBytes(StandardCharsets.UTF_8).length > 65536) throw new ExecutionFailure(413, "EXECUTION_TOO_LARGE");
        return success(broker.request("POST", "/v1/jobs", owner, key, body));
    }

    @GetMapping("/jobs/{id}")
    public ResponseEntity<?> status(@PathVariable String id, @AuthenticationPrincipal AccountPrincipal principal, HttpServletRequest request) {
        UUID owner = owner(principal, request);
        return success(broker.request("GET", "/v1/jobs/" + uuid(id), owner, null, new byte[0]));
    }

    @DeleteMapping("/jobs/{id}")
    public ResponseEntity<?> cancel(@PathVariable String id, @AuthenticationPrincipal AccountPrincipal principal, HttpServletRequest request) {
        UUID owner = owner(principal, request);
        return success(broker.request("DELETE", "/v1/jobs/" + uuid(id), owner, null, new byte[0]));
    }

    private UUID owner(AccountPrincipal principal, HttpServletRequest request) {
        if (!author.allowed(principal)) throw new ExecutionFailure(403, "EXECUTION_AUTHOR_REQUIRED");
        if (!principal.accountId().toString().equals(uuid(request.getHeader("X-LookAhead-Account")))) {
            throw new ExecutionFailure(409, "ACCOUNT_CHANGED");
        }
        return principal.accountId();
    }

    private String uuid(String value) {
        if (value == null || !value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
            throw new ExecutionFailure(400, "EXECUTION_REQUEST_INVALID");
        }
        return UUID.fromString(value).toString();
    }

    private ResponseEntity<?> success(Object data) { return ResponseEntity.ok().header("Cache-Control", "no-store").body(ApiResponse.success(data)); }

    @ExceptionHandler(ExecutionFailure.class)
    public ResponseEntity<?> failure(ExecutionFailure exception) {
        return ResponseEntity.status(exception.status).header("Cache-Control", "no-store")
                .body(Map.of("status", exception.status, "code", exception.code, "message", "Execution request could not be completed. Check the request or retry."));
    }
}
