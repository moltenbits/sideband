package com.moltenbits.sideband.command;

import com.moltenbits.sideband.install.InstallReport;
import com.moltenbits.sideband.install.InstallReport.Item;

/** The client items of an install report as lines for a person: label, state, path, and any note beneath. */
final class InstallLines {

    private InstallLines() {
    }

    /** @param inboundNote whether to print the inbound item's explanatory note under it */
    static void append(StringBuilder text, InstallReport clients, boolean inboundNote) {
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
