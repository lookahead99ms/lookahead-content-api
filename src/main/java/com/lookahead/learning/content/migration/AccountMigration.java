package com.lookahead.learning.content.migration;

import org.flywaydb.core.Flyway;
import java.nio.file.Files;
import java.nio.file.Path;

/** One-shot migration entrypoint; no web server, runtime seeder or session setup. */
public final class AccountMigration {
    private AccountMigration() {}

    public static void main(String[] args) throws Exception {
        String password = migrationPassword(System.getenv());
        Flyway.configure().dataSource(required("SPRING_FLYWAY_URL"),
                required("SPRING_FLYWAY_USER"), password)
                .locations("classpath:db/migration").cleanDisabled(true).load().migrate();
    }

    /** Local config-tree file or ECS Secrets Manager injection; never print either value. */
    static String migrationPassword(java.util.Map<String,String> environment) throws java.io.IOException {
        String file=environment.get("LOOKAHEAD_MIGRATION_PASSWORD_FILE");
        String injected=environment.get("SPRING_FLYWAY_PASSWORD");
        if(file!=null && injected!=null) throw new IllegalStateException("Choose one migration password source");
        String password=injected!=null ? injected : Files.readString(Path.of(file!=null?file:"/run/secrets/spring.flyway.password")).stripTrailing();
        if(password.isBlank()) throw new IllegalStateException("Migration password is required");
        return password;
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }
}
