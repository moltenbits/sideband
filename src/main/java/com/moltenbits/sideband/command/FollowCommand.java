package com.moltenbits.sideband.command;

import com.moltenbits.sideband.handoff.Wake;
import com.moltenbits.sideband.home.SidebandHome;
import com.moltenbits.sideband.host.HostEnvironment;
import com.moltenbits.sideband.journal.Entry;
import com.moltenbits.sideband.journal.Journal;
import com.moltenbits.sideband.journal.Read;
import com.moltenbits.sideband.protocol.Role;
import com.moltenbits.sideband.recipient.Cursor;
import com.moltenbits.sideband.recipient.RecipientState;
import com.moltenbits.sideband.waiting.JournalWatcher;
import com.moltenbits.sideband.waiting.Waited;
import io.micronaut.context.annotation.Prototype;
import io.micronaut.serde.ObjectMapper;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.function.Predicate;

/**
 * A listener that never needs re-arming: streams one JSON wake line, forever, for every
 * set of open entries addressed to the role. Meant to be attached to a host facility that
 * turns each output line into a notification, such as Claude Code's Monitor. The line is
 * a signal to run {@code pending}, never the payload: hosts truncate notifications.
 * Idle waiting happens inside this process and costs no model tokens.
 */
@Command(name = "follow", description = "Stream one wake line per batch of open entries for a role; read them with pending; never exits on its own", mixinStandardHelpOptions = true)
@Prototype
public class FollowCommand implements Callable<Integer> {

    /** Re-check the cursor this often so entries resolved elsewhere stop being re-reported. */
    static final Duration CURSOR_REFRESH = Duration.ofSeconds(30);

    @Spec
    CommandSpec spec;

    @Mixin
    Repository repository;

    @Option(names = "--from", required = true, description = "Byte offset to start from, normally the session's watermark_end")
    long from;

    @Option(names = "--role", hidden = true, description = "Override the client detected from the environment")
    Role role;

    @Option(names = "--max-batches", hidden = true, description = "Stop after this many batches (for tests)")
    Integer maxBatches;

    private final SidebandHome home;
    private final HostEnvironment host;
    private final JournalWatcher watcher;
    private final RecipientState recipients;
    private final ObjectMapper json;

    FollowCommand(SidebandHome home, HostEnvironment host, JournalWatcher watcher, RecipientState recipients, ObjectMapper json) {
        this.home = home;
        this.host = host;
        this.watcher = watcher;
        this.recipients = recipients;
        this.json = json;
    }

    @Override
    public Integer call() throws IOException {
        if (from < 0) {
            throw new IllegalArgumentException("--from must not be negative");
        }
        if (role == null) {
            role = host.requireRole("--role");
        }
        Path stateDirectory = home.locate(repository.directory);
        Path file = stateDirectory.resolve(Journal.FILE_NAME);
        PrintWriter out = spec.commandLine().getOut();
        long offset = from;
        int batches = 0;
        while (maxBatches == null || batches < maxBatches) {
            Cursor cursor = recipients.load(stateDirectory, role);
            Predicate<Entry> open = entry -> recipients.isOpen(cursor, entry);
            Waited waited = watcher.await(file, offset, CURSOR_REFRESH, open);
            Read read = waited.read();
            offset = read.end();
            if (waited.timedOut()) {
                continue;
            }
            out.println(json.writeValueAsString(Wake.of(role, read.start(), read.end(), read.entries(), read.diagnostics())));
            out.flush();
            batches++;
        }
        return ExitCode.OK;
    }
}
