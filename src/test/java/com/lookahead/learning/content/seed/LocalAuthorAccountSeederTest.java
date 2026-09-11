package com.lookahead.learning.content.seed;

import com.lookahead.learning.content.security.LocalAuthorAccess;
import com.lookahead.learning.content.validator.LocalTestSeedGuard;
import com.lookahead.learning.content.validator.SnapshotValidator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.password.PasswordEncoder;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LocalAuthorAccountSeederTest {
    private LocalAuthorAccountSeeder seeder(JdbcTemplate jdbc, PasswordEncoder encoder) {
        var env = new MockEnvironment().withProperty("app.local-test.seed-enabled", "true")
                .withProperty("app.local-test.author-enabled", "true").withProperty("app.deployment-environment", "local")
                .withProperty("app.local-test.seed-password", "synthetic-author-password");
        env.setActiveProfiles("accounts", "local-test");
        var mapper = JsonMapper.builder().build();
        var catalogJson = mapper.createObjectNode();
        catalogJson.put("schemaVersion", "account-catalog/v1");
        catalogJson.put("catalogVersion", "sha256:" + "a".repeat(64));
        catalogJson.putArray("algorithmVersions").add("study-plan/v1");
        catalogJson.putArray("rankingVersions");
        var record = catalogJson.putArray("records").addObject();
        record.put("id", "author-fixture");
        record.put("contentType", "question");
        record.putArray("topicIds").add("learn:core-java").add("look-ahead:system-design");
        record.putArray("route").add("/question");
        var catalog = new SnapshotValidator(mapper, catalogJson);
        return new LocalAuthorAccountSeeder(jdbc, encoder, env, catalog, new LocalTestSeedGuard(env));
    }
    @Test void createsOnlyTheReservedAuthorWithEncodedPasswordAndExplicitGrants() {
        var jdbc = mock(JdbcTemplate.class, withSettings().mockMaker(MockMakers.SUBCLASS));
        var encoder = mock(PasswordEncoder.class, withSettings().mockMaker(MockMakers.PROXY));
        when(jdbc.queryForList(anyString(), eq(UUID.class), eq(LocalAuthorAccess.USERNAME))).thenReturn(List.of());
        when(encoder.encode(anyString())).thenReturn("encoded-author-password");
        seeder(jdbc, encoder).run(new DefaultApplicationArguments());
        verify(jdbc).update(startsWith("INSERT INTO accounts"), eq(LocalAuthorAccess.ACCOUNT_ID), eq(LocalAuthorAccess.USERNAME), eq("Author"), eq("encoded-author-password"));
        verify(jdbc, times(2)).update(startsWith("INSERT INTO account_grants"), eq(LocalAuthorAccess.ACCOUNT_ID), anyString());
    }
    @Test void restartPreservesThePasswordAndRefreshesPublishedScopesOnlyForTheAuthor() {
        var jdbc = mock(JdbcTemplate.class, withSettings().mockMaker(MockMakers.SUBCLASS));
        var encoder = mock(PasswordEncoder.class, withSettings().mockMaker(MockMakers.PROXY));
        when(jdbc.queryForList(anyString(), eq(UUID.class), eq(LocalAuthorAccess.USERNAME))).thenReturn(List.of(LocalAuthorAccess.ACCOUNT_ID));
        seeder(jdbc, encoder).run(new DefaultApplicationArguments());
        verifyNoInteractions(encoder);
        verify(jdbc, never()).update(startsWith("INSERT INTO accounts"), any(), any(), any(), any());
        verify(jdbc, times(2)).update(startsWith("INSERT INTO account_grants"), eq(LocalAuthorAccess.ACCOUNT_ID), anyString());
    }
    @Test void aNameCollisionNeverPromotesAnExistingLearner() {
        var jdbc = mock(JdbcTemplate.class, withSettings().mockMaker(MockMakers.SUBCLASS));
        var encoder = mock(PasswordEncoder.class, withSettings().mockMaker(MockMakers.PROXY));
        when(jdbc.queryForList(anyString(), eq(UUID.class), eq(LocalAuthorAccess.USERNAME))).thenReturn(List.of(UUID.randomUUID()));
        assertThatThrownBy(() -> seeder(jdbc, encoder).run(new DefaultApplicationArguments())).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(encoder);
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }
}
