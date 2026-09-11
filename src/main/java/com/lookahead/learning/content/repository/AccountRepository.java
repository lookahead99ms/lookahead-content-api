package com.lookahead.learning.content.repository;

import com.lookahead.learning.content.model.AccountCredentials;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
@Profile("accounts")
public class AccountRepository {
    private final JdbcTemplate jdbc;

    public AccountRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Set<String> findTopicGrants(UUID accountId) {
        return Set.copyOf(jdbc.queryForList(
                "SELECT topic_id FROM account_grants WHERE account_id = ? AND (valid_until IS NULL OR valid_until > now())", String.class, accountId));
    }

    public boolean isEnabled(UUID accountId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM accounts WHERE id=? AND enabled=true)",Boolean.class,accountId));
    }

    public boolean hasRegistrationProfile(UUID accountId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM account_profiles WHERE account_id=?)", Boolean.class, accountId));
    }

    public Optional<AccountCredentials> findById(UUID id) {
        var rows=jdbc.query("SELECT id,username,display_name,password_hash,enabled FROM accounts WHERE id=?",
                (row,index)->new AccountCredentials(row.getObject("id",UUID.class),row.getString("username"),row.getString("display_name"),null,row.getBoolean("enabled")),id);
        return rows.stream().findFirst();
    }

    public Optional<AccountCredentials> findByUsername(String username) {
        var accounts = jdbc.query("""
                SELECT id, username, display_name, password_hash, enabled
                FROM accounts WHERE username = ?
                """, (row, index) -> new AccountCredentials(row.getObject("id", UUID.class),
                row.getString("username"), row.getString("display_name"),
                row.getString("password_hash"), row.getBoolean("enabled")), username);
        return accounts.size() == 1 ? Optional.of(accounts.getFirst()) : Optional.empty();
    }
}
