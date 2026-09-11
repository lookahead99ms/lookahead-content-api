package com.lookahead.learning.content.health;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component("accountDatabase")
@Profile("accounts")
public class AccountDatabaseHealthIndicator implements HealthIndicator {
    private final JdbcTemplate jdbc;
    public AccountDatabaseHealthIndicator(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Override public Health health() {
        try {
            Boolean ready = jdbc.queryForObject("SELECT current_user = 'lookahead_app' AND EXISTS "
                    + "(SELECT 1 FROM flyway_schema_history WHERE version='1' AND success) "
                    + "AND has_table_privilege(current_user, 'plans', 'SELECT,INSERT,UPDATE,DELETE')",
                    Boolean.class);
            jdbc.queryForObject("SELECT count(*) FROM plans WHERE false", Long.class);
            return Boolean.TRUE.equals(ready) ? Health.up().build() : Health.down().build();
        } catch (Exception ex) { return Health.down().build(); }
    }
}
