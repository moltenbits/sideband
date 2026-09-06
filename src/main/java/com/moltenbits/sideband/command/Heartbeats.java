package com.moltenbits.sideband.command;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses the heartbeat a sender names on the command line: a count of seconds, minutes, or hours. */
final class Heartbeats {

    private static final Pattern DURATION = Pattern.compile("(\\d+)\\s*([smh])");

    private Heartbeats() {
    }

    static long seconds(String text) {
        Matcher m = DURATION.matcher(text.strip().toLowerCase());
        if (!m.matches()) {
            throw new IllegalArgumentException("--heartbeat must be a number followed by s, m, or h, such as 10m");
        }
        long n = Long.parseLong(m.group(1));
        if (n <= 0) {
            throw new IllegalArgumentException("--heartbeat must be positive");
        }
        return switch (m.group(2)) {
            case "s" -> n;
            case "m" -> n * 60;
            default -> n * 3600;
        };
    }
}
