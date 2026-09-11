package com.lookahead.learning.content.atdd;

import com.lookahead.learning.content.config.AccountSecurityConfig;
import com.lookahead.learning.content.controller.AuthController;
import com.lookahead.learning.content.dto.AccountView;
import com.lookahead.learning.content.security.AccountPrincipal;
import com.lookahead.learning.content.service.AccountUserDetailsService;
import com.lookahead.learning.content.validator.LocalTestSeedGuard;

import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;

import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;
import org.mockito.MockMakers;
import static org.mockito.Mockito.when;


public class ComponentApplication {
    private static final UUID ACCOUNT_ID = UUID.fromString("53b362b5-57a6-41f1-8e8d-c413160e142a");
    @TestConfiguration
    @EnableAutoConfiguration(excludeName = {
            "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
            "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"})
    @Import({AccountSecurityConfig.class, AuthController.class, com.lookahead.learning.content.security.LocalAuthorAccess.class, LocalTestSeedGuard.class})
    public static class SecurityTestApplication {
        @Bean AccountUserDetailsService users() {
            var users = mock(AccountUserDetailsService.class, withSettings().mockMaker(MockMakers.SUBCLASS));
            String hash = PasswordEncoderFactories.createDelegatingPasswordEncoder().encode("synthetic-test-password");
            when(users.loadUserByUsername(anyString())).thenAnswer(invocation -> {
                if ("storage-outage".equals(invocation.getArgument(0))) throw new org.springframework.dao.DataAccessResourceFailureException("Synthetic storage failure");
                if (!"learner01".equals(invocation.getArgument(0))) throw new UsernameNotFoundException("Invalid credentials");
                return new AccountPrincipal(ACCOUNT_ID, "learner01", "Synthetic Learner 01", hash, true);
            });
            when(users.accountView(any())).thenAnswer(invocation -> {
                AccountPrincipal principal = invocation.getArgument(0);
                return new AccountView(principal.accountId(), principal.getUsername(),
                        principal.displayName(), Set.of("learn:hands-on-dsa"));
            });
            return users;
        }
    }

}
