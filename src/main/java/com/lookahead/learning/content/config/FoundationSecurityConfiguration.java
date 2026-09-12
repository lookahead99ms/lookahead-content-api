package com.lookahead.learning.content.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;

/** Redistributable, account-free foundation mode. Never active for accounts or gateway. */
@Configuration
@Profile("!accounts & !gateway")
public class FoundationSecurityConfiguration {
    @Bean UserDetailsService disabledFoundationUsers() {
        return username -> { throw new UsernameNotFoundException("Accounts are disabled"); };
    }

    @Bean SecurityFilterChain foundationSecurity(HttpSecurity http) throws Exception {
        return http.cors(Customizer.withDefaults()).csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .httpBasic(AbstractHttpConfigurer::disable).formLogin(AbstractHttpConfigurer::disable).build();
    }
}
