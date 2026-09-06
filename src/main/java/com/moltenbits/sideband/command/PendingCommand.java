package com.moltenbits.sideband.command;

import com.moltenbits.sideband.home.SidebandHome;
import com.moltenbits.sideband.host.HostEnvironment;
import com.moltenbits.sideband.journal.Entry;
import com.moltenbits.sideband.journal.Journal;
import com.moltenbits.sideband.pending.Addressing;
import com.moltenbits.sideband.pending.Pending;
import com.moltenbits.sideband.pending.PendingReport;
import com.moltenbits.sideband.protocol.MessageType;
import com.moltenbits.sideband.protocol.Role;
import com.moltenbits.sideband.session.Session;
import com.moltenbits.sideband.session.Sessions;
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
 * Everything a role has to look at, derived from the journal: unanswered requests to it,
 * informational entries it has not been shown, and its own requests still awaiting a reply.
 * Printing the report advances the role's read position past the updates.
 * <p>
 * With {@code --wait} the command first blocks, at no model cost, until something new for
 * the role arrives; with {@code --stream} it keeps doing that forever, one report per batch.
 * A waiting report is a delivery, not a read: it never advances the read position, because
 * a host notification may be truncated, so the model's own plain {@code pending} is what
 * marks updates shown. Both forms sit under a host facility, Claude Code's Monitor or a
 * background task, that turns the output into a wake-up.
 */
@Command(name = "pending", description = "List what is waiting for this client: unanswered requests, unseen updates, and your own unanswered requests. --wait blocks until something arrives; --stream keeps listening", mixinStandardHelpOptions = true)
@Prototype
public class PendingCommand implements Callable<Integer> {

    /** Wake from the watcher this often even when nothing arrived, so a hung watch is bounded. */
    static final Duration IDLE_RECHECK = Duration.ofSeconds(30);

    @Spec
    CommandSpec spec;

    @Mixin
    Repository repository;

    @Option(names = "--wait", description = "Block until something new for this client arrives, then report")
    boolean wait;

    @Option(names = "--timeout", paramLabel = "SECONDS", description = "With --wait (not --stream): give up after this long with the timed-out exit code, still printing the report")
    Long timeoutSeconds;

    @Option(names = "--stream", description = "With --wait: keep listening forever, one report per batch. A waited report never advances the read position; a plain pending does")
    boolean stream;

    @Option(names = "--from", hidden = true, description = "Byte offset to watch from (default: the session's read position)")
    Long from;

    @Option(names = "--role", hidden = true, description = "Override the client detected from the environment")
    Role role;

    @Option(names = "--max-batches", hidden = true, description = "With --stream: stop after this many reports (for tests)")
    Integer maxBatches;

    private final SidebandHome home;
    private final HostEnvironment host;
    private final Sessions sessions;
    private final Pending pending;
    private final JournalWatcher watcher;
    private final ObjectMapper json;

    PendingCommand(SidebandHome home, HostEnvironment host, Sessions sessions, Pending pending, JournalWatcher watcher, ObjectMapper json) {
        this.home = home;
        this.host = host;
        this.sessions = sessions;
        this.pending = pending;
        this.watcher = watcher;
        this.json = json;
    }

    @Override
    public Integer call() throws IOException {
        if (!wait && (timeoutSeconds != null || stream)) {
            throw new IllegalArgumentException((stream ? "--stream" : "--timeout") + " only applies with --wait");
        }
        if (timeoutSeconds != null && stream) {
            throw new IllegalArgumentException("--timeout does not apply to --stream, which listens until stopped");
        }
        if (timeoutSeconds != null && timeoutSeconds < 0) {
            throw new IllegalArgumentException("--timeout must not be negative");
        }
        if (from != null && from < 0) {
            throw new IllegalArgumentException("--from must not be negative");
        }
        Role who = role != null ? role : host.requireRole("--role");
        Path stateDirectory = repository.stateDirectory(home);
        if (!wait) {
            return print(stateDirectory, who, true, ExitCode.OK);
        }
        Path file = stateDirectory.resolve(Journal.FILE_NAME);
        Predicate<Entry> wanted = entry -> Addressing.concerns(entry.metadata(), who) && entry.metadata().type() != MessageType.ACK;
        long offset = from != null ? from : sessions.load(stateDirectory, who).map(Session::offset).orElse(0L);
        Duration timeout = stream ? IDLE_RECHECK : timeoutSeconds == null ? null : Duration.ofSeconds(timeoutSeconds);
        int limit = stream ? maxBatches == null ? Integer.MAX_VALUE : maxBatches : 1;
        for (int reports = 0; reports < limit;) {
            Waited waited = watcher.await(file, offset, timeout, wanted);
            offset = waited.read().end();
            if (waited.timedOut()) {
                if (stream) {
                    continue;
                }
                return print(stateDirectory, who, false, ExitCode.TIMED_OUT);
            }
            int written = print(stateDirectory, who, false, ExitCode.OK);
            if (written != ExitCode.OK) {
                return written; // a listener that lost its output is not a listener
            }
            reports++;
        }
        return ExitCode.OK;
    }

    /** Prints first and moves the bookmark second, so a report nobody received is not marked shown. */
    private int print(Path stateDirectory, Role who, boolean advance, int exit) throws IOException {
        PendingReport report = pending.report(stateDirectory, who);
        PrintWriter out = spec.commandLine().getOut();
        out.println(json.writeValueAsString(report));
        out.flush();
        if (out.checkError()) {
            spec.commandLine().getErr().println("sideband pending: could not write the report; the read position was not advanced");
            return ExitCode.IO_FAILURE;
        }
        if (advance && report.session() != null) {
            sessions.advance(stateDirectory, who, report.end());
        }
        return exit;
    }
}
