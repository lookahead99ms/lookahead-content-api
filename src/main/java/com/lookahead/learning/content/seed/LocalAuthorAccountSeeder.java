package com.lookahead.learning.content.seed;

import com.lookahead.learning.content.security.LocalAuthorAccess;
import com.lookahead.learning.content.validator.LocalTestSeedGuard;
import com.lookahead.learning.content.validator.SnapshotValidator;
import java.util.UUID;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("accounts & local-test")
@ConditionalOnProperty(name = "app.local-test.author-enabled", havingValue = "true")
public class LocalAuthorAccountSeeder implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final Environment environment;
    private final SnapshotValidator catalog;
    public LocalAuthorAccountSeeder(JdbcTemplate jdbc, PasswordEncoder encoder, Environment environment,
                                   SnapshotValidator catalog, LocalTestSeedGuard guard) {
        this.jdbc = jdbc; this.encoder = encoder; this.environment = environment; this.catalog = catalog;
    }
    @Override @Transactional
    public void run(ApplicationArguments args) {
        if (catalog.allTopicIds().isEmpty()) throw new IllegalStateException("Local author requires a published catalog");
        var existing = jdbc.queryForList("SELECT id FROM accounts WHERE username = ?", UUID.class, LocalAuthorAccess.USERNAME);
        if (!existing.isEmpty() && !LocalAuthorAccess.ACCOUNT_ID.equals(existing.getFirst()))
            throw new IllegalStateException("The reserved local author name belongs to another account; no access was changed");
        if (existing.isEmpty()) {
            jdbc.update("INSERT INTO accounts(id, username, display_name, password_hash, enabled) VALUES (?, ?, ?, ?, true)",
                    LocalAuthorAccess.ACCOUNT_ID, LocalAuthorAccess.USERNAME, "Author",
                    encoder.encode(environment.getRequiredProperty("app.local-test.seed-password")));
        }
        // Only this reserved local account gets every current published catalog scope.
        for (String topic : catalog.allTopicIds())
            jdbc.update("INSERT INTO account_grants(account_id, topic_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
                    LocalAuthorAccess.ACCOUNT_ID, topic);
    }
}
