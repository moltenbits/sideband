package com.moltenbits.sideband.install;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import io.micronaut.serde.config.naming.SnakeCaseStrategy;

import java.util.List;

/**
 * What is installed where. States: installed, updated, unchanged, missing, stale, conflict,
 * ejected; for a hook file, also custom (each hook a client needs is registered once but runs
 * another command than this executable) and broken (one is not registered, misplaced,
 * registered more than once, names the wrong client, or runs a path that gives no version);
 * and for {@code inbound}, whether Claude Code will deliver a push: installed, held, refused,
 * missing, unknown, unreadable. An item's {@code note}, when present, says what the state
 * covers and what to change.
 *
 * @param executable the executable whose path {@code init} registers in the hooks
 */
@Serdeable(naming = SnakeCaseStrategy.class)
public record InstallReport(String executable, List<Item> skills, Item hook, Item codexHook, Item inbound) {

    /** @param events for a hook file that exists, each event the client needs, in registration order */
    @Serdeable(naming = SnakeCaseStrategy.class)
    public record Item(String name, String path, String state, @Nullable String note, List<Event> events) {

        public Item {
            events = List.copyOf(events);
        }

        public Item(String name, String path, String state, @Nullable String note) {
            this(name, path, state, note, List.of());
        }

        public Item(String name, String path, String state) {
            this(name, path, state, null);
        }
    }

    /**
     * One event a client needs, and every handler configured for it.
     *
     * @param matcher  the matcher Sideband's handler must sit under, or null for none
     * @param problem  why Sideband's registration for this event will not run as it should, or null
     * @param handlers every handler in the event, in the file's order
     */
    @Serdeable(naming = SnakeCaseStrategy.class)
    public record Event(String event, @Nullable String matcher, @Nullable String problem, List<Handler> handlers) {

        public Event {
            handlers = List.copyOf(handlers);
        }
    }

    /**
     * @param matcher the matcher of the group the handler is in
     * @param command the command exactly as configured
     * @param runs    what the command runs, as far as Sideband can tell without running it: {@code this executable},
     *                the version a literal path to a Sideband reports, {@code not resolved} for a Sideband command
     *                the shell would expand, or {@code another hook; not run} for a handler that is not this event's
     *                Sideband registration
     */
    @Serdeable(naming = SnakeCaseStrategy.class)
    public record Handler(@Nullable String matcher, String command, String runs) {
    }
}
