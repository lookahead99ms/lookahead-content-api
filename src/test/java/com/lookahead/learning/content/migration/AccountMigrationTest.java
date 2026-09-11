package com.lookahead.learning.content.migration;
import java.nio.file.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
class AccountMigrationTest {
    @TempDir Path directory;
    @Test void readsLocalFileOrInjectedCloudSecretWithoutAmbiguousPrecedence() throws Exception {
        Path file=directory.resolve("password");Files.writeString(file,"synthetic-file-secret\n");
        assertEquals("synthetic-file-secret",AccountMigration.migrationPassword(Map.of("LOOKAHEAD_MIGRATION_PASSWORD_FILE",file.toString())));
        assertEquals("synthetic-ecs-secret",AccountMigration.migrationPassword(Map.of("SPRING_FLYWAY_PASSWORD","synthetic-ecs-secret")));
        assertThrows(IllegalStateException.class,()->AccountMigration.migrationPassword(Map.of("SPRING_FLYWAY_PASSWORD","")));
        assertThrows(IllegalStateException.class,()->AccountMigration.migrationPassword(Map.of("SPRING_FLYWAY_PASSWORD","synthetic","LOOKAHEAD_MIGRATION_PASSWORD_FILE",file.toString())));
    }
}
