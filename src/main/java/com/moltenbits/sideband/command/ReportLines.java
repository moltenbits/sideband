package com.moltenbits.sideband.command;

import com.moltenbits.sideband.install.InstallReport;
import com.moltenbits.sideband.install.InstallReport.Event;
import com.moltenbits.sideband.install.InstallReport.Handler;
import com.moltenbits.sideband.install.InstallReport.Item;
import com.moltenbits.sideband.store.StoreHealth;

import java.util.Objects;

/** The lines {@code init} and {@code doctor} share: the database in one line, and the client items one per line. */
final class ReportLines {

    private ReportLines() {
    }

    /** Path, schema, entry count, size, and SQLite's integrity verdict. */
    static String database(StoreHealth health) {
        return health.path() + ", schema " + health.schema()
                + (health.migratedFrom() != null ? ", migrated from " + health.migratedFrom() : "")
                + (health.schema() > health.supported() ? ", newer than this Sideband's " + health.supported() : "")
                + ", " + health.entries() + (health.entries() == 1 ? " entry, " : " entries, ")
                + health.bytes() + " bytes, integrity " + health.integrity();
    }

    /**
     * Label, state, path, and any note beneath, for each client item; under a hook file, each
     * event the client needs and every handler configured for it.
     *
     * @param inboundNote whether to print the inbound item's explanatory note under it
     */
    static void clients(StringBuilder text, InstallReport clients, boolean inboundNote) {
        for (Item skill : clients.skills()) {
            item(text, skill.name() + " skill", skill, true);
        }
        item(text, "claude hook", clients.hook(), true);
        item(text, "codex hook", clients.codexHook(), true);
        item(text, "claude inbound", clients.inbound(), inboundNote);
    }

    private static void item(StringBuilder text, String label, Item item, boolean note) {
        text.append(String.format("  %-15s %-11s %s%n", label, item.state(), item.path()));
        if (note && item.note() != null) {
            text.append("      ").append(item.note()).append('\n');
        }
        for (Event event : item.events()) {
            text.append("      ").append(event.event());
            if (event.matcher() != null) {
                text.append(" (matcher ").append(event.matcher()).append(')');
            }
            if (event.problem() != null) {
                text.append(": ").append(event.problem());
            }
            text.append('\n');
            for (Handler handler : event.handlers()) {
                text.append("        ");
                if (!Objects.equals(handler.matcher(), event.matcher())) {
                    text.append("[matcher ").append(handler.matcher() == null ? "none" : handler.matcher()).append("] ");
                }
                text.append(handler.command()).append("  (").append(handler.runs()).append(")\n");
            }
        }
    }

    /** Whether either client's hook file needs {@code init} to work as it should. */
    static boolean hooksBroken(InstallReport clients) {
        return "broken".equals(clients.hook().state()) || "broken".equals(clients.codexHook().state());
    }
}
