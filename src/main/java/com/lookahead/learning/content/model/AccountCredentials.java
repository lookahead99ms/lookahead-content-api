package com.lookahead.learning.content.model;

import java.util.UUID;

/** Database credential record; never returned by a controller. */
public record AccountCredentials(UUID accountId, String username, String displayName,
                                 String passwordHash, boolean enabled) {}
