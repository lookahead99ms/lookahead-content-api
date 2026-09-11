package com.lookahead.learning.content.seed;

import com.lookahead.learning.content.validator.LocalTestSeedGuard;

import com.lookahead.learning.content.validator.SnapshotValidator;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;

@Component
@Profile("accounts & local-test")
@ConditionalOnProperty(name = "app.local-test.seed-enabled", havingValue = "true")
public class LocalTestAccountSeeder implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final Environment environment;
    private final SnapshotValidator catalog;

    public LocalTestAccountSeeder(JdbcTemplate jdbc, PasswordEncoder passwordEncoder, Environment environment,
                                  SnapshotValidator catalog, LocalTestSeedGuard guard) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.environment = environment;
        this.catalog = catalog;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        Set<String> topics = Set.copyOf(catalog.allTopicIds());
        if (topics.isEmpty()) throw new IllegalStateException("Synthetic accounts require a nonempty trusted catalog");
        for (int number = 1; number <= 10; number++) {
            String username = "learner%02d".formatted(number);
            UUID accountId = UUID.nameUUIDFromBytes(("lookahead-local-test:" + username).getBytes(StandardCharsets.UTF_8));
            // Check first to avoid unnecessary password hashing on ordinary restart. The insert remains race safe.
            Boolean exists = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM accounts WHERE username = ?)",
                    Boolean.class, username);
            if (Boolean.TRUE.equals(exists)) continue;
            String passwordHash = passwordEncoder.encode(environment.getRequiredProperty("app.local-test.seed-password"));
            int inserted = jdbc.update("""
                    INSERT INTO accounts(id, username, display_name, password_hash, enabled)
                    VALUES (?, ?, ?, ?, true) ON CONFLICT (username) DO NOTHING
                    """, accountId, username, "Synthetic Learner %02d".formatted(number), passwordHash);
            if (inserted == 0) continue;
            for (String topic : topics) {
                if (number == 9 && !Set.of("learn:hands-on-dsa", "learn:algorithmic-patterns").contains(topic)) continue;
                jdbc.update("INSERT INTO account_grants(account_id, topic_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
                        accountId, topic);
            }
        }
    }
}
