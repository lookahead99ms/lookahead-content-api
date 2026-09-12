package com.lookahead.learning.content.oauth;

import com.lookahead.learning.content.repository.AccountRepository;
import com.lookahead.learning.content.security.AccountPrincipal;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.authorization.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import java.util.UUID;

@Configuration
@Profile("oauth-server")
public class OAuthResourceConfiguration {
    @Bean @Order(2) SecurityFilterChain oauthResources(HttpSecurity http, JwtDecoder decoder,
            OAuthSettings settings, AccountRepository accounts, OAuth2AuthorizationService authorizations) throws Exception {
        http.securityMatcher("/api/v1/executions/capabilities", "/api/v1/executions/jobs", "/api/v1/executions/jobs/*", "/api/v1/plans", "/api/v1/plans/**", "/api/v1/auth/me", "/api/v1/account-catalog", "/api/v1/support", "/content/**")
                .cors(Customizer.withDefaults()).csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session->session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth->auth
                        .requestMatchers(HttpMethod.GET,"/content/**").access((authentication,request)-> {
                            var value=authentication.get();
                            return new AuthorizationDecision(value instanceof AnonymousAuthenticationToken || value.getAuthorities().stream().anyMatch(a->a.getAuthority().equals("SCOPE_content")));
                        })
                        .requestMatchers("/api/v1/support").hasAuthority("SCOPE_support")
                        .anyRequest().hasAuthority("SCOPE_account"))
                .oauth2ResourceServer(resource->resource.jwt(jwt->jwt.decoder(decoder)
                        .jwtAuthenticationConverter(new ApiAccessTokenConverter(settings,accounts,authorizations))));
        return http.build();
    }
}
