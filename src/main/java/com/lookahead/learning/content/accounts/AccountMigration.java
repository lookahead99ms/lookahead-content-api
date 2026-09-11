package com.lookahead.learning.content.accounts;

/** Compatibility entrypoint for existing infrastructure migration jobs. */
@Deprecated(forRemoval = false)
public final class AccountMigration {
    private AccountMigration() {}

    public static void main(String[] args) throws Exception {
        com.lookahead.learning.content.migration.AccountMigration.main(args);
    }
}
