package com.moltenbits.sideband.protocol;

import java.util.Optional;

/** A client role. Version one has exactly one live instance of each per repository. */
public enum Role implements Wire {

    CLAUDE("claude", "Claude"),

    CODEX("codex", "Codex");

    private final String id;
    private final String displayName;

    Role(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    /** The identifier used in metadata and routing directives. */
    @Override
    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public static Optional<Role> fromId(String id) {
        for (Role role : values()) {
            if (role.id.equals(id)) {
                return Optional.of(role);
            }
        }
        return Optional.empty();
    }
}
