package com.lookahead.learning.content.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import javax.sql.DataSource;

@Configuration
@Profile("accounts")
@EnableTransactionManagement
public class AccountDatabaseConfiguration {
    @Bean
    HikariDataSource accountDataSource(@Value("${spring.datasource.url}") String url,
            @Value("${spring.datasource.username}") String username,
            @Value("${spring.datasource.password}") String password,
            @Value("${spring.datasource.hikari.maximum-pool-size:6}") int maximumPoolSize,
            @Value("${spring.datasource.hikari.connection-timeout:3000}") long connectionTimeout,
            @Value("${spring.datasource.hikari.validation-timeout:2000}") long validationTimeout) {
        if (!"lookahead_app".equals(username) || password.isBlank())
            throw new IllegalStateException("Accounts require the dedicated runtime database role");
        HikariDataSource source = new HikariDataSource();
        source.setJdbcUrl(url);
        source.setUsername(username);
        source.setPassword(password);
        // Pool acquisition does not bound an already borrowed connection's socket read.
        source.addDataSourceProperty("connectTimeout", "3");
        source.addDataSourceProperty("socketTimeout", "5");
        source.addDataSourceProperty("cancelSignalTimeout", "2");
        source.setMaximumPoolSize(maximumPoolSize);
        source.setConnectionTimeout(connectionTimeout);
        source.setValidationTimeout(validationTimeout);
        return source;
    }

    @Bean JdbcTemplate accountJdbcTemplate(DataSource source) {
        JdbcTemplate template = new JdbcTemplate(source);
        template.setQueryTimeout(5);
        return template;
    }
    @Bean JdbcTransactionManager accountTransactionManager(DataSource source) { return new JdbcTransactionManager(source); }
}
