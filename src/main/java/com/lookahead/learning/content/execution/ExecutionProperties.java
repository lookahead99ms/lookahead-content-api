package com.lookahead.learning.content.execution;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.net.URI;
import java.util.Set;

@ConfigurationProperties("app.execution")
public record ExecutionProperties(boolean enabled, URI brokerOrigin, String brokerToken) {
    public ExecutionProperties {
        if (enabled) {
            if (brokerOrigin == null || !"http".equals(brokerOrigin.getScheme())
                    || brokerOrigin.getHost() == null
                    || !Set.of("127.0.0.1", "localhost", "host.docker.internal").contains(brokerOrigin.getHost())
                    || brokerOrigin.getPort() < 1 || brokerOrigin.getPort() > 65535 || brokerOrigin.getUserInfo() != null
                    || !brokerOrigin.getPath().isEmpty() || brokerOrigin.getQuery() != null || brokerOrigin.getFragment() != null) {
                throw new IllegalStateException("Execution broker must be an explicit local HTTP origin with port");
            }
            if (brokerToken == null || brokerToken.length() < 32 || brokerToken.length() > 512
                    || brokerToken.chars().anyMatch(value -> value < 33 || value > 126)) {
                throw new IllegalStateException("Execution broker token must be a mounted secret of 32 to 512 non-whitespace characters");
            }
        }
    }
    @Override public String toString() { return "ExecutionProperties[redacted]"; }
}
