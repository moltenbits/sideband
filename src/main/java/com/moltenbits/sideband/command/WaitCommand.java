package com.moltenbits.sideband.command;

import com.moltenbits.sideband.handoff.Batch;
import com.moltenbits.sideband.handoff.Handoffs;
import com.moltenbits.sideband.home.SidebandHome;
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
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.function.Predicate;

/**
 * Blocks until complete entries exist past a byte offset, then prints one batch and exits.
 * With {@code --role}, only open entries for that role are returned. For a listener that
 * should never need re-arming, see {@code follow}.
 */
@Command(name = "wait", description = "Block until complete entries are appended after a byte offset, then print one batch")
@Prototype
public class WaitCommand implements Callable<Integer> {

    @Spec
    CommandSpec spec;

    @Mixin
    Repository repository;

    @Option(names = "--from", required = true, description = "Byte offset to watch from; entries before it are ignored")
    long from;

    @Option(names = "--role", description = "Return only open entries addressed to this role: claude or codex")
    Role role;

    @Option(names = "--timeout", description = "Seconds to wait before giving up; waits indefinitely when omitted")
    Long timeoutSeconds;

    private final SidebandHome home;
    private final JournalWatcher watcher;
    private final RecipientState recipients;
    private final Handoffs handoffs;
    private final ObjectMapper json;

    WaitCommand(SidebandHome home, JournalWatcher watcher, RecipientState recipients, Handoffs handoffs, ObjectMapper json) {
        this.home = home;
        this.watcher = watcher;
        this.recipients = recipients;
        this.handoffs = handoffs;
        this.json = json;
    }

    @Override
    public Integer call() throws IOException {
        if (from < 0) {
            throw new IllegalArgumentException("--from must not be negative");
        }
        if (timeoutSeconds != null && timeoutSeconds < 0) {
            throw new IllegalArgumentException("--timeout must not be negative");
        }
        Path stateDirectory = home.locate(repository.directory);
        Path file = stateDirectory.resolve(Journal.FILE_NAME);
        Duration timeout = timeoutSeconds == null ? null : Duration.ofSeconds(timeoutSeconds);
        Waited waited = watcher.await(file, from, timeout, filter(stateDirectory));
        Output.print(spec, json, batch(file, waited));
        return waited.timedOut() ? ExitCode.TIMED_OUT : ExitCode.OK;
    }

    /** Open entries for the role, or everything when no role is given. */
    Predicate<Entry> filter(Path stateDirectory) {
        if (role == null) {
            return entry -> true;
        }
        Cursor cursor = recipients.load(stateDirectory, role);
        return entry -> recipients.isOpen(cursor, entry);
    }

    Batch batch(Path file, Waited waited) {
        Read read = waited.read();
        return new Batch(read.start(), read.end(), handoffs.prepare(file, read.entries()), read.diagnostics(), waited.timedOut());
    }
}
