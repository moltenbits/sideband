package com.moltenbits.sideband.command;

import com.moltenbits.sideband.handoff.Wake;
import com.moltenbits.sideband.home.SidebandHome;
import com.moltenbits.sideband.host.HostEnvironment;
import com.moltenbits.sideband.journal.Entry;
import com.moltenbits.sideband.journal.Journal;
import com.moltenbits.sideband.journal.Read;
import com.moltenbits.sideband.pending.Addressing;
import com.moltenbits.sideband.protocol.MessageType;
import com.moltenbits.sideband.protocol.Role;
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
 * The listener. Streams one wake line per batch of entries that concern the role, forever,
 * and is meant to sit under a host facility that turns each line into a notification, such
 * as Claude Code's Monitor. The line is a signal to run {@code pending}, never the payload:
 * hosts truncate notifications. Idle waiting happens inside this process and costs no model
 * tokens. {@code --once} prints one line and exits, for a host without such a facility,
 * where a background task restarts it from the line's {@code end}; {@code --timeout} bounds
 * that and exits with the timed-out code, still printing the line so the offset carries over.
 */
@Command(name = "follow", description = "Stream one wake line per batch of new entries for this client, forever; read them with pending. --once prints one line and exits", mixinStandardHelpOptions = true)
@Prototype
public class FollowCommand implements Callable<Integer> {

    /** Wake from the watcher this often even when nothing arrived, so a hung watch is bounded. */
    static final Duration IDLE_RECHECK = Duration.ofSeconds(30);

    @Spec
    CommandSpec spec;

    @Mixin
    Repository repository;

    @Option(names = "--from", required = true, description = "Byte offset to start from, normally the session's watermark")
    long from;

    @Option(names = "--once", description = "Print one wake line and exit instead of streaming")
    boolean once;

    @Option(names = "--timeout", description = "With --once: seconds to wait before giving up with the timed-out exit code")
    Long timeoutSeconds;

    @Option(names = "--role", hidden = true, description = "Override the client detected from the environment")
    Role role;

    @Option(names = "--all", hidden = true, description = "Every entry regardless of recipient, acks included (for inspection and tests)")
    boolean all;

    @Option(names = "--max-batches", hidden = true, description = "Stop after this many lines (for tests)")
    Integer maxBatches;

    private final SidebandHome home;
    private final HostEnvironment host;
    private final JournalWatcher watcher;
    private final ObjectMapper json;

    FollowCommand(SidebandHome home, HostEnvironment host, JournalWatcher watcher, ObjectMapper json) {
        this.home = home;
        this.host = host;
        this.watcher = watcher;
        this.json = json;
    }

    @Override
    public Integer call() throws IOException {
        if (from < 0) {
            throw new IllegalArgumentException("--from must not be negative");
        }
        if (timeoutSeconds != null && (timeoutSeconds < 0 || !once)) {
            throw new IllegalArgumentException(timeoutSeconds < 0 ? "--timeout must not be negative" : "--timeout only applies with --once");
        }
        if (role == null) {
            role = host.requireRole("--role");
        }
        Path file = home.locate(repository.directory).resolve(Journal.FILE_NAME);
        PrintWriter out = spec.commandLine().getOut();
        Predicate<Entry> wanted = all ? entry -> true
                : entry -> Addressing.concerns(entry.metadata(), role) && entry.metadata().type() != MessageType.ACK;
        int limit = once ? 1 : maxBatches == null ? Integer.MAX_VALUE : maxBatches;
        Duration timeout = once && timeoutSeconds != null ? Duration.ofSeconds(timeoutSeconds) : IDLE_RECHECK;
        long offset = from;
        for (int lines = 0; lines < limit;) {
            Waited waited = watcher.await(file, offset, timeout, wanted);
            Read read = waited.read();
            offset = read.end();
            if (waited.timedOut() && !(once && timeoutSeconds != null)) {
                continue;
            }
            out.println(json.writeValueAsString(Wake.of(role, read.start(), read.end(), read.entries(), read.diagnostics())));
            out.flush();
            if (waited.timedOut()) {
                return ExitCode.TIMED_OUT;
            }
            lines++;
        }
        return ExitCode.OK;
    }
}
