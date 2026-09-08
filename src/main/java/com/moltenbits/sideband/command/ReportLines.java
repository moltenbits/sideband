package com.moltenbits.sideband.command;

import com.moltenbits.sideband.install.InstallReport;
import com.moltenbits.sideband.install.InstallReport.Item;
import com.moltenbits.sideband.store.StoreHealth;

/** The lines {@code init} and {@code doctor} share: the database in one line, and the client items one per line. */
final class ReportLines {

    private ReportLines() {
    }

    /** Path, entry count, size, and SQLite's integrity verdict. */
    static String database(StoreHealth health) {
        return health.path() + ", " + health.entries() + (health.entries() == 1 ? " entry, " : " entries, ")
                + health.bytes() + " bytes, integrity " + health.integrity();
    }

    /**
     * Label, state, path, and any note beneath, for each client item.
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
    }
}
