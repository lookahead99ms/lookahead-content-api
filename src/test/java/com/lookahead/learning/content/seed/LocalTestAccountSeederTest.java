package com.lookahead.learning.content.seed;

import com.lookahead.learning.content.seed.LocalTestAccountSeeder;
import com.lookahead.learning.content.validator.LocalTestSeedGuard;

import com.lookahead.learning.content.validator.SnapshotValidator;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.password.PasswordEncoder;

import tools.jackson.databind.json.JsonMapper;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LocalTestAccountSeederTest {
    @Test
    void restartDoesNotChangeExistingCredentialsOrGrants() {
        var jdbc = mock(JdbcTemplate.class, withSettings().mockMaker(MockMakers.SUBCLASS));
        var encoder = mock(PasswordEncoder.class, withSettings().mockMaker(MockMakers.SUBCLASS));
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), anyString())).thenReturn(true);
        seeder(jdbc, encoder).run(new DefaultApplicationArguments());
        verifyNoInteractions(encoder);
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void seedsTenEncodedAccountsAndLimitsNinthLearnerGrants() {
        var jdbc = mock(JdbcTemplate.class, withSettings().mockMaker(MockMakers.SUBCLASS));
        var encoder = mock(PasswordEncoder.class, withSettings().mockMaker(MockMakers.SUBCLASS));
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), anyString())).thenReturn(false);
        when(encoder.encode(anyString())).thenReturn("{bcrypt}test-hash");
        when(jdbc.update(startsWith("INSERT INTO accounts"), any(), any(), any(), any())).thenReturn(1);
        seeder(jdbc, encoder).run(new DefaultApplicationArguments());
        verify(encoder, times(10)).encode("synthetic-unit-test-password");
        verify(jdbc, times(10)).update(startsWith("INSERT INTO accounts"), any(), any(), any(), eq("{bcrypt}test-hash"));
        verify(jdbc, times(19)).update(startsWith("INSERT INTO account_grants"), any(UUID.class), anyString());
    }

    private LocalTestAccountSeeder seeder(JdbcTemplate jdbc, PasswordEncoder encoder) {
        var environment = new MockEnvironment().withProperty("app.local-test.seed-enabled", "true")
                .withProperty("app.deployment-environment", "local")
                .withProperty("app.local-test.seed-password", "synthetic-unit-test-password");
        environment.setActiveProfiles("accounts", "local-test");
        var mapper = JsonMapper.builder().build();
        var catalogJson = mapper.createObjectNode();
        catalogJson.put("schemaVersion", "account-catalog/v1");
        catalogJson.put("catalogVersion", "sha256:" + "a".repeat(64));
        catalogJson.putArray("algorithmVersions").add("study-plan/v1");
        catalogJson.putArray("rankingVersions");
        var record = catalogJson.putArray("records").addObject();
        record.put("id", "fixture-question");
        record.put("contentType", "question");
        record.putArray("topicIds").add("learn:hands-on-dsa").add("grow:spring-boot");
        record.putArray("route").add("/question");
        var catalog = new SnapshotValidator(mapper, catalogJson);
        return new LocalTestAccountSeeder(jdbc, encoder, environment, catalog, new LocalTestSeedGuard(environment));
    }
}
