package com.lookahead.learning.content.config;

import com.lookahead.learning.content.security.AccountPrincipal;
import com.lookahead.learning.content.service.AccountUserDetailsService;
import com.lookahead.learning.content.validator.LocalTestSeedGuard;

import com.lookahead.learning.content.dto.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;

@Configuration
public class AccountSecurityConfig {
    @Bean
    @Profile("!accounts & !gateway")
    UserDetailsService disabledFoundationUsers() {
        return username -> { throw new UsernameNotFoundException("Accounts are disabled"); };
    }

    @Bean
    @Profile("accounts")
    PasswordEncoder accountPasswordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    @Profile("accounts")
    @org.springframework.core.annotation.Order(3)
    SecurityFilterChain accountSecurity(HttpSecurity http, AccountUserDetailsService users,
                                        PasswordEncoder encoder, ObjectMapper mapper, Environment environment,
                                        LocalTestSeedGuard localTestGuard) throws Exception {
        boolean localPasswordLogin = environment.acceptsProfiles(Profiles.of("local-test"))
                && "local".equals(environment.getProperty("app.deployment-environment"))
                && !environment.acceptsProfiles(Profiles.of("prod", "production"));
        boolean registrationEnabled = environment.getProperty("app.accounts.registration-enabled", Boolean.class, false);
        boolean passwordLogin = localPasswordLogin || registrationEnabled;
        http.cors(Customizer.withDefaults())
                .csrf(csrf -> csrf.csrfTokenRepository(new HttpSessionCsrfTokenRepository()))
                .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()))
                .requestCache(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionFixation(fixation -> fixation.changeSessionId()))
                .authorizeHttpRequests(auth -> {
                    if (passwordLogin) auth.requestMatchers("/api/v1/auth/login").permitAll();
                    else auth.requestMatchers("/api/v1/auth/login").denyAll();
                    if (registrationEnabled) auth.requestMatchers(HttpMethod.POST, "/api/v1/auth/register").permitAll();
                    else auth.requestMatchers("/api/v1/auth/register").denyAll();
                    auth.requestMatchers(HttpMethod.GET, "/content/**", "/api/v1/auth/csrf", "/api/v1/status",
                                "/api/v1/auth/options",
                                "/actuator/health", "/actuator/health/**", "/actuator/info",
                                "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/api/v1/auth/logout").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/account-catalog").authenticated()
                        .requestMatchers("/api/v1/support", "/api/v1/auth/continue", "/api/v1/auth/me", "/api/v1/plans", "/api/v1/plans/**").authenticated()
                        .anyRequest().denyAll();
                })
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) ->
                                error(mapper, request, response, 401, "AUTHENTICATION_REQUIRED", "Sign in to continue"))
                        .accessDeniedHandler((request, response, exception) ->
                                error(mapper, request, response, 403,
                                        exception instanceof CsrfException ? "CSRF_INVALID" : "ACCESS_DENIED",
                                        exception instanceof CsrfException ? "Refresh the security token and retry" : "Access denied")))
                .logout(logout -> logout.logoutUrl("/api/v1/auth/logout")
                        .invalidateHttpSession(true).clearAuthentication(true)
                        .deleteCookies(environment.getProperty("server.servlet.session.cookie.name", "JSESSIONID"))
                        .logoutSuccessHandler((request, response, authentication) -> response.setStatus(204)));
        if (passwordLogin) {
            var provider = new DaoAuthenticationProvider(users);
            provider.setPasswordEncoder(encoder);
            http.authenticationProvider(provider)
                    .formLogin(login -> login.loginProcessingUrl("/api/v1/auth/login")
                            .successHandler((request, response, authentication) -> write(mapper, response, 200,
                                    ApiResponse.success(users.accountView((AccountPrincipal) authentication.getPrincipal()))))
                            .failureHandler((request, response, exception) -> error(mapper, request, response,
                                    exception instanceof AuthenticationServiceException ? 503 : 401,
                                    exception instanceof AuthenticationServiceException ? "ACCOUNT_STORAGE_UNAVAILABLE" : "INVALID_CREDENTIALS",
                                    exception instanceof AuthenticationServiceException ? "Account storage is temporarily unavailable. Retry shortly." : "Invalid username or password")));
        } else {
            // Identity is disabled unless registration or the guarded local fixture login is enabled.
            http.formLogin(AbstractHttpConfigurer::disable);
        }
        return http.build();
    }

    @Bean
    @Profile("!accounts & !gateway")
    SecurityFilterChain foundationSecurity(HttpSecurity http) throws Exception {
        return http.cors(Customizer.withDefaults()).csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .httpBasic(AbstractHttpConfigurer::disable).formLogin(AbstractHttpConfigurer::disable).build();
    }

    private static void error(ObjectMapper mapper, HttpServletRequest request, HttpServletResponse response,
                              int status, String code, String message) throws IOException {
        write(mapper, response, status, Map.of("status", status, "code", code, "message", message,
                "path", request.getRequestURI(), "timestamp", Instant.now()));
    }

    private static void write(ObjectMapper mapper, HttpServletResponse response, int status, Object body) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write(mapper.writeValueAsString(body));
    }
}
